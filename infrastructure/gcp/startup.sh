#!/usr/bin/env bash
#
# VM boot script. Architecture §27, §30.
#
# Runs on every boot, not just the first, so it must be idempotent: installing Docker, writing the
# env file, and starting the stack all have to be safe to repeat.
#
# Secrets are read from Secret Manager using the VM's ATTACHED SERVICE ACCOUNT — there is no key
# file anywhere on this machine. The env file it writes is root-only and lives outside the repo, so
# `git status` can never show it and a repo archive cannot contain it.
set -euo pipefail

PROJECT="${PROJECT:-uit-uit-et-servicenow-ai-dev}"
REGION="${REGION:-us-central1}"
APP_DIR=/opt/docreview
ENV_FILE=/etc/docreview.env

log() { echo "[startup] $(date -Is) $*"; }

# ---------------------------------------------------------------- Docker
if ! command -v docker >/dev/null 2>&1; then
  log "installing docker"
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl git jq
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg \
    -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
  systemctl enable --now docker
else
  log "docker already present"
fi

# ---------------------------------------------------------------- Secrets
# Read via the attached service account. A failure here is fatal: starting with a blank Mongo
# password or internal token would produce a running system that is quietly wrong, and the
# application's own startup guards would reject most of them anyway.
secret() {
  gcloud secrets versions access latest --secret="$1" --project="$PROJECT" 2>/dev/null \
    || { log "FATAL: cannot read secret '$1' — check the VM's service account has \
roles/secretmanager.secretAccessor"; exit 1; }
}

log "reading secrets"
MONGO_PASSWORD="$(secret mongo-password)"
INTERNAL_SERVICE_TOKEN="$(secret internal-service-token)"
DOCREVIEW_SECRET_KEY="$(secret docreview-secret-key)"
# Seeds the embedding provider. NOT optional in practice: OpenAiEmbeddingModel binds its client when
# the bean is built, so unlike the chat gateways it cannot be repointed from Admin per request. With
# this blank, embedding calls default to api.openai.com and every indexing job fails with
# "401 You didn't provide an API key" — which reads as a credential problem rather than a missing
# base URL. `|| true` so a deployment without a key still boots; comparisons then fail loudly.
LITELLM_API_KEY="$(gcloud secrets versions access latest --secret=litellm-api-key \
  --project="$PROJECT" 2>/dev/null || true)"
DEMO_ADMIN_PASSWORD="$(secret demo-admin-password)"
DEMO_STANDARDS_ADMIN_PASSWORD="$(secret demo-standards-password)"
DEMO_REVIEWER_PASSWORD="$(secret demo-reviewer-password)"

# Non-secret deployment settings come from instance metadata, so the same script serves any
# environment without editing.
meta() {
  curl -fsS -H "Metadata-Flavor: Google" \
    "http://metadata.google.internal/computeMetadata/v1/instance/attributes/$1" 2>/dev/null || true
}

DOMAIN="$(meta domain)"
GCS_BUCKET="$(meta gcs-bucket)"
SECURITY_MODE="$(meta security-mode)"; SECURITY_MODE="${SECURITY_MODE:-sso}"
GOOGLE_CLIENT_ID="$(meta google-client-id)"
SSO_HOSTED_DOMAIN="$(meta sso-hosted-domain)"; SSO_HOSTED_DOMAIN="${SSO_HOSTED_DOMAIN:-stanford.edu}"
SSO_ADMIN_EMAILS="$(meta sso-admin-emails)"
SSO_STANDARDS_ADMIN_EMAILS="$(meta sso-standards-admin-emails)"
IMAGE_TAG="$(meta image-tag)"; IMAGE_TAG="${IMAGE_TAG:-latest}"
# A safety bound against unbounded fan-out, overridable from instance metadata so it can be tuned
# without a redeploy. Previously hardcoded to 120 here, which silently overrode the application
# default: a three-standard selection of 187 requirements lost 67, and the third standard was
# evaluated against nothing at all.
MAX_REQS="$(meta max-requirements-per-comparison)"; MAX_REQS="${MAX_REQS:-500}"
LITELLM_BASE_URL="$(meta litellm-base-url)"
REPO="${REGION}-docker.pkg.dev/${PROJECT}/docreview"

if [ -z "$DOMAIN" ]; then
  log "FATAL: instance metadata 'domain' is not set; Caddy cannot request a certificate"
  exit 1
fi

# ---------------------------------------------------------------- Env file
# 0600 and owned by root: compose reads it as root, and nothing else needs to.
log "writing $ENV_FILE"
umask 077
cat > "$ENV_FILE" <<EOF
DOMAIN=$DOMAIN

# --- Object storage: GCS via the attached service account, no key file ---
#
# GCS_BUCKET is a SEED, not the source of truth. An administrator sets the bucket under
# Admin -> Cloud sync; both review-core and document-processing resolve it per call, so repointing
# it takes effect without a redeploy. Blank here is supported — the first object operation then
# reports "Set one under Admin -> Cloud sync" rather than failing obscurely.
STORAGE_PROVIDER=gcs
GCP_PROJECT_ID=$PROJECT
GCS_BUCKET=$GCS_BUCKET
GCS_STANDARDS_PREFIX=standards/

# --- Datastores (self-hosted on this VM) ---
MONGO_ROOT_USERNAME=docreview
MONGO_ROOT_PASSWORD=$MONGO_PASSWORD
QDRANT_API_KEY=$INTERNAL_SERVICE_TOKEN

# --- Internal service auth ---
INTERNAL_SERVICE_TOKEN=$INTERNAL_SERVICE_TOKEN
REVIEW_CORE_BASE_URL=http://review-core:8080
DOCREVIEW_SECRET_KEY=$DOCREVIEW_SECRET_KEY

# --- Authentication ---
SECURITY_MODE=$SECURITY_MODE
GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID
GOOGLE_CLIENT_SECRET=
GOOGLE_CLIENT_AUTH_METHOD=none
SSO_HOSTED_DOMAIN=$SSO_HOSTED_DOMAIN
SSO_ADMIN_EMAILS=$SSO_ADMIN_EMAILS
SSO_STANDARDS_ADMIN_EMAILS=$SSO_STANDARDS_ADMIN_EMAILS
DEMO_ADMIN_PASSWORD=$DEMO_ADMIN_PASSWORD
DEMO_STANDARDS_ADMIN_PASSWORD=$DEMO_STANDARDS_ADMIN_PASSWORD
DEMO_REVIEWER_PASSWORD=$DEMO_REVIEWER_PASSWORD

# --- Model provider: configured from Admin, NOT here ---
#
# Deliberately blank. An administrator sets the provider, key, and model aliases under
# Admin -> AI & matching; the key is encrypted at rest with DOCREVIEW_SECRET_KEY and the gateways
# resolve all of it per call. Baking a key in here would mean changing providers requires a
# redeploy, and would put a live credential in a file on the VM for no benefit.
#
# The consequence: comparisons fail until a provider is configured. That is a visible, explained
# state — the Admin screen's connection test reports it — rather than a silent misconfiguration.
# Seeded, and Admin remains authoritative for the CHAT gateways (which do resolve per call). See the
# note beside LITELLM_API_KEY above for why embeddings need this present at startup.
#
# Changing the provider therefore means updating Admin AND restarting this service, or the extraction
# and comparison calls follow Admin while embeddings keep using the value they booted with.
LITELLM_BASE_URL=$LITELLM_BASE_URL
LITELLM_API_KEY=$LITELLM_API_KEY

# --- Bounds. Lower than local defaults: one VM, and every comparison unit is a chain of
# --- proxied model calls, so the ceiling is the proxy's rate limit rather than local CPU (§10.5).
JOB_WORKER_THREADS=2
DOC_JOB_WORKER_THREADS=4
MAX_REQUIREMENTS_PER_COMPARISON=$MAX_REQS
DOCREVIEW_IMAGE_TAG=$IMAGE_TAG
DOCREVIEW_REPO=$REPO
EOF
chmod 600 "$ENV_FILE"

# ---------------------------------------------------------------- Source
# The compose files, fetched from the deployment bucket.
#
# NOT cloned: the working tree is not a git repository, so a clone had nothing to fetch and the boot
# failed with "open /opt/docreview/compose.yaml: no such file or directory" AFTER installing Docker
# and reading every secret — a confusing place to stop. `deploy.sh` uploads the bundle, so the same
# artifact that produced the images provides the topology.
#
# Idempotent: re-fetched on every boot, so a compose change is an upload plus a reboot.
mkdir -p "$APP_DIR"
BUNDLE="gs://${GCS_BUCKET}/deploy/docreview-compose.tgz"
if [ -n "$GCS_BUCKET" ] && gcloud storage cp "$BUNDLE" /tmp/docreview-compose.tgz 2>/dev/null; then
  log "unpacking compose bundle from $BUNDLE"
  tar xzf /tmp/docreview-compose.tgz -C "$APP_DIR"
  rm -f /tmp/docreview-compose.tgz
elif [ -f "$APP_DIR/compose.yaml" ]; then
  log "no bundle in the bucket; using the existing $APP_DIR"
else
  log "FATAL: no compose bundle at $BUNDLE and nothing in $APP_DIR."
  log "  Run ./infrastructure/gcp/deploy.sh, which uploads it, then reboot."
  exit 1
fi

# ---------------------------------------------------------------- Image digests
#
# compose.gcp.yaml pins every service BY DIGEST and marks each variable required, so the stack cannot
# start without them. The digests are produced at push time and travel in the compose bundle, because
# only the registry can supply them — a tag would let a reboot weeks later pull different bytes than
# were tested (§19.2).
#
# Appended to the env file compose already reads, rather than exported: `docker compose --env-file`
# ignores the surrounding shell environment for variable substitution.
DIGEST_MANIFEST="$APP_DIR/releases/$IMAGE_TAG/image-digests.env"
if [ ! -f "$DIGEST_MANIFEST" ]; then
  # Fall back to any manifest in the bundle. The tag directory is the convention, but a bundle built
  # with an explicit RELEASE_DIR would land elsewhere, and failing on a naming mismatch when the
  # digests are present would be needlessly brittle.
  DIGEST_MANIFEST="$(find "$APP_DIR/releases" -name image-digests.env -type f 2>/dev/null | head -1)"
fi
if [ -n "$DIGEST_MANIFEST" ] && [ -f "$DIGEST_MANIFEST" ]; then
  log "applying image digests from ${DIGEST_MANIFEST#$APP_DIR/}"
  # Comments and blanks stripped; the file is generated, so no quoting or escaping to handle.
  grep -E '^DOCREVIEW_[A-Z_]+_DIGEST=' "$DIGEST_MANIFEST" >> "$ENV_FILE"
  # Counted into a variable rather than piped through xargs: `log` is a shell function, and xargs can
  # only exec a binary, so `| xargs -I{} log ...` died with "xargs: log: No such file or directory"
  # and `set -e` aborted the boot AFTER the digests had been written. The stack was left down with an
  # error naming xargs, which says nothing about the deploy.
  pinned=$(grep -cE '^DOCREVIEW_[A-Z_]+_DIGEST=' "$DIGEST_MANIFEST")
  log "  $pinned digests pinned"
else
  log "FATAL: no image-digests.env in the compose bundle."
  log "  compose.gcp.yaml requires a digest per service and will refuse to start without one."
  log "  Re-run ./infrastructure/gcp/deploy.sh, which generates and uploads it."
  exit 1
fi

# ---------------------------------------------------------------- Start
cd "$APP_DIR"
log "authenticating docker to artifact registry"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

log "pulling images and starting the stack"
docker compose --env-file "$ENV_FILE" -f compose.yaml -f compose.gcp.yaml pull --quiet || true
docker compose --env-file "$ENV_FILE" -f compose.yaml -f compose.gcp.yaml up -d --remove-orphans

log "started; Caddy will request a certificate for $DOMAIN on first request"
