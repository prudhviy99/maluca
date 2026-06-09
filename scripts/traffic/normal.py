#!/usr/bin/env python3
"""Realistic browser traffic: Poisson arrivals, varied paths, browser headers,
human think time. This is the reference 'good' traffic — Maluca should leave
it almost entirely alone, and it's the baseline for shadow-mode false-positive
measurement.
"""
import random
import time

import common


def main():
    parser = common.base_parser(__doc__)
    parser.add_argument("--rps", type=float, default=3.0, help="mean requests/sec (Poisson)")
    args = parser.parse_args()

    stats = common.Stats()
    ua = random.choice(common.BROWSER_UAS)
    headers = {**common.BROWSER_HEADERS, "User-Agent": ua}
    deadline = time.time() + args.duration

    while time.time() < deadline:
        path = random.choice(common.BROWSE_PATHS)
        stats.record(common.request(args.target, "GET", path, headers))
        # exponential inter-arrival ≈ Poisson process
        time.sleep(random.expovariate(args.rps))

    stats.report("normal browser")


if __name__ == "__main__":
    main()
