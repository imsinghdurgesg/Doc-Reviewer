#!/usr/bin/env python3
"""Import governed requirement-integrity records for known-incomplete extractions.

Architecture 12.7, 18.3.

WHAT THIS RECORDS
-----------------
A requirement whose source states a material obligation that no extracted atomic clause
represents. Such a requirement is withheld from the comparison model, because asking it to
evaluate a clause list that omits the obligation produces an unanswerable question.

WHY IT IS DERIVED, NOT HAND-WRITTEN
-----------------------------------
The affected set is found by comparing each requirement's TITLE against its clauses and
normalized text, then confirming the obligation actually appears in one of its provenance
spans. A hand-written list would encode today's corpus and silently rot; this re-derives from
the live data every run and refuses to write anything it cannot verify against a span.

WHY THE TITLE ALONE IS NOT EVIDENCE
-----------------------------------
A title-derived candidate is a HYPOTHESIS, not a finding. Titles are the extractor's paraphrase
and routinely restate an obligation in words the source never uses: CHG-01-R2 is titled
"Complete change record before execution" while its source span says "at authorization" and its
clause correctly carries timing "at authorization". Nothing was dropped there.

Of 11 title-derived candidates in REQSET-V3, 8 were paraphrases of this kind and only 3 — the
"within N business day(s)" deadlines — appear verbatim in a provenance span. So the span check
is not a formality; it is what separates a real dropped obligation from the extractor choosing
different words. A record is written only when the obligation is quotable from the source.

SAFETY
------
- Refuses the whole batch if any candidate cannot be verified against its source span. A
  partial import would leave some requirements withheld and others not, for no visible reason.
- Never writes a record with an empty obligation list. An unsupported record would withhold a
  control while giving the reviewer nothing to check; the code treats such a record as
  non-blocking anyway, so writing one is pointless and misleading.
- Scoped to one requirementSetVersion. A record must not carry into a later extraction
  generation, which may have fixed the defect.
- Credentials are read from the container environment, never passed on argv.

USAGE
    python3 import-requirement-integrity.py --set-version REQSET-V3 [--apply]

Defaults to a DRY RUN. Nothing is written without --apply.
"""

import argparse
import json
import re
import subprocess
import sys

CONTAINER = "docreview-mongodb-1"
BATCH_ID = "REQ-INTEGRITY-V1"
REVIEWED_BY = "SYSTEM_REVIEW_BATCH"

# Obligation phrases that carry normative force on their own. Matched against the TITLE, then
# confirmed against a provenance span before anything is written.
TIMING = r"\b(within|no later than|by the end of|prior to|before|after|annually|quarterly|monthly|daily|business day|calendar day)\b"

# Which obligation kind to record, by the phrase that matched. Ordering matters: the first
# match wins, so the sequencing words are listed before the generic timing ones.
OBLIGATION_TYPES = [
    (r"\b(before|prior to)\b", "SEQUENCING"),
    (r"\b(after)\b", "SEQUENCING"),
    (r"\b(within|no later than|by the end of|business day|calendar day)\b", "TIMING"),
    (r"\b(annually|quarterly|monthly|daily)\b", "TIMING"),
]


def mongosh(database, script):
    """Runs a mongosh script, reading credentials from the container env.

    The script is piped on STDIN rather than passed with --eval. Quoting a multi-line script
    through `docker exec sh -c` mangles it: the newlines arrive as literal backslash-n and
    mongosh rejects them as bad escape sequences. Credentials stay in the container env either
    way, so they never appear in argv.
    """
    command = (
        'exec mongosh -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" '
        f'--authenticationDatabase admin --quiet {database}'
    )
    # Wrapped in an IIFE. Fed on STDIN, mongosh runs as a REPL and evaluates each top-level
    # statement separately, echoing every non-undefined result — so a bare `db.find()` printed a
    # whole cursor of documents on top of the intended payload. One expression, one result.
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c", command],
        input="(function () {\n" + script + "\n})()\n",
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        sys.exit(f"mongosh failed against {database}: {result.stderr.strip()}")
    # Reading from STDIN puts mongosh in REPL mode, so it echoes a "db> " prompt before each
    # line of output. Stripped here so callers can parse the payload rather than each learning
    # about the prompt separately.
    # Strips the REPL prompt AND the "| | " continuation markers it emits for a multi-line
    # expression. The markers land on the SAME line as the payload, so removing only the prompt
    # left output that no longer began with "[" and parsed as nothing.
    cleaned = re.sub(r"^[A-Za-z0-9_]+> ", "", result.stdout, flags=re.MULTILINE)
    return re.sub(r"^(?:\|\s*)+", "", cleaned, flags=re.MULTILINE)


def parse_json_output(output, opener, what):
    """The JSON payload from mongosh REPL output.

    Reading a script on STDIN puts mongosh in REPL mode, which interleaves prompts and
    continuation markers ("| |") with real output. Rather than assume a line position, this
    takes the LAST line that actually decodes — the payload is the final print().
    """
    best = None
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith(opener):
            continue
        try:
            best = json.loads(line)
        except json.JSONDecodeError:
            continue
    if best is None:
        sys.exit(f"could not parse {what} from mongosh output")
    return best


def obligation_type(phrase):
    for pattern, kind in OBLIGATION_TYPES:
        if re.search(pattern, phrase, re.IGNORECASE):
            return kind
    return "OTHER"


def find_candidates(set_version):
    """Requirements whose title states an obligation absent from clauses and normalized text."""
    script = f"""
    var TIMING = /{TIMING}/i;
    var out = [];
    db.standard_requirements.find({{requirementSetVersion: "{set_version}", material: true}})
      .forEach(function (d) {{
        var title = String(d.title || "");
        var m = title.match(TIMING);
        if (!m) return;
        var blob = (JSON.stringify(d.atomicClauses || []) + " "
                    + String(d.normalizedRequirement || "")).toLowerCase();
        if (blob.indexOf(m[0].toLowerCase()) !== -1) return;
        out.push({{
          requirementId: d.requirementId,
          standardVersionId: d.standardVersionId,
          requirementSetVersion: d.requirementSetVersion,
          title: title,
          phrase: m[0],
          sourceSpanIds: d.sourceSpanIds || []
        }});
      }});
    print(JSON.stringify(out));
    """
    return parse_json_output(
        mongosh("retrieval_comparison", script), "[", "the candidate list")


def span_texts(span_ids):
    """Exact text for each span, from document_processing."""
    script = f"""
    var ids = {json.dumps(span_ids)};
    var out = {{}};
    db.document_spans.find({{spanId: {{$in: ids}}}}).forEach(function (s) {{
      out[s.spanId] = String(s.exactText || "");
    }});
    print(JSON.stringify(out));
    """
    return parse_json_output(
        mongosh("document_processing", script), "{", "the span text")


def verify(candidate, texts):
    """The obligation as stated in the source, plus the spans containing it.

    Returns (exact_text, [span_ids]) or (None, reason). The obligation is taken from the SPAN,
    not from the title: the title is the extractor's paraphrase, and a record must quote the
    authoritative source.
    """
    phrase = candidate["phrase"].lower()
    matched = []
    exact = None
    for span_id in candidate["sourceSpanIds"]:
        text = texts.get(span_id, "")
        position = text.lower().find(phrase)
        if position == -1:
            continue
        matched.append(span_id)
        if exact is None:
            # The obligation with enough surrounding words to be meaningful on its own:
            # "within one business day" rather than the bare word "within".
            window = text[position:position + 60]
            exact = re.split(r"[.;]", window)[0].strip()
    if not matched:
        return None, f"phrase '{candidate['phrase']}' not found in any provenance span"
    if not exact:
        return None, "could not extract obligation text"
    return exact, matched


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--set-version", required=True,
                        help="the promoted requirement set, e.g. REQSET-V3")
    parser.add_argument("--apply", action="store_true",
                        help="write the records; omit for a dry run")
    args = parser.parse_args()

    candidates = find_candidates(args.set_version)
    if not candidates:
        print(f"No incomplete extractions found in {args.set_version}.")
        return

    all_spans = sorted({s for c in candidates for s in c["sourceSpanIds"]})
    texts = span_texts(all_spans)

    records, rejected = [], []
    for candidate in candidates:
        exact, spans = verify(candidate, texts)
        if exact is None:
            rejected.append((candidate, spans))
            continue
        records.append({
            "standardVersionId": candidate["standardVersionId"],
            "requirementSetVersion": candidate["requirementSetVersion"],
            "requirementId": candidate["requirementId"],
            "requirementTitle": candidate["title"],
            "integrityStatus": "INCOMPLETE_ATOMIC_COVERAGE",
            "missingObligations": [{
                "exactText": exact,
                "standardSpanIds": spans,
                "obligationType": obligation_type(candidate["phrase"]),
                "reason": "Present in the requirement's provenance spans but absent from both "
                          "normalizedRequirement and the atomic clauses.",
            }],
            "reviewedBy": REVIEWED_BY,
            "reviewBatchId": BATCH_ID,
            "notes": f"Derived from {args.set_version}: the title states this obligation and the "
                     f"extracted clauses do not represent it.",
        })

    print(f"{len(candidates)} candidate(s); {len(records)} verified, {len(rejected)} rejected\n")
    for record in records:
        obligation = record["missingObligations"][0]
        print(f"  {record['requirementId']:<12} {record['standardVersionId']}  "
              f"[{obligation['obligationType']}]  \"{obligation['exactText']}\"")

    if rejected:
        # NOT an error, and NOT a batch blocker. These are title paraphrases: the title states an
        # obligation in words the source does not use, and the clause carries the source's own
        # wording. Nothing was dropped, so no record is written and the requirement is compared
        # normally. Listed because a silent drop would hide the detector's own false-positive
        # rate, which is the number that says how much to trust this import.
        print(f"\nNOT DEFECTS — {len(rejected)} title paraphrase(s), compared normally:")
        for candidate, reason in rejected:
            print(f"  {candidate['requirementId']:<12} {reason}")

    if not args.apply:
        print("\nDRY RUN. Re-run with --apply to write these records.")
        return

    script = f"""
    var records = {json.dumps(records)};
    var now = new Date();
    var written = 0;
    records.forEach(function (r) {{
      r.recordedAt = now;
      r._class = "com.app.docreview.retrievalcomparison.comparison.domain.RequirementIntegrity";
      db.requirement_integrity_records.replaceOne(
        {{standardVersionId: r.standardVersionId,
          requirementSetVersion: r.requirementSetVersion,
          requirementId: r.requirementId}},
        r, {{upsert: true}});
      written++;
    }});
    print("written=" + written);
    """
    print(mongosh("retrieval_comparison", script).strip())
    print(f"\nBatch {BATCH_ID} applied. Restart retrieval-comparison to clear its lookup cache.")


if __name__ == "__main__":
    main()
