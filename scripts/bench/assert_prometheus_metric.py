#!/usr/bin/env python3
"""Assert that one Prometheus text metric reaches a minimum value."""

import argparse
import math
import time
from urllib.request import urlopen


def metric_values(payload, metric):
    values = []
    for raw_line in payload.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        identity, separator, remainder = line.partition(" ")
        if not separator:
            continue
        name = identity.partition("{")[0]
        if name != metric:
            continue
        try:
            values.append(float(remainder.split()[0]))
        except (IndexError, ValueError):
            continue
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--metric", required=True)
    parser.add_argument("--minimum", type=float, required=True)
    parser.add_argument("--attempts", type=int, default=10)
    parser.add_argument("--delay", type=float, default=0.2)
    args = parser.parse_args()
    if args.attempts < 1 or args.delay < 0 or not math.isfinite(args.minimum):
        raise SystemExit("attempts/delay/minimum are outside their valid range")

    last_values = []
    last_error = None
    for attempt in range(args.attempts):
        try:
            with urlopen(args.url, timeout=5) as response:
                payload = response.read().decode("utf-8")
            last_values = metric_values(payload, args.metric)
            last_error = None
            if last_values and sum(last_values) >= args.minimum:
                print(
                    f"PASS: {args.metric} total {sum(last_values):g} "
                    f">= {args.minimum:g}"
                )
                return
        except Exception as error:
            last_error = error
        if attempt + 1 < args.attempts:
            time.sleep(args.delay)

    detail = f"last error: {last_error}" if last_error else f"values: {last_values}"
    raise SystemExit(
        f"FAIL: {args.metric} did not reach {args.minimum:g}; {detail}"
    )


if __name__ == "__main__":
    main()
