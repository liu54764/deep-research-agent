#!/usr/bin/env python3
"""Minimal OpenAI-compatible mock server for local M6 development.

Usage:
  python scripts/mock-llm.py [port]
  DRA_LLM_ENABLED=true DRA_LLM_BASE_URL=http://127.0.0.1:8099 DRA_LLM_API_KEY=mock \
    java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar

It returns deterministic plan / extract / claim / verify JSON depending on the
system prompt, so the real activity code paths can be exercised without a live key.
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLAN = {
    "subQuestions": [
        {"title": "Durable execution", "question": "What problem does durable execution solve for long-running tasks?",
         "queries": ["durable execution long running workflow", "Temporal workflow state recovery"]},
        {"title": "Graph checkpoints", "question": "How do graph checkpoint frameworks handle long-running agent tasks?",
         "queries": ["LangGraph checkpoint resume", "agent graph state persistence"]},
        {"title": "Trade-offs", "question": "What are the trade-offs between the two approaches?",
         "queries": ["Temporal vs LangGraph", "durable execution vs checkpoint trade-offs"]},
    ]
}

VERIFY = {"verdict": "SUPPORT", "confidence": 0.9,
          "rationale": "Mock verification: quoted evidence supports the claim."}

ID_PATTERN = re.compile(r"([0-9A-Z]{26})")


def extract_response(prompt):
    sub_question_ids = []
    for match in re.finditer(r"subQuestionId=([0-9A-Z]{26})", prompt):
        if match.group(1) not in sub_question_ids:
            sub_question_ids.append(match.group(1))
    source_match = re.search(r"<source>\s*(.*?)\s*</source>", prompt, re.S)
    source_text = re.sub(r"\s+", " ", source_match.group(1)).strip() if source_match else ""
    if len(source_text) >= 40:
        quote = source_text[:200]
    else:
        quote = "Mock content"
    facts = []
    for sub_id in sub_question_ids[:3]:
        facts.append({
            "subQuestionId": sub_id,
            "quote": quote,
            "aspect": "overview",
        })
    return {"facts": facts}


def claim_response(prompt):
    evidence_ids = []
    for match in re.finditer(r"evidenceId=([0-9A-Z]{26})", prompt):
        if match.group(1) not in evidence_ids:
            evidence_ids.append(match.group(1))
    sub_question_ids = []
    for match in re.finditer(r"subQuestionId=([0-9A-Z]{26})", prompt):
        if match.group(1) not in sub_question_ids:
            sub_question_ids.append(match.group(1))
    if not evidence_ids:
        return {"claims": []}
    section = sub_question_ids[0] if sub_question_ids else "s1"
    claims = [{
        "sectionKey": section,
        "statement": "Durable execution recovers workflow state after process crashes.",
        "evidenceIds": evidence_ids[:2],
        "importance": 4,
    }]
    if len(evidence_ids) > 2:
        claims.append({
            "sectionKey": sub_question_ids[1] if len(sub_question_ids) > 1 else section,
            "statement": "Checkpoint frameworks persist graph state instead of replaying events.",
            "evidenceIds": evidence_ids[2:4],
            "importance": 3,
        })
    return {"claims": claims}


def conflict_response(prompt):
    claim_ids = []
    for match in re.finditer(r"claimId=([0-9A-Za-z_-]{1,64})", prompt):
        if match.group(1) not in claim_ids:
            claim_ids.append(match.group(1))
    if len(claim_ids) < 2:
        return {"conflicts": []}
    return {"conflicts": [{
        "topic": "Mock disagreement on long-running orchestration",
        "summary": "Mock: sources describe different trade-offs; both sides are shown.",
        "positions": [
            {"claimId": claim_ids[0], "stance": "supports", "urls": []},
            {"claimId": claim_ids[1], "stance": "opposes", "urls": []},
        ],
    }]}


def report_response(prompt):
    claim_ids = []
    for match in re.finditer(r"claimId=([0-9A-Za-z_-]{1,64})", prompt):
        if match.group(1) not in claim_ids:
            claim_ids.append(match.group(1))
    lines = ["## Mock summary", "", "Deterministic mock prose for the report body.", ""]
    for claim_id in claim_ids:
        lines.append("- Mock finding backed by the stored evidence. [[" + claim_id + "]]")
    return "\n".join(lines)


def message_text(message):
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        raw = self.read_body()
        try:
            request = json.loads(raw)
        except Exception:
            request = {}
        prompt = "\n".join(
            message_text(m) for m in request.get("messages", [])
            if isinstance(m, dict)
        )
        # The user turn is what carries <source>; the system prompt may mention the tag.
        messages = [m for m in request.get("messages", []) if isinstance(m, dict)]
        user_prompt = message_text(messages[-1]) if messages else prompt

        print(f"[mock-llm] prompt head={prompt[:100]!r}", flush=True)
        if "研究规划器" in prompt:
            content = json.dumps(PLAN, ensure_ascii=False)
        elif "抽取器" in prompt:
            content = json.dumps(extract_response(user_prompt), ensure_ascii=False)
        elif "结论合成器" in prompt:
            content = json.dumps(claim_response(prompt), ensure_ascii=False)
        elif "事实校验器" in prompt:
            content = json.dumps(VERIFY, ensure_ascii=False)
        elif "冲突检测器" in prompt:
            content = json.dumps(conflict_response(prompt), ensure_ascii=False)
        elif "报告撰写者" in prompt:
            content = report_response(prompt)
        else:
            content = json.dumps(PLAN, ensure_ascii=False)

        response = {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": 1700000000,
            "model": request.get("model", "mock-model"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 900, "completion_tokens": 300, "total_tokens": 1200},
        }
        payload = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        print(f"[mock-llm] {self.path} -> {content[:60]}...", flush=True)

    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b"{}"

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    print(f"[mock-llm] listening on http://127.0.0.1:{port}/v1/chat/completions", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
