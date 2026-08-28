#!/usr/bin/env bash
#
# Builds the four service images and pushes them to Artifact Registry. Architecture §27.
#
# Run from the repository root:  ./infrastructure/gcp/deploy.sh
#
# Images are built HERE rather than on the VM. Building on an e2-standard-4 takes ~7 minutes per
# service and competes with the running stack for CPU; pushing a tag means a redeploy is a pull.
set -euo pipefail

PROJECT="${PROJECT:-uit-uit-et-servicenow-ai-dev}"
REGION="${REGION:-us-central1}"
REPO="${REGION}-docker.pkg.dev/${PROJECT}/docreview"
# Immutable by default. A moving `latest` makes "which build is running" unanswerable after the
# fact, which matters for a tool whose results have to be explainable weeks later (§19.2).
TAG="${TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d-%H%M%S)}"

log() { echo "[deploy] $*"; }

log "authenticating docker to $REGION-docker.pkg.dev"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

# The Maven reactor must build first: each service Dockerfile compiles from the repository root, and
# building all four in parallel would run four full reactor builds.
log "building the reactor"
./mvnw -q -DskipTests package

# linux/amd64 explicitly. A build on an Apple-silicon machine otherwise produces arm64 images that
# the VM cannot run, and the failure is an exec-format error rather than anything about architecture.
PLATFORM=linux/amd64

for svc in review-core document-processing retrieval-comparison; do
  log "building $svc"
  docker build --platform "$PLATFORM" \
    -f "services/$svc/Dockerfile" \
    -t "$REPO/$svc:$TAG" -t "$REPO/$svc:latest" .
done

log "building review-web"
docker build --platform "$PLATFORM" \
  -f ui/review-web/Dockerfile \
  -t "$REPO/review-web:$TAG" -t "$REPO/review-web:latest" ui/review-web

# Retried, and a permanent failure is FATAL.
#
# `docker push` fails on a dropped connection partway through a layer, and these images are 200-400MB
# over whatever link the operator has. Without the retry a transient drop leaves the registry holding
# some images and not others; without the `exit` the script printed its success banner anyway, and the
# VM then booted and pulled a tag that only existed for one of four services.
for svc in review-core document-processing retrieval-comparison review-web; do
  pushed=false
  for attempt in 1 2 3 4; do
    log "pushing $svc:$TAG (attempt $attempt)"
    if docker push --quiet "$REPO/$svc:$TAG" && docker push --quiet "$REPO/$svc:latest"; then
      pushed=true
      break
    fi
    log "  push failed; retrying in 20s"
    sleep 20
  done
  if [ "$pushed" != true ]; then
    log "FATAL: could not push $svc after 4 attempts. The registry is now INCOMPLETE —"
    log "  do not boot the VM against tag $TAG until every service is present."
    exit 1
  fi
done

# The topology travels with the images. Uploaded here rather than baked into an image so a compose
# change does not require a rebuild — and so the VM has a source that does not depend on the working
# tree being a git repository, which it is not.
if [ -n "${GCS_BUCKET:-}" ]; then
  log "uploading the compose bundle to gs://$GCS_BUCKET/deploy/"
  BUNDLE=$(mktemp -t docreview-compose-XXXX).tgz
  tar czf "$BUNDLE" compose.yaml compose.gcp.yaml infrastructure/gcp/Caddyfile
  gcloud storage cp "$BUNDLE" "gs://$GCS_BUCKET/deploy/docreview-compose.tgz" --project="$PROJECT"
  rm -f "$BUNDLE"
else
  log "GCS_BUCKET unset; skipping the compose bundle upload."
  log "  The VM reads it from gs://<bucket>/deploy/docreview-compose.tgz at boot."
fi

log "pushed tag $TAG"
log "to roll the VM onto it:"
log "  gcloud compute instances add-metadata docreview-vm --zone=${REGION}-a \\"
log "    --metadata image-tag=$TAG --project=$PROJECT"
log "  gcloud compute ssh docreview-vm --zone=${REGION}-a --project=$PROJECT \\"
log "    --command 'sudo google_metadata_script_runner startup'"
