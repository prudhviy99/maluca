#!/usr/bin/env python3
"""Fixed-rate HTTP latency smoke benchmark with coordinated-omission correction.

Requests are scheduled on a wall-clock timeline and latency is measured from
the intended send time. A stalled server therefore exposes its backlog instead
of under-sampling it. The harness is stdlib-only so CI can run it without a
benchmark package; use wrk2 for capacity measurements.
"""

import argparse
import http.client
import json
import math
from pathlib import Path
import threading
import time
from urllib.parse import urlparse


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def run_load(parsed, path, rps, duration, request_timeout):
    interval = 1.0 / rps
    latencies = []
    lock = threading.Lock()
    codes = {}
    start = time.perf_counter()
    request_count = int(rps * duration)
    connection_type = (
        http.client.HTTPSConnection if parsed.scheme == "https"
        else http.client.HTTPConnection
    )

    def fire(intended_offset):
        intended = start + intended_offset
        connection = connection_type(
            parsed.hostname,
            parsed.port or (443 if parsed.scheme == "https" else 80),
            timeout=request_timeout,
        )
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            response.read()
            code = response.status
        except Exception:
            code = 0
        finally:
            connection.close()
        done = time.perf_counter()
        with lock:
            latencies.append((done - intended) * 1000.0)
            codes[code] = codes.get(code, 0) + 1

    threads = []
    for index in range(request_count):
        intended_offset = index * interval
        remaining = intended_offset - (time.perf_counter() - start)
        if remaining > 0:
            time.sleep(remaining)
        thread = threading.Thread(target=fire, args=(intended_offset,))
        thread.start()
        threads.append(thread)
    for thread in threads:
        thread.join()

    latencies.sort()
    return {
        "samples": len(latencies),
        "codes": dict(sorted(codes.items())),
        "p50_ms": percentile(latencies, 0.50),
        "p95_ms": percentile(latencies, 0.95),
        "p99_ms": percentile(latencies, 0.99),
        "p999_ms": percentile(latencies, 0.999),
        "max_ms": latencies[-1] if latencies else 0.0,
    }


def print_summary(label, path, rps, duration, result):
    print(f"\n=== {label}: {path} @ {rps} rps for {duration}s ===")
    print(f"  samples : {result['samples']}")
    print(f"  codes   : {result['codes']}")
    print(f"  p50     : {result['p50_ms']:8.2f} ms")
    print(f"  p95     : {result['p95_ms']:8.2f} ms")
    print(f"  p99     : {result['p99_ms']:8.2f} ms")
    print(f"  p99.9   : {result['p999_ms']:8.2f} ms")
    print(f"  max     : {result['max_ms']:8.2f} ms")


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", default="http://localhost:8080")
    parser.add_argument("--path", default="/")
    parser.add_argument("--rps", type=int, default=200, help="constant offered load")
    parser.add_argument("--duration", type=float, default=10.0)
    parser.add_argument(
        "--warmup-duration", type=float, default=0.0,
        help="fixed-rate warm-up discarded before the measured interval",
    )
    parser.add_argument("--request-timeout", type=float, default=10.0)
    parser.add_argument("--label", default="run")
    parser.add_argument("--output-json", type=Path)
    parser.add_argument(
        "--max-p99-ms", type=float,
        help="fail if measured end-to-end p99 exceeds this ceiling",
    )
    parser.add_argument(
        "--max-transport-error-rate", type=float,
        help="fail if connection/timeout failures divided by samples exceed this value",
    )
    parser.add_argument(
        "--max-http-5xx-rate", type=float,
        help="fail if HTTP 5xx responses divided by samples exceed this value",
    )
    parser.add_argument(
        "--baseline-json", type=Path,
        help="prior latency_bench JSON result used for candidate comparison",
    )
    parser.add_argument(
        "--max-p99-regression-ms", type=float,
        help="fail if candidate p99 exceeds baseline p99 by more than this amount",
    )
    return parser.parse_args()


def validate_args(args, parsed):
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise SystemExit("--target must be an absolute HTTP(S) URL")
    if args.rps <= 0 or args.duration <= 0 or args.warmup_duration < 0:
        raise SystemExit("--rps and --duration must be positive; warm-up cannot be negative")
    if args.request_timeout <= 0:
        raise SystemExit("--request-timeout must be positive")
    if (args.baseline_json is None) != (args.max_p99_regression_ms is None):
        raise SystemExit(
            "--baseline-json and --max-p99-regression-ms must be supplied together"
        )
    for name in ("max_p99_ms", "max_p99_regression_ms"):
        value = getattr(args, name)
        if value is not None and (not math.isfinite(value) or value < 0):
            raise SystemExit(f"--{name.replace('_', '-')} must be finite and non-negative")
    for name in ("max_transport_error_rate", "max_http_5xx_rate"):
        value = getattr(args, name)
        if value is not None and not (0.0 <= value <= 1.0):
            raise SystemExit(
                f"--{name.replace('_', '-')} must be between 0 and 1"
            )


def main():
    args = parse_args()
    parsed = urlparse(args.target)
    validate_args(args, parsed)

    if args.warmup_duration > 0:
        print(
            f"Warming {args.label} at {args.rps} rps for "
            f"{args.warmup_duration}s (samples discarded)..."
        )
        warmup = run_load(
            parsed, args.path, args.rps, args.warmup_duration, args.request_timeout
        )
        print(f"Warm-up responses: {warmup['codes']}")

    result = run_load(parsed, args.path, args.rps, args.duration, args.request_timeout)
    result.update({
        "label": args.label,
        "target": args.target,
        "path": args.path,
        "rps": args.rps,
        "duration_seconds": args.duration,
        "warmup_seconds": args.warmup_duration,
    })
    print_summary(args.label, args.path, args.rps, args.duration, result)

    if args.output_json is not None:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    failures = []
    if args.max_p99_ms is not None and result["p99_ms"] > args.max_p99_ms:
        failures.append(
            f"p99 {result['p99_ms']:.2f} ms exceeds {args.max_p99_ms:.2f} ms"
        )
    if args.max_transport_error_rate is not None:
        transport_errors = result["codes"].get(0, 0)
        error_rate = transport_errors / result["samples"] if result["samples"] else 1.0
        if error_rate > args.max_transport_error_rate:
            failures.append(
                f"transport error rate {error_rate:.4f} exceeds "
                f"{args.max_transport_error_rate:.4f}"
            )
    if args.max_http_5xx_rate is not None:
        server_errors = sum(
            count for code, count in result["codes"].items() if 500 <= code < 600
        )
        error_rate = server_errors / result["samples"] if result["samples"] else 1.0
        if error_rate > args.max_http_5xx_rate:
            failures.append(
                f"HTTP 5xx rate {error_rate:.4f} exceeds "
                f"{args.max_http_5xx_rate:.4f}"
            )
    if args.baseline_json is not None:
        baseline = json.loads(args.baseline_json.read_text(encoding="utf-8"))
        regression = result["p99_ms"] - float(baseline["p99_ms"])
        print(
            f"  p99 delta vs {baseline.get('label', 'baseline')}: "
            f"{regression:+.2f} ms"
        )
        if regression > args.max_p99_regression_ms:
            failures.append(
                f"p99 regression {regression:.2f} ms exceeds "
                f"{args.max_p99_regression_ms:.2f} ms"
            )

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        raise SystemExit(1)
    if any(value is not None for value in (
        args.max_p99_ms,
        args.max_transport_error_rate,
        args.max_http_5xx_rate,
        args.max_p99_regression_ms,
    )):
        print("PASS: configured latency and response bounds were satisfied")


if __name__ == "__main__":
    main()
