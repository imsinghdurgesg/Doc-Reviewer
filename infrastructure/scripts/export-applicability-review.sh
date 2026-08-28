#!/usr/bin/env bash
# Exports every extracted applicability condition for human review. Architecture §12.5.
#
# WHY THIS EXISTS
# ---------------
# The corpus is far too small to measure retrieval quality, but the extracted conditions ARE large
# enough to measure applicability quality — and that is the more urgent question, because a wrongly
# classified SCOPE_GATE silently removes a real control from a review.
#
# The metric that matters is the FALSE NOT_APPLICABLE rate, and the target is zero. Precision beats
# recall here: comparing one extra uncertain requirement costs a model call, while excluding an
# applicable control produces a clean-looking report that is wrong.
#
# The reviewer fills in the three trailing columns; everything before them is machine-generated, so a
# diff of predicted vs reviewed role is the measurement.
#
#   reviewer_role       one of SCOPE_GATE, CONDITIONAL_OBLIGATION, IMPLEMENTATION_QUALIFIER,
#                       EXCEPTION_OR_WAIVER, TEMPORAL_OR_SEQUENCE, or UNKNOWN when it cannot be settled
#                       from the text — UNKNOWN routes the row to a policy owner rather than guessing.
#   reviewer_exclusion  AUTHORISE_EXCLUSION to let a confirmed SCOPE_GATE produce NOT_APPLICABLE.
#                       Left blank the requirement is still compared. Separate from reviewer_role on
#                       purpose: confirming what a clause does must not grant control-removing power by
#                       omission, so exclusion is always an explicit second act.
#   reviewer_notes      why. Required for UNKNOWN, where the open question is the whole point.
#
# USAGE
#   infrastructure/scripts/export-applicability-review.sh > applicability-review.csv
set -euo pipefail

cd "$(dirname "$0")/../.."

# Emitted as CSV rather than JSON so it can go straight into a spreadsheet — the reviewers doing this
# are policy people, not engineers.
docker compose exec -T mongodb sh -c 'mongosh --quiet \
  -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin retrieval_comparison --eval "
function csv(v) {
  if (v === null || v === undefined) return \"\";
  var s = String(v).replace(/\r?\n/g, \" \").replace(/\"/g, \"\\\"\\\"\");
  return \"\\\"\" + s + \"\\\"\";
}
print([\"standard_version_id\",\"requirement_set\",\"control_id\",\"requirement_id\",
       \"requirement_text\",\"condition_text\",\"predicted_role\",\"document_types\",
       \"condition_source_spans\",\"can_exclude\",
       \"reviewer_role\",\"reviewer_exclusion\",\"reviewer_notes\"].join(\",\"));

// V3 only: V1 and V2 sets carry no roles, so including them would put un-reviewable rows in a
// review file and dilute the measurement.
db.requirement_set_versions.find({requirementExtractionPromptVersion: \"REQ-EXTRACT-V3\"}).forEach(function(set) {
  db.standard_requirements.find({
    standardVersionId: set.standardVersionId,
    requirementSetVersion: set.requirementSetVersion
  }).forEach(function(r) {
    var conds = ((r.applicability || {}).structuredConditions) || [];
    conds.forEach(function(c) {
      // What the CURRENT extraction would treat as exclusion-capable, pre-computed so a reviewer can
      // sort by it and check the dangerous rows first. Note this is the model's claim, not a live
      // permission: exclusion at runtime additionally requires an authorising override.
      var canExclude = (c.role === \"SCOPE_GATE\") ? \"YES\" : \"no\";
      print([
        csv(r.standardVersionId), csv(r.requirementSetVersion),
        csv(r.sourceControlId || r.parentControlId), csv(r.requirementId),
        csv(r.normalizedRequirement), csv(c.text), csv(c.role),
        csv((c.documentTypes || []).join(\"; \")),
        csv((c.conditionSourceSpanIds || []).join(\"; \")),
        csv(canExclude), \"\", \"\", \"\"
      ].join(\",\"));
    });
  });
});
"' 2>/dev/null | tr -d '\r' | grep -v '^$'
