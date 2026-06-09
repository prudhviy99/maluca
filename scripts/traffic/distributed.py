#!/usr/bin/env python3
"""Distributed flood from many spoofed IPs (via X-Forwarded-For, for the demo —
assumes Maluca trusts this host as a proxy). High aggregate rate spread across
100 source IPs: per-IP limiting lets it through, which is exactly the case that
motivates composite identity and global anomaly detection.
"""
import random
import threading

import common


def main():
    parser = common.base_parser(__doc__)
    parser.add_argument("--ips", type=int, default=100)
    parser.add_argument("--workers", type=int, default=20)
    args = parser.parse_args()

    stats = common.Stats()
    ips = [f"203.0.{i // 254}.{i % 254 + 1}" for i in range(args.ips)]

    def worker():
        def hit():
            headers = {"User-Agent": "Mozilla/5.0", "X-Forwarded-For": random.choice(ips)}
            stats.record(common.request(args.target, "GET", "/search?q=" + common.rand_string(), headers))
        common.run_for(args.duration, hit)

    threads = [threading.Thread(target=worker) for _ in range(args.workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    stats.report("distributed flood")


if __name__ == "__main__":
    main()
