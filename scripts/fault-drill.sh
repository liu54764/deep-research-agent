#!/usr/bin/env bash
# S4 fault drill: run a load test, kill the io/orchestration worker mid-flight, restart it,
# and let dra-bench report whether every task still finished. Mock search + stub LLM by
# default, so the drill costs nothing and the timing is deterministic.
#
# Prereqs: docker compose up -d mysql redis temporal; mvn -B package -DskipTests
# Env knobs: TASKS, CONCURRENCY, DEPTH, KILL_AFTER_SECONDS, DOWN_SECONDS, API_URL, API_KEY.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

API_URL="${API_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-key}"
TASKS="${TASKS:-8}"
CONCURRENCY="${CONCURRENCY:-4}"
DEPTH="${DEPTH:-QUICK}"
KILL_AFTER_SECONDS="${KILL_AFTER_SECONDS:-4}"
DOWN_SECONDS="${DOWN_SECONDS:-20}"
JAVA_BIN="${JAVA_BIN:-java}"

API_JAR="$ROOT/dra-api/target/dra-api-0.1.0-SNAPSHOT.jar"
WORKER_JAR="$ROOT/dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar"
BENCH_JAR="$ROOT/dra-bench/target/dra-bench-0.1.0-SNAPSHOT.jar"

for jar in "$API_JAR" "$WORKER_JAR" "$BENCH_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "missing $jar; run: mvn -B package -DskipTests" >&2
    exit 2
  fi
done

if [ -f .env.local ]; then
  # shellcheck disable=SC1091
  set -a; . ./.env.local; set +a
fi
export DRA_HTTP_PROXY=
export DRA_SEARCH_PROVIDER=mock DRA_LLM_ENABLED=false DRA_FETCH_MODE=stub

API_PID=""
IO_PID=""
LLM_PID=""
BENCH_PID=""

cleanup() {
  for pid in "$BENCH_PID" "$IO_PID" "$LLM_PID" "$API_PID"; do
    if [ -n "$pid" ]; then
      kill "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

log "starting API"
"$JAVA_BIN" -jar "$API_JAR" > /tmp/fault-api.log 2>&1 &
API_PID=$!
for _ in $(seq 1 60); do
  if curl -sf "$API_URL/actuator/health" -H "X-API-Key: $API_KEY" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

log "starting worker-io (roles=orchestration,io)"
DRA_WORKER_ROLES=orchestration,io "$JAVA_BIN" -jar "$WORKER_JAR" > /tmp/fault-worker-io.log 2>&1 &
IO_PID=$!
sleep 6

log "starting worker-llm (roles=llm)"
DRA_WORKER_ROLES=llm "$JAVA_BIN" -jar "$WORKER_JAR" > /tmp/fault-worker-llm.log 2>&1 &
LLM_PID=$!
sleep 6

log "submitting load: tasks=$TASKS concurrency=$CONCURRENCY depth=$DEPTH"
"$JAVA_BIN" -jar "$BENCH_JAR" --api-url="$API_URL" --api-key="$API_KEY" \
  --tasks="$TASKS" --concurrency="$CONCURRENCY" --depth="$DEPTH" \
  > /tmp/fault-bench.log 2>&1 &
BENCH_PID=$!

sleep "$KILL_AFTER_SECONDS"
KILLED_AT=$(date +%s)
log "killing worker-io pid=$IO_PID"
kill -9 "$IO_PID" 2>/dev/null || true
wait "$IO_PID" 2>/dev/null || true
IO_PID=""

log "worker-io down for ${DOWN_SECONDS}s (Temporal retries in-flight io activities)"
sleep "$DOWN_SECONDS"

log "restarting worker-io"
DRA_WORKER_ROLES=orchestration,io "$JAVA_BIN" -jar "$WORKER_JAR" >> /tmp/fault-worker-io.log 2>&1 &
IO_PID=$!
RESTARTED_AT=$(date +%s)

log "waiting for bench"
BENCH_EXIT=0
wait "$BENCH_PID" || BENCH_EXIT=$?
BENCH_PID=""
FINISHED_AT=$(date +%s)

cat /tmp/fault-bench.log
log "downtime=$((RESTARTED_AT - KILLED_AT))s, bench finished ${FINISHED_AT:+$((FINISHED_AT - KILLED_AT))}s after kill, exit=$BENCH_EXIT"
log "logs: /tmp/fault-api.log /tmp/fault-worker-io.log /tmp/fault-worker-llm.log /tmp/fault-bench.log"
exit "$BENCH_EXIT"
