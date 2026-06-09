#!/usr/bin/env python3
"""Burst flood: N concurrent workers hammering one path from one IP as fast as
possible. Exercises the rate limiter and the burst/sustained score signals —
expect a rapid slide into 429 then 403.
"""
import threading

import common


def main():
    parser = common.base_parser(__doc__)
    parser.add_argument("--workers", type=int, default=20)
    parser.add_argument("--path", default="/api/products")
    args = parser.parse_args()

    stats = common.Stats()
    headers = {"User-Agent": "burst-bot/1.0"}

    def worker():
        common.run_for(args.duration,
                       lambda: stats.record(common.request(args.target, "GET", args.path, headers)))

    threads = [threading.Thread(target=worker) for _ in range(args.workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    stats.report("burst flood")


if __name__ == "__main__":
    main()
