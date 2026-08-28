#!/usr/bin/env python3
"""Imports a reviewed condition-role CSV into requirement_condition_overrides.

WHY A SCRIPT RATHER THAN A SERVICE ENDPOINT
-------------------------------------------
A review batch is imported once, by an operator, from a hashed artifact. An always-available endpoint
would invite the CSV to become the operational lookup — which is exactly the arrangement being avoided:
MongoDB is the runtime source of truth, and the CSV is the import and audit artifact behind it.

WHAT THIS DOES NOT DO
---------------------
It does not re-judge the review. Status comes from what the reviewer recorded, not from phrase matching
in this file: a heuristic here would quietly overrule a human decision, which is the failure mode the
override model exists to prevent.

  reviewer_role = a specific role  -> CONFIRMED with that role
  reviewer_role = UNKNOWN          -> NEEDS_POLICY_OWNER, role UNKNOWN, cannot exclude
  reviewer_role = blank            -> no row; the model's role stands, unreviewed and unable to exclude

EXCLUSION IS A SEPARATE, EXPLICIT DECISION
------------------------------------------
`canDriveNotApplicable` is true only when the reviewer wrote AUTHORISE_EXCLUSION in `reviewer_exclusion`
AND the row is a CONFIRMED SCOPE_GATE. Confirming what a clause does is not the same as authorising it to
remove a control from a review, so confirming a role never grants exclusion by omission. Absent that
column the import authorises nothing — a scope gate that is merely correct still gets compared, which
costs a redundant comparison rather than a silently missing control.

USAGE
  infrastructure/scripts/import-condition-overrides.py <csv> --batch CONDROLE-REVIEW-V1 [--apply]
  Without --apply it prints the batch as JSON and a summary; nothing is written.
"""
import argparse
import csv
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter

VALID_ROLES = {
    "SCOPE_GATE", "CONDITIONAL_OBLIGATION", "IMPLEMENTATION_QUALIFIER",
    "EXCEPTION_OR_WAIVER", "TEMPORAL_OR_SEQUENCE", "UNKNOWN",
}

# The reviewer's opt-in token for exclusion authority. Spelled out rather than a truthy "yes" so that a
# stray "y" or "1" in a spreadsheet cell cannot grant a control-removing power by accident.
AUTHORISE = "AUTHORISE_EXCLUSION"


def build(csv_path, batch, reviewer):
    raw = open(csv_path, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    rows = list(csv.DictReader(open(csv_path, newline="")))

    out, problems = [], []
    stats = Counter()

    for i, r in enumerate(rows, start=2):  # +2: header is line 1
        reviewed = (r.get("reviewer_role") or "").strip().upper()
        if not reviewed:
            stats["skipped_unreviewed"] += 1
            continue
        if reviewed not in VALID_ROLES:
            problems.append(f"line {i}: unrecognised reviewer_role {reviewed!r}")
            continue

        notes = (r.get("reviewer_notes") or "").strip()

        if reviewed == "UNKNOWN":
            # The reviewer looked and could not settle it. That is information — the question is open —
            # and forcing it into a definite role would discard exactly that.
            status, role = "NEEDS_POLICY_OWNER", "UNKNOWN"
            stats["needs_policy_owner"] += 1
            if not notes:
                problems.append(f"line {i}: UNKNOWN with no reviewer_notes; the open question is unstated")
        else:
            status, role = "CONFIRMED", reviewed
            stats["confirmed"] += 1
            stats[f"confirmed_{role}"] += 1

        authorised = (r.get("reviewer_exclusion") or "").strip().upper() == AUTHORISE
        can_exclude = authorised and status == "CONFIRMED" and role == "SCOPE_GATE"
        if authorised and not can_exclude:
            problems.append(
                f"line {i}: {AUTHORISE} on a {status}/{role} row; exclusion needs a CONFIRMED SCOPE_GATE")
        if can_exclude:
            stats["authorised_to_exclude"] += 1

        out.append({
            "standardVersionId": r["standard_version_id"].strip(),
            "requirementSetVersion": r["requirement_set"].strip(),
            "requirementId": r["requirement_id"].strip(),
            "conditionText": (r.get("condition_text") or "").strip(),
            "extractedRole": (r.get("predicted_role") or "UNKNOWN").strip(),
            "extractionVersion": "REQ-EXTRACT-V3",
            "reviewedRole": role,
            "reviewStatus": status,
            "canDriveNotApplicable": can_exclude,
            "reviewerNotes": notes,
            "reviewedBy": reviewer,
            "reviewBatchId": batch,
            "sourceFileName": os.path.basename(csv_path),
            "sourceSha256": sha,
            "_class": "com.app.docreview.retrievalcomparison.comparison.domain."
                      "RequirementConditionOverride",
        })

    # A CONFIRMED SCOPE_GATE that nothing authorised still cannot exclude. Worth stating rather than
    # leaving the operator to infer it from a zero.
    unauthorised_gates = sum(
        1 for o in out
        if o["reviewStatus"] == "CONFIRMED" and o["reviewedRole"] == "SCOPE_GATE"
        and not o["canDriveNotApplicable"])
    if unauthorised_gates:
        stats["confirmed_gates_not_authorised"] = unauthorised_gates

    return {"batch": batch, "sourceFileName": os.path.basename(csv_path), "sourceSha256": sha,
            "rowsRead": len(rows), "stats": dict(stats), "problems": problems, "overrides": out}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv_path")
    ap.add_argument("--batch", required=True, help="review batch id, e.g. CONDROLE-REVIEW-V1")
    ap.add_argument("--reviewer", default="imsingh@stanford.edu")
    ap.add_argument("--apply", action="store_true",
                    help="write to MongoDB via the retrieval_comparison container; otherwise dry-run")
    ap.add_argument("--mongo-container", default="docreview-mongodb-1")
    ap.add_argument("--mongo-db", default="retrieval_comparison")
    args = ap.parse_args()

    batch = build(args.csv_path, args.batch, args.reviewer)

    print(f"source     {batch['sourceFileName']}", file=sys.stderr)
    print(f"sha256     {batch['sourceSha256']}", file=sys.stderr)
    print(f"rows read  {batch['rowsRead']}", file=sys.stderr)
    print(f"overrides  {len(batch['overrides'])}", file=sys.stderr)
    for k, v in sorted(batch["stats"].items()):
        print(f"   {k:38s} {v}", file=sys.stderr)
    for p in batch["problems"]:
        print(f"   PROBLEM: {p}", file=sys.stderr)

    if batch["problems"]:
        # Refusing the whole batch keeps the import atomic in intent: a partially-applied review is far
        # harder to reason about later than one that was rejected outright.
        print("\nrefusing to apply: fix the CSV and re-run", file=sys.stderr)
        return 1

    if not args.apply:
        print(json.dumps(batch["overrides"], indent=2))
        print("\ndry run — re-run with --apply to write", file=sys.stderr)
        return 0

    # Idempotent by batch: dropping this batch's rows first means a re-run replaces rather than
    # duplicates, and the unique index on {identity, batch} would reject the duplicate anyway.
    script = (
        f'db.requirement_condition_overrides.deleteMany({{reviewBatchId: {json.dumps(args.batch)}}});\n'
        f'var docs = {json.dumps(batch["overrides"])};\n'
        'docs.forEach(function (d) { d.reviewedAt = new Date(); });\n'
        'var r = db.requirement_condition_overrides.insertMany(docs);\n'
        'print("inserted=" + Object.keys(r.insertedIds).length);\n'
        'print("authorised_to_exclude=" + db.requirement_condition_overrides'
        f'.countDocuments({{reviewBatchId: {json.dumps(args.batch)}, canDriveNotApplicable: true}}));\n'
        'print("needs_policy_owner=" + db.requirement_condition_overrides'
        f'.countDocuments({{reviewBatchId: {json.dumps(args.batch)}, '
        'reviewStatus: "NEEDS_POLICY_OWNER"}));\n'
    )
    # Credentials are read from the container's own environment rather than passed on our command line,
    # so they never appear in this process's argv or in shell history.
    # No trailing "-": mongosh reads piped stdin on its own and rejects "-" as an option.
    wrapper = (
        'exec mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" '
        f'--authenticationDatabase admin {args.mongo_db}')
    proc = subprocess.run(
        ["docker", "exec", "-i", args.mongo_container, "sh", "-c", wrapper],
        input=script, text=True, capture_output=True)
    sys.stderr.write(proc.stdout)
    sys.stderr.write(proc.stderr)
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
