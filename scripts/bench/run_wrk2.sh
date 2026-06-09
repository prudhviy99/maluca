#!/usr/bin/env bash
# Latency benchmark with wrk2 (constant-throughput, coordinated-omission-aware).
# Install: https://github.com/giltene/wrk2
#
# Runs the three reference configurations the benchmark report compares:
#   1. direct-to-backend (baseline overhead floor)
#   2. Maluca pass-through-ish (low rps, everything allowed)
#   3. Maluca under stress (high rps, scoring + Redis on every request)
set -euo pipefail

PROXY="${PROXY:-http://localhost:8080}"
BACKEND="${BACKEND:-http://localhost:8081}"
RATE="${RATE:-2000}"
DURATION="${DURATION:-30s}"
THREADS="${THREADS:-4}"
CONNS="${CONNS:-100}"

if ! command -v wrk >/dev/null 2>&1; then
  echo "wrk2 (wrk) not found. Install from https://github.com/giltene/wrk2"
  echo "Falling back to the stdlib bench:"
  python3 "$(dirname "$0")/latency_bench.py" --target "$PROXY" --rps 200 --duration 10 --label "maluca-fallback"
  exit 0
fi

echo "### 1. Direct to backend (baseline) @ ${RATE} rps"
wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" -R"$RATE" --latency "$BACKEND/" || true

echo "### 2. Through Maluca @ ${RATE} rps"
wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" -R"$RATE" --latency "$PROXY/" || true

echo "Compare the p99/p99.9 lines: the delta is Maluca's added latency under load."
