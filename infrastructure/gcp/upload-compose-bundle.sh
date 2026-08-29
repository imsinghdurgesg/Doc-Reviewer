#!/usr/bin/env bash
#
# Uploads the compose bundle for an already-pushed release. Architecture §27.
#
#   GCS_BUCKET=<bucket> TAG=v1.0.0-pilot.3 ./infrastructure/gcp/upload-compose-bundle.sh
#
# WHY THIS IS SEPARATE FROM deploy.sh
# -----------------------------------
# deploy.sh uploads the bundle as its last step, but SKIPS it when GCS_BUCKET is unset — logging one
# line and exiting 0. That is a successful-looking run that leaves the VM unable to start: the bundle
# carries image-digests.env, and compose.gcp.yaml refuses to start without a digest per service.
#
# Re-running deploy.sh just to reach that step would rebuild and re-push four images for nothing. This
# does only the upload, against digests that already exist.
#
# It also REFUSES rather than uploading something incomplete. A bundle missing a digest fails at VM
# startup, at which point the stack is down and the cause is a line in a boot log.
set -euo pipefail

PROJECT="${PROJECT:-uit-uit-et-servicenow-ai-dev}"
TAG="${TAG:?set TAG to the release tag, e.g. TAG=v1.0.0-pilot.3}"
BUCKET="${GCS_BUCKET:?set GCS_BUCKET, e.g. GCS_BUCKET=stanford-docreview-standards}"
MANIFEST="releases/$TAG/image-digests.env"

log() { echo "[bundle] $*"; }

if [ ! -f "$MANIFEST" ]; then
  log "FATAL: $MANIFEST does not exist."
  log "  The digests are written by deploy.sh AFTER a successful push. Run it first."
  exit 1
fi

# Every service the overlay pins must be present. Checked here rather than trusting the manifest,
# because a partial push leaves a partial manifest and the failure would otherwise surface as a
# stopped stack on the VM.
missing=0
for var in DOCREVIEW_REVIEW_CORE_DIGEST DOCREVIEW_DOCUMENT_PROCESSING_DIGEST \
           DOCREVIEW_RETRIEVAL_COMPARISON_DIGEST DOCREVIEW_REVIEW_WEB_DIGEST; do
  if ! grep -qE "^${var}=sha256:[0-9a-f]{64}$" "$MANIFEST"; then
    log "FATAL: $var absent or malformed in $MANIFEST"
    missing=1
  fi
done
[ "$missing" -eq 0 ] || exit 1

log "manifest complete: 4 digests pinned for $TAG"

BUNDLE=$(mktemp -t docreview-compose-XXXX).tgz
trap 'rm -f "$BUNDLE"' EXIT
tar czf "$BUNDLE" compose.yaml compose.gcp.yaml infrastructure/gcp/Caddyfile "$MANIFEST"

# Verified BEFORE upload. startup.sh looks for releases/$IMAGE_TAG/image-digests.env at exactly this
# path, so a bundle built from the wrong directory would extract somewhere the VM does not look and
# fail the same way an absent bundle does.
if ! tar tzf "$BUNDLE" | grep -qx "$MANIFEST"; then
  log "FATAL: the bundle does not contain $MANIFEST at the path startup.sh reads."
  exit 1
fi

log "uploading to gs://$BUCKET/deploy/docreview-compose.tgz"
gcloud storage cp "$BUNDLE" "gs://$BUCKET/deploy/docreview-compose.tgz" --project="$PROJECT"

log "done. To roll the VM onto $TAG:"
log "  gcloud compute instances add-metadata docreview-vm --zone=us-central1-a \\"
log "    --metadata image-tag=$TAG --project=$PROJECT"
log "  gcloud compute ssh docreview-vm --zone=us-central1-a --tunnel-through-iap \\"
log "    --command 'sudo google_metadata_script_runner startup'"
