#!/usr/bin/env bash
# Smoke test: submit a task, stream SSE, print status and report.
set -euo pipefail

API="${API:-http://localhost:8080}"
KEY="${DRA_API_KEY:-dev-key}"
IDEM="smoke-$(date +%s)-$RANDOM"

RESP=$(curl -sS -X POST "$API/api/v1/research/tasks" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: $IDEM" \
  --data-binary @- <<'JSON'
{"question":"Temporal 和 LangGraph 在长任务编排上各自适合什么场景？","depth":"QUICK","deadlineMinutes":5}
JSON
)

echo "submit response: $RESP"
TASK_ID=$(printf '%s' "$RESP" | python -c "import sys,json; print(json.load(sys.stdin)['taskId'])")
echo "taskId: $TASK_ID"

echo "--- SSE (max 60s) ---"
curl -sS -N --max-time 60 "$API/api/v1/research/tasks/$TASK_ID/events" \
  -H "X-API-Key: $KEY" || true

echo
echo "--- status ---"
curl -sS "$API/api/v1/research/tasks/$TASK_ID" -H "X-API-Key: $KEY"
echo
echo "--- report (markdown) ---"
curl -sS "$API/api/v1/research/tasks/$TASK_ID/report?format=markdown" -H "X-API-Key: $KEY"
echo
