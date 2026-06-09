#!/usr/bin/env python3
"""Credential stuffing: rapid POSTs to /login with changing usernames. Hits the
sensitive-endpoint signal and, against the default policies, the strict
sliding-window-log limiter on /login (5/60s).
"""
import json

import common


def main():
    parser = common.base_parser(__doc__)
    args = parser.parse_args()

    stats = common.Stats()
    headers = {"User-Agent": "Mozilla/5.0", "Content-Type": "application/json"}

    def attempt():
        body = json.dumps({"username": common.rand_string(), "password": common.rand_string(12)})
        stats.record(common.request(args.target, "POST", "/login", headers, body))

    common.run_for(args.duration, attempt)
    stats.report("credential stuffing")


if __name__ == "__main__":
    main()
