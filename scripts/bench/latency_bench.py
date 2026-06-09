#!/usr/bin/env python3
"""Closed-loop latency benchmark with a coordinated-omission correction.

Most naive benchmarks (and `ab`) send the next request only after the previous
one returns. Under load that *hides* tail latency: while one slow response is
in flight, the requests that should have been sent during that window simply
aren't — so the slow period under-samples itself (Gil Tene's "coordinated
omission"). We correct for it the standard way: requests are scheduled at a
fixed rate on a wall-clock timeline, and each sample's latency is measured from
its *intended* send time, not its actual send time. A stalled server therefore
shows the backlog in the numbers instead of erasing it.

Stdlib only. For serious numbers use wrk2 (see scripts/bench/run_wrk2.sh);
this exists so the harness runs with zero dependencies.
"""
import argparse
import http.client
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


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--target", default="http://localhost:8080")
    ap.add_argument("--path", default="/")
    ap.add_argument("--rps", type=int, default=200, help="constant offered load")
    ap.add_argument("--duration", type=float, default=10.0)
    ap.add_argument("--label", default="run")
    args = ap.parse_args()

    parsed = urlparse(args.target)
    host, port = parsed.hostname, parsed.port or 80
    interval = 1.0 / args.rps

    latencies = []        # corrected for coordinated omission
    lock = threading.Lock()
    codes = {}

    start = time.perf_counter()
    n = int(args.rps * args.duration)

    def fire(intended_offset):
        intended = start + intended_offset
        conn = http.client.HTTPConnection(host, port, timeout=10)
        try:
            conn.request("GET", args.path)
            resp = conn.getresponse()
            resp.read()
            code = resp.status
        except Exception:
            code = 0
        finally:
            conn.close()
        done = time.perf_counter()
        # measure from intended send time -> coordinated-omission correction
        with lock:
            latencies.append((done - intended) * 1000.0)
            codes[code] = codes.get(code, 0) + 1

    threads = []
    for i in range(n):
        intended_offset = i * interval
        now = time.perf_counter() - start
        if intended_offset > now:
            time.sleep(intended_offset - now)
        t = threading.Thread(target=fire, args=(intended_offset,))
        t.start()
        threads.append(t)
    for t in threads:
        t.join()

    latencies.sort()
    print(f"\n=== {args.label}: {args.path} @ {args.rps} rps for {args.duration}s ===")
    print(f"  samples : {len(latencies)}")
    print(f"  codes   : {codes}")
    print(f"  p50     : {percentile(latencies, 0.50):8.2f} ms")
    print(f"  p95     : {percentile(latencies, 0.95):8.2f} ms")
    print(f"  p99     : {percentile(latencies, 0.99):8.2f} ms")
    print(f"  p99.9   : {percentile(latencies, 0.999):8.2f} ms")
    print(f"  max     : {latencies[-1]:8.2f} ms" if latencies else "  max: n/a")


if __name__ == "__main__":
    main()
