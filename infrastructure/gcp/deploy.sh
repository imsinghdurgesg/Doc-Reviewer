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
# The instance this release targets. Named here rather than inline so the bucket lookup, the boot
# script push, and the printed roll-forward commands cannot disagree about which VM they mean.
VM_NAME="${VM_NAME:-docreview-vm}"
REPO="${REGION}-docker.pkg.dev/${PROJECT}/docreview"
# Immutable by default. A moving `latest` makes "which build is running" unanswerable after the
# fact, which matters for a tool whose results have to be explainable weeks later (§19.2).
TAG="${TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d-%H%M%S)}"

# Which services to rebuild. Default is all four.
#
# A one-file UI change still cost a full cycle: reactor build, four images, four pushes. So ONLY=
# narrows it, and the digests for everything else are inherited from a previous release rather than
# omitted — compose.gcp.yaml requires one per service and refuses to start without it, so a partial
# manifest is not a smaller release, it is an undeployable one.
#
#   ONLY=review-web TAG=v1.0.0-pilot.6 ./infrastructure/gcp/deploy.sh
#   ONLY="review-core review-web" ...
#
# INHERIT_FROM names the release to copy the untouched digests from. Defaults to whatever the VM is
# currently running, which is the only safe default: inheriting from an arbitrary older release would
# roll those services BACKWARDS while appearing to deploy forwards.
ONLY="${ONLY:-}"
ALL_SERVICES="review-core document-processing retrieval-comparison review-web"
INHERIT_FROM="${INHERIT_FROM:-}"

log() { echo "[deploy] $*"; }

# Whether $1 is being rebuilt this run.
building() {
  [ -z "$ONLY" ] && return 0
  for s in $ONLY; do [ "$s" = "$1" ] && return 0; done
  return 1
}

if [ -n "$ONLY" ]; then
  # Validated against the real list: a typo would otherwise build nothing, inherit every digest, and
  # report a successful release that changed not one thing.
  for s in $ONLY; do
    case " $ALL_SERVICES " in
      *" $s "*) ;;
      *) log "FATAL: ONLY names '$s', which is not one of: $ALL_SERVICES"; exit 1 ;;
    esac
  done
  if [ -z "$INHERIT_FROM" ]; then
    INHERIT_FROM=$(gcloud compute instances describe "$VM_NAME" --zone="${REGION}-a" \
      --project="$PROJECT" \
      --format='value(metadata.items.filter("key:image-tag").extract("value"))' 2>/dev/null \
      | tr -d "[]'" || true)
  fi
  if [ -z "$INHERIT_FROM" ] || [ ! -f "releases/$INHERIT_FROM/image-digests.env" ]; then
    log "FATAL: ONLY=$ONLY needs digests for the services it is NOT building, and"
    log "  releases/${INHERIT_FROM:-<unset>}/image-digests.env does not exist."
    log "  Set INHERIT_FROM to a release whose manifest is present, or run a full deploy."
    exit 1
  fi
  log "building only: $ONLY"
  log "inheriting other digests from $INHERIT_FROM"
fi

log "authenticating docker to $REGION-docker.pkg.dev"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

# The Maven reactor must build first: each service Dockerfile compiles from the repository root, and
# building all four in parallel would run four full reactor builds.
#
# Retried on a TRANSIENT download failure only. A dropped connection to Maven Central here fails the
# release before any image is built; twice in one day a release was lost to
# "failed to respond" and "Premature end of Content-Length" on unrelated artifacts. The script
# classifies the failure, so a compile error still fails immediately rather than being repeated.
# Skipped when no Java service is being rebuilt: review-web is a webpack build that never reads the
# reactor output, so running it would add minutes to a UI-only release for nothing.
if building review-core || building document-processing || building retrieval-comparison; then
  log "building the reactor"
  ./infrastructure/build/mvn-package-retry.sh . -q -DskipTests
else
  log "no Java service in this build; skipping the reactor"
fi

# linux/amd64 explicitly. A build on an Apple-silicon machine otherwise produces arm64 images that
# the VM cannot run, and the failure is an exec-format error rather than anything about architecture.
PLATFORM=linux/amd64

for svc in review-core document-processing retrieval-comparison; do
  building "$svc" || { log "skipping $svc"; continue; }
  log "building $svc"
  docker build --platform "$PLATFORM" \
    -f "services/$svc/Dockerfile" \
    -t "$REPO/$svc:$TAG" .
done

if building review-web; then
  log "building review-web"
  docker build --platform "$PLATFORM" \
    -f ui/review-web/Dockerfile \
    -t "$REPO/review-web:$TAG" ui/review-web
else
  log "skipping review-web"
fi

# Retried, and a permanent failure is FATAL.
#
# `docker push` fails on a dropped connection partway through a layer, and these images are 200-400MB
# over whatever link the operator has. Without the retry a transient drop leaves the registry holding
# some images and not others; without the `exit` the script printed its success banner anyway, and the
# VM then booted and pulled a tag that only existed for one of four services.
for svc in review-core document-processing retrieval-comparison review-web; do
  building "$svc" || continue
  pushed=false
  for attempt in 1 2 3 4; do
    log "pushing $svc:$TAG (attempt $attempt)"
    # NO `latest` PUSH. The overlay pins by digest, and a moving `latest` alongside it is a second,
    # mutable way to name the same image — which is what digest pinning exists to remove. A build tag
    # plus the digest manifest is enough to identify and redeploy any release.
    if docker push --quiet "$REPO/$svc:$TAG"; then
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

# DIGESTS, CAPTURED AFTER THE PUSH.
#
# A digest only exists once the registry has accepted the manifest, so this cannot be read from a
# local build. Recorded to a release manifest because the compose overlay REQUIRES each one — an
# unset variable now stops the deploy rather than silently pulling `latest`.
#
# RELEASE_DIR defaults to the tag, so every build leaves a manifest that names exactly what it pushed.
RELEASE_DIR="${RELEASE_DIR:-releases/$TAG}"
mkdir -p "$RELEASE_DIR"
MANIFEST="$RELEASE_DIR/image-digests.env"
{
  echo "# ${TAG} — generated by infrastructure/gcp/deploy.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# Source before deploying:  set -a; . $MANIFEST; set +a"
  echo "DOCREVIEW_REPO=$REPO"
  echo "DOCREVIEW_IMAGE_TAG=$TAG"
} > "$MANIFEST"

log "resolving pushed digests"
for svc in review-core document-processing retrieval-comparison review-web; do
  var="DOCREVIEW_$(echo "$svc" | tr 'a-z-' 'A-Z_')_DIGEST"

  if building "$svc"; then
    # From the REGISTRY, not from the local image. `docker inspect` reports RepoDigests only for images
    # it has pulled or pushed, and a stale local entry from a previous push would name the wrong bytes.
    digest=$(gcloud artifacts docker images describe "$REPO/$svc:$TAG" \
      --project="$PROJECT" --format='value(image_summary.digest)' 2>/dev/null || true)
    if [ -z "$digest" ]; then
      log "FATAL: no digest for $svc:$TAG. The overlay pins by digest, so the deploy cannot proceed"
      log "  without it — a missing digest must fail here rather than at boot on the VM."
      exit 1
    fi
    echo "$var=$digest" >> "$MANIFEST"
    log "  $svc  $digest"
  else
    # INHERITED, not omitted. compose.gcp.yaml marks every digest required, so leaving one out does not
    # produce a smaller release — it produces one the VM refuses to start, with an error naming compose
    # rather than this decision.
    digest=$(grep -E "^${var}=sha256:[0-9a-f]{64}$" "releases/$INHERIT_FROM/image-digests.env" \
      | cut -d= -f2 || true)
    if [ -z "$digest" ]; then
      log "FATAL: $svc was not built and releases/$INHERIT_FROM/image-digests.env has no $var."
      log "  Nothing can supply that digest, so the release would not start. Run a full deploy."
      exit 1
    fi
    echo "$var=$digest" >> "$MANIFEST"
    log "  $svc  $digest  (inherited from $INHERIT_FROM)"
  fi
done

# The topology travels with the images. Uploaded here rather than baked into an image so a compose
# change does not require a rebuild. The digest manifest goes with it, because the overlay refuses to
# start without it.
# GCS_BUCKET falls back to the VM's OWN metadata rather than being required here.
#
# It used to be read only from the environment, and an unset value skipped the upload with one log
# line and exit 0 — a successful-looking release the VM could not start, because compose.gcp.yaml
# requires a digest per service and the digests travel in this bundle. The bucket is not really an
# operator's choice; it is a property of the deployment, so it is read from the deployment.
if [ -z "${GCS_BUCKET:-}" ]; then
  GCS_BUCKET=$(gcloud compute instances describe "$VM_NAME" --zone="${REGION}-a" \
    --project="$PROJECT" \
    --format='value(metadata.items.filter("key:gcs-bucket").extract("value"))' 2>/dev/null \
    | tr -d "[]'" || true)
  [ -n "$GCS_BUCKET" ] && log "GCS_BUCKET unset; using the VM's own gcs-bucket metadata: $GCS_BUCKET"
fi

if [ -z "$GCS_BUCKET" ]; then
  log "FATAL: no GCS bucket. The compose bundle carries $MANIFEST, and the VM cannot start"
  log "  without it. Set GCS_BUCKET, or set 'gcs-bucket' in the instance metadata."
  exit 1
fi

log "uploading the compose bundle to gs://$GCS_BUCKET/deploy/"
BUNDLE=$(mktemp -t docreview-compose-XXXX).tgz
tar czf "$BUNDLE" compose.yaml compose.gcp.yaml infrastructure/gcp/Caddyfile "$MANIFEST"
gcloud storage cp "$BUNDLE" "gs://$GCS_BUCKET/deploy/docreview-compose.tgz" --project="$PROJECT"
rm -f "$BUNDLE"

# THE BOOT SCRIPT TRAVELS WITH THE RELEASE.
#
# startup.sh lives in VM instance metadata, not on the VM's disk, and nothing used to push it. So the
# repository copy and the script that actually runs drifted silently — and a release that depended on
# a newer startup.sh failed at boot with every service unable to resolve its image, because the VM was
# running a version from before digest pinning existed. A script in the repository is not the script
# that runs unless something puts it there.
#
# Pushed on every deploy, so the two cannot diverge again.
log "updating the VM boot script from infrastructure/gcp/startup.sh"
gcloud compute instances add-metadata "$VM_NAME" --zone="${REGION}-a" --project="$PROJECT" \
  --metadata-from-file startup-script=infrastructure/gcp/startup.sh >/dev/null

# Verified by reading it back. add-metadata reports success on a value that was truncated or
# rewritten, and the failure would surface as a boot error naming compose rather than the upload.
pushed_size=$(gcloud compute instances describe "$VM_NAME" --zone="${REGION}-a" --project="$PROJECT" \
  --format='value(metadata.items.filter("key:startup-script").extract("value"))' 2>/dev/null | wc -c)
local_size=$(wc -c < infrastructure/gcp/startup.sh)
# Compared loosely: the metadata round trip normalises trailing whitespace, so an exact byte match
# would fail on a correct push. A large discrepancy means truncation, which is what matters.
if [ "$pushed_size" -lt $((local_size - 200)) ]; then
  log "FATAL: the boot script in metadata is $pushed_size bytes but the local file is $local_size."
  log "  It appears truncated; the VM would boot an incomplete script."
  exit 1
fi
log "  boot script in metadata: $pushed_size bytes"

log "pushed tag $TAG; digests recorded in $MANIFEST"
log "to roll the VM onto it:"
log "  gcloud compute instances add-metadata $VM_NAME --zone=${REGION}-a \\"
log "    --metadata image-tag=$TAG --project=$PROJECT"
log "  gcloud compute ssh $VM_NAME --zone=${REGION}-a --project=$PROJECT --tunnel-through-iap \\"
log "    --command 'sudo google_metadata_script_runner startup'"
