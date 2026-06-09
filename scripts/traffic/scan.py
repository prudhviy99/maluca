#!/usr/bin/env python3
"""Path scanner: one client enumerating many distinct paths quickly. Drives the
distinct-paths-per-30s signal (path_scan) and the upstream 4xx ratio as it
hits non-existent endpoints.
"""
import common

WORDLIST = [
    "/admin", "/.env", "/wp-login.php", "/config.json", "/backup.zip",
    "/api/v1/users", "/api/internal", "/.git/config", "/phpmyadmin",
    "/server-status", "/actuator/env", "/debug", "/console", "/.aws/credentials",
]


def main():
    parser = common.base_parser(__doc__)
    args = parser.parse_args()

    stats = common.Stats()
    headers = {"User-Agent": "python-requests/2.31.0"}
    i = 0

    def hit():
        nonlocal i
        path = WORDLIST[i % len(WORDLIST)] + f"?v={i}"
        i += 1
        stats.record(common.request(args.target, "GET", path, headers))

    common.run_for(args.duration, hit)
    stats.report("path scan")


if __name__ == "__main__":
    main()
