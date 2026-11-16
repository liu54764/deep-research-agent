#!/usr/bin/env python3
"""Validate eval/dataset/questions.jsonl without third-party dependencies.

Checks: JSON parses, required fields, id uniqueness, enum values, weight range,
exact questions have goldAnswer, contested questions have conflicts, referenceUrls
look like absolute URLs. Schema.json is the source of truth; this script is the
dependency-free gate that can run before `jsonschema` is available.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATASET = ROOT / "eval" / "dataset" / "questions.jsonl"

REQUIRED = ["id", "datasetVersion", "split", "category", "question",
            "language", "depth", "gold", "contested"]
SPLITS = {"regression", "dev", "test"}
LANGUAGES = {"zh", "en"}
DEPTHS = {"QUICK", "STANDARD", "DEEP"}
GOLD_TYPES = {"exact", "keypoints"}
ID_RE = re.compile(r"^q-[a-z0-9-]+$")
KP_RE = re.compile(r"^kp[0-9]+$")
CF_RE = re.compile(r"^cf[0-9]+$")
URL_RE = re.compile(r"^https?://")

errors = []
seen_ids = set()


def check(cond, msg):
    if not cond:
        errors.append(msg)


def validate_line(lineno, raw):
    try:
        q = json.loads(raw)
    except json.JSONDecodeError as e:
        errors.append(f"line {lineno}: invalid JSON: {e}")
        return

    for key in REQUIRED:
        check(key in q, f"line {lineno}: missing field '{key}'")

    qid = q.get("id", "")
    check(bool(ID_RE.match(qid)), f"line {lineno}: bad id '{qid}'")
    check(qid not in seen_ids, f"line {lineno}: duplicate id '{qid}'")
    seen_ids.add(qid)

    check(q.get("split") in SPLITS, f"{qid}: bad split")
    check(q.get("language") in LANGUAGES, f"{qid}: bad language")
    check(q.get("depth") in DEPTHS, f"{qid}: bad depth")
    check(len(q.get("question", "")) >= 8, f"{qid}: question too short")

    gold = q.get("gold") or {}
    gtype = gold.get("type")
    check(gtype in GOLD_TYPES, f"{qid}: bad gold.type")

    # Drafts are allowed, but must be explicit about it.
    check(isinstance(gold.get("verified"), bool), f"{qid}: gold.verified must be bool")
    if gold.get("verified") is False:
        check(gold.get("checkedAt") is None,
              f"{qid}: unverified gold must have checkedAt = null")

    if gtype == "exact":
        check(bool(gold.get("goldAnswer")), f"{qid}: exact gold needs goldAnswer")

    kps = gold.get("keyPoints") or []
    check(1 <= len(kps) <= 8, f"{qid}: keyPoints must be 1..8")
    kp_ids = set()
    for kp in kps:
        kid = kp.get("id", "")
        check(bool(KP_RE.match(kid)), f"{qid}: bad keyPoint id '{kid}'")
        check(kid not in kp_ids, f"{qid}: duplicate keyPoint id '{kid}'")
        kp_ids.add(kid)
        w = kp.get("weight")
        check(isinstance(w, (int, float)) and 0.1 <= w <= 1.0,
              f"{qid}/{kid}: weight must be within [0.1, 1.0]")
        check(bool(kp.get("statement")), f"{qid}/{kid}: empty statement")
        for url in kp.get("referenceUrls") or []:
            check(bool(URL_RE.match(url)), f"{qid}/{kid}: referenceUrl not absolute: {url}")
        if gold.get("verified"):
            check(bool(kp.get("referenceUrls")),
                  f"{qid}/{kid}: verified keyPoint needs referenceUrls")

    if q.get("contested"):
        conflicts = gold.get("conflicts") or []
        check(len(conflicts) >= 1, f"{qid}: contested question needs gold.conflicts")
        cf_ids = set()
        for cf in conflicts:
            cid = cf.get("id", "")
            check(bool(CF_RE.match(cid)), f"{qid}: bad conflict id '{cid}'")
            check(cid not in cf_ids, f"{qid}: duplicate conflict id '{cid}'")
            cf_ids.add(cid)
            check(bool(cf.get("sideA")) and bool(cf.get("sideB")),
                  f"{qid}/{cid}: conflict needs sideA and sideB")


def main():
    if not DATASET.exists():
        print(f"dataset not found: {DATASET}")
        return 1

    lines = [ln for ln in DATASET.read_text(encoding="utf-8").splitlines() if ln.strip()]
    for i, raw in enumerate(lines, start=1):
        validate_line(i, raw)

    if errors:
        print(f"FAILED: {len(errors)} problem(s) in {len(lines)} question(s)")
        for e in errors:
            print(f"  - {e}")
        return 1

    verified = sum(1 for ln in lines if json.loads(ln)["gold"].get("verified"))
    print(f"OK: {len(lines)} question(s), {verified} verified, "
          f"{len(lines) - verified} unverified (diagnostic only)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
