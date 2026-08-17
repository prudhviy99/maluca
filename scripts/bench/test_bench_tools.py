import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import latency_bench
from assert_prometheus_metric import metric_values


class _NoLogHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(204)
        self.end_headers()

    def log_message(self, _format, *_args):
        pass


class BenchmarkToolsTest(unittest.TestCase):
    def test_percentile_interpolates(self):
        self.assertEqual(1.0, latency_bench.percentile([1.0], 0.99))
        self.assertEqual(2.5, latency_bench.percentile([1.0, 2.0, 3.0, 4.0], 0.5))

    def test_fixed_rate_run_records_http_responses(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), _NoLogHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = latency_bench.run_load(
                urlparse(f"http://127.0.0.1:{server.server_port}"),
                "/health", 20, 0.2, 1.0,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=1)

        self.assertEqual(4, result["samples"])
        self.assertEqual({204: 4}, result["codes"])
        self.assertGreaterEqual(result["p99_ms"], 0)

    def test_prometheus_parser_sums_labeled_and_unlabeled_series(self):
        payload = """
        # HELP example_total Example
        example_total 2.0
        example_total{result="retry"} 3.5
        unrelated_total 99
        """
        self.assertEqual([2.0, 3.5], metric_values(payload, "example_total"))


if __name__ == "__main__":
    unittest.main()
