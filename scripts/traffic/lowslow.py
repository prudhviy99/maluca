#!/usr/bin/env python3
"""Low-and-slow distributed scrape: many simulated clients, each at ~1 rps, each
trying to stay under per-IP thresholds. The interesting attack — only composite
identity (fingerprint keying) catches it, because every client shares the same
script fingerprint even with different spoofed IPs.

Requires Maluca configured with trust-x-forwarded-for + this host as a trusted
proxy, and identity strategy FINGERPRINT or COMPOSITE, to demonstrate the catch.
Without that it shows the evasion (mostly 200s) — which is the honest result.
"""
import threading

import common


def main():
    parser = common.base_parser(__doc__)
    parser.add_argument("--clients", type=int, default=50)
    parser.add_argument("--rps-per-client", type=float, default=1.0)
    args = parser.parse_args()

    stats = common.Stats()
    import time

    def client(ip):
        # identical script fingerprint, distinct spoofed source IP
        headers = {"User-Agent": "python-requests/2.31.0", "X-Forwarded-For": ip}
        interval = 1.0 / args.rps_per_client
        deadline = time.time() + args.duration
        while time.time() < deadline:
            stats.record(common.request(args.target, "GET", "/api/products", headers))
            time.sleep(interval)

    threads = [threading.Thread(target=client, args=(f"198.51.{i // 254}.{i % 254 + 1}",))
               for i in range(args.clients)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    stats.report("low-and-slow distributed")


if __name__ == "__main__":
    main()
