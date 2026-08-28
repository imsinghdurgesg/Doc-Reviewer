#!/usr/bin/env bash
#
# Creates or rotates the deployment secrets. Architecture §30.
#
#   ./infrastructure/gcp/create-secrets.sh
#
# Idempotent: adds a new VERSION to a secret that already exists rather than failing, so this is also
# the rotation path. Existing versions are retained, so a bad rotation can be rolled back.
#
# Nothing is written to disk and nothing is echoed. Values go to Secret Manager over stdin; the VM
# reads them at boot through its attached service account (§27), so no key file exists anywhere.
set -euo pipefail

PROJECT="${PROJECT:-uit-uit-et-servicenow-ai-dev}"

log() { echo "[secrets] $*"; }

# ---------------------------------------------------------------- Generators
#
# ALPHANUMERIC ONLY for anything that lands in a URI.
#
# This is not fussiness. MongoDB is addressed as mongodb://user:pass@host/db, and Spring Boot ignores
# spring.mongodb.username/password whenever spring.mongodb.uri is set — so the password HAS to be
# interpolated into the URI, and there is no shell in a compose file to percent-encode it with.
#
# A generated password containing "/" broke the deployment: the slash terminated the host section and
# every service failed with "The connection string contains an invalid host", which reads like a
# networking fault rather than a character-set problem. "@", ":", "?", and "#" fail the same way.
#
# 40 alphanumeric characters is ~238 bits of entropy, well beyond a base64 32-byte value, so nothing
# is given up by excluding the punctuation.
uri_safe() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40
}

# For values that are never parsed as a URI, where any byte is fine.
opaque() {
  openssl rand -base64 32 | tr -d '\n'
}

# `printf`, never `echo`: a trailing newline becomes part of the secret, and a key with an invisible
# newline fails authentication with an error that names neither the newline nor the key.
put() {
  local name="$1" value="$2"
  if gcloud secrets describe "$name" --project="$PROJECT" >/dev/null 2>&1; then
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- --project="$PROJECT" \
      >/dev/null
    log "rotated $name (previous versions retained)"
  else
    printf '%s' "$value" | gcloud secrets create "$name" --data-file=- \
      --replication-policy=automatic --project="$PROJECT" >/dev/null
    log "created $name"
  fi
}

log "project $PROJECT"

# In a URI (mongodb://user:pass@host) — must be alphanumeric.
put mongo-password "$(uri_safe)"

# Sent as an HTTP header and a Qdrant API key. Alphanumeric keeps it safe in both, and in any future
# URI, at no cost.
put internal-service-token "$(uri_safe)"

# AES key for the provider API key at rest (§30). Read as a string and never parsed as a URI, so full
# base64 is fine — and rotating it makes existing stored keys undecryptable BY DESIGN: re-enter the
# provider key under Admin -> AI & matching afterwards.
put docreview-secret-key "$(opaque)"

# Demo-mode logins. Meaningless under SECURITY_MODE=sso, but DemoUserProperties.validate() refuses to
# start on a blank password when the demo chain IS active, so they must exist.
put demo-admin-password "$(uri_safe)"
put demo-standards-password "$(uri_safe)"
put demo-reviewer-password "$(uri_safe)"

log "done. The VM reads these at boot; no value was printed or written to disk."
log "NOTE: rotating mongo-password requires wiping the mongodata volume — the database keeps the"
log "      credentials it was initialised with, so an existing volume rejects the new password."
