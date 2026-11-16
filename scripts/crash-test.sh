#!/usr/bin/env bash
# Crash test: kill the worker mid-task, restart it, verify Temporal resumes the workflow.
# Requires: docker compose --profile app up -d --build
set -euo pipefail

API="${API:-http://localhost:8080}"
KEY="${DRA_API_KEY:-dev-key}"
IDEM="crash-$(date +%s)-$RANDOM"

RESP=$(curl -sS -X POST "$API/api/v1/research/tasks" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: $IDEM" \
  --data-binary @- <<'JSON'
{"question":"长任务编排的崩溃恢复应该怎么做？","depth":"STANDARD","deadlineMinutes":10}
JSON
)

TASK_ID=$(printf '%s' "$RESP" | python -c "import sys,json; print(json.load(sys.stdin)['taskId'])")
echo "taskId: $TASK_ID"
sleep 3

echo "--- killing worker-io (workflow + io activities) ---"
docker compose kill worker-io
sleep 3

echo "--- restarting worker-io ---"
docker compose start worker-io

echo "--- waiting for task to finish (max 180s) ---"
for _ in $(seq 1 90); do
  STATUS=$(curl -sS "$API/api/v1/research/tasks/$TASK_ID" -H "X-API-Key: $KEY")
  STATE=$(printf '%s' "$STATUS" | python -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "$STATUS"
  if [ "$STATE" = "SUCCEEDED" ] || [ "$STATE" = "FAILED" ] || [ "$STATE" = "CANCELED" ]; then
    echo "final status: $STATE"
    exit 0
  fi
  sleep 2
done

echo "task did not finish within timeout"
exit 1
