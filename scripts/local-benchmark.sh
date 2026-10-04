#!/usr/bin/env bash
set -Eeuo pipefail

# =============================================================================
# local-benchmark.sh — run the graphql-gateway-benchmarks heavy query against
# this feddi checkout, for before/after comparisons on one machine.
#
#   scripts/local-benchmark.sh <graphql-gateway-benchmarks-dir> [seconds] [vus] [subgraph-delay-ms]
#
# - Subgraphs: the benchmark's Rust subgraphs, built and run in Docker (no Rust
#   toolchain needed on the host). Ports 5221-5224.
# - Gateway: built from this checkout (:app:feddiGatewayDistZip), configured with
#   the benchmark's feddi config and the canonical .NET subgraph SDLs. Port 5220.
# - Load: k6 with the benchmark's k6/k6.js (constant mode).
#
# Numbers are only comparable with other runs on the same machine. Docker port
# forwarding adds overhead on macOS. This script never runs the benchmark repo's
# install/build scripts (they would install toolchains).
#
# Requirements: docker, k6, curl, zip, a JDK 25+ on PATH (or JAVA_HOME).
# =============================================================================

BENCH_DIR="$(cd "${1:?usage: $0 <graphql-gateway-benchmarks-dir> [seconds] [vus] [subgraph-delay-ms]}" && pwd)"
SECONDS_TO_RUN="${2:-30}"
VUS="${3:-50}"
DELAY_MS="${4:-0}"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$REPO_DIR/gateway/build/local-benchmark"
FEDDI_CFG="$BENCH_DIR/composite-schema/gateways/feddi"
IMAGE="feddi-bench-subgraphs-rust"
CONTAINER="feddi-bench-subgraphs"

cleanup() {
  [[ -n "${GATEWAY_PID:-}" ]] && kill "$GATEWAY_PID" 2>/dev/null || true
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "==> Building Rust subgraphs image ($IMAGE)"
docker build -q -t "$IMAGE" -f - "$BENCH_DIR/composite-schema/subgraphs-rust" <<'DOCKERFILE' >/dev/null
FROM rust:1-slim AS build
WORKDIR /src
COPY . .
RUN cargo build --release
FROM debian:stable-slim
COPY --from=build /src/target/release/subgraphs /usr/local/bin/subgraphs
EXPOSE 5221 5222 5223 5224
CMD ["subgraphs"]
DOCKERFILE

echo "==> Starting subgraphs (delay ${DELAY_MS}ms)"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e SUBGRAPH_DELAY_MS="$DELAY_MS" \
  -p 5221-5224:5221-5224 "$IMAGE" >/dev/null

echo "==> Building feddi from $REPO_DIR"
( cd "$REPO_DIR/gateway" && ./gradlew -q :app:feddiGatewayDistZip )
rm -rf "$WORK_DIR" && mkdir -p "$WORK_DIR"
unzip -q "$REPO_DIR/gateway/app/build/distributions/feddi-gateway.zip" -d "$WORK_DIR/dist"
cp "$FEDDI_CFG/feddi-gateway.yml" "$WORK_DIR/"
cp -R "$FEDDI_CFG/subgraph-config" "$WORK_DIR/"
for entry in accounts:eShop.Accounts inventory:eShop.Inventory products:eShop.Products reviews:eShop.Reviews; do
  cp "$BENCH_DIR/composite-schema/subgraphs-net/${entry##*:}/schema.graphql" \
     "$WORK_DIR/subgraph-config/${entry%%:*}/schema.graphqls"
done
( cd "$WORK_DIR/subgraph-config" && zip -qr "$WORK_DIR/subgraphs.zip" . )

echo "==> Starting feddi"
export JAVA_OPTS="${JAVA_OPTS:-} -Dreactor.netty.pool.maxConnections=4096 -Dreactor.netty.pool.acquireTimeout=60000"
( cd "$WORK_DIR" && exec "$WORK_DIR/dist/feddi-gateway/bin/feddi-gateway" ) > "$WORK_DIR/gateway_log.txt" 2>&1 &
GATEWAY_PID=$!

deadline=$((SECONDS + 90))
until curl -s --max-time 5 -X POST -H 'Content-Type: application/octet-stream' \
        --data-binary @"$WORK_DIR/subgraphs.zip" http://127.0.0.1:9091/admin/upload | grep -q '"success":true'; do
  kill -0 "$GATEWAY_PID" 2>/dev/null || { echo "gateway exited, see $WORK_DIR/gateway_log.txt"; exit 1; }
  (( SECONDS < deadline )) || { echo "subgraph upload timed out"; exit 1; }
  sleep 0.5
done
until curl -s --max-time 5 -X POST -H 'Content-Type: application/json' \
        --data '{"query":"{ topProducts { upc } }"}' http://localhost:5220/graphql | grep -q '"data"'; do
  (( SECONDS < deadline )) || { echo "gateway not serving queries"; exit 1; }
  sleep 0.5
done

echo "==> Warmup (10s), then measuring ${SECONDS_TO_RUN}s with ${VUS} VUs"
( cd "$BENCH_DIR/k6" && MODE=constant BENCH_VUS="$VUS" BENCH_OVER_TIME=10s \
    k6 run --quiet --no-summary k6.js >/dev/null 2>&1 ) || true
( cd "$BENCH_DIR/k6" && MODE=constant BENCH_VUS="$VUS" BENCH_OVER_TIME="${SECONDS_TO_RUN}s" \
    k6 run --quiet --no-color k6.js ) 2>&1 | grep -E "checks|http_reqs|http_req_duration|success_rate|✗|✓" || true
