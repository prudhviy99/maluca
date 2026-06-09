"""Shared helpers for Maluca traffic-simulation scripts.

Uses only the Python standard library so the harness runs anywhere with
python3 — no pip install needed.
"""
import argparse
import http.client
import random
import string
import threading
import time
from collections import Counter
from urllib.parse import urlparse

BROWSER_UAS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
]

BROWSER_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
}

BROWSE_PATHS = ["/", "/api/products", "/api/products/1", "/api/products/2", "/search?q=phone"]


class Stats:
    """Thread-safe response-code tally."""

    def __init__(self):
        self._counter = Counter()
        self._lock = threading.Lock()

    def record(self, code):
        with self._lock:
            self._counter[code] += 1

    def report(self, title):
        total = sum(self._counter.values())
        print(f"\n=== {title} — {total} requests ===")
        for code in sorted(self._counter):
            n = self._counter[code]
            print(f"  {code}: {n:>6}  ({100 * n / total:5.1f}%)")
        blocked = self._counter.get(403, 0) + self._counter.get(429, 0)
        if total:
            print(f"  mitigated (403+429): {blocked} ({100 * blocked / total:.1f}%)")


def request(target, method, path, headers=None, body=None, timeout=5):
    """One HTTP request. Returns the status code, or 0 on connection error."""
    parsed = urlparse(target)
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=timeout)
    merged = dict(headers or {})
    try:
        conn.request(method, path, body=body, headers=merged)
        resp = conn.getresponse()
        resp.read()
        return resp.status
    except Exception:
        return 0
    finally:
        conn.close()


def rand_string(n=8):
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def base_parser(description):
    p = argparse.ArgumentParser(description=description)
    p.add_argument("--target", default="http://localhost:8080", help="Maluca proxy base URL")
    p.add_argument("--duration", type=float, default=15.0, help="seconds to run")
    return p


def run_for(duration, fn):
    """Call fn() repeatedly until duration elapses."""
    deadline = time.time() + duration
    while time.time() < deadline:
        fn()
