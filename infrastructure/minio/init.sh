#!/bin/sh
# Creates the required MinIO buckets. Architecture §23.
#
# Runs as a short-lived container that the app services wait on
# (`condition: service_completed_successfully`), so no service can start against missing buckets.
#
# set -e only: `set -u` would break on the mc image's own unset variables, and pipefail is not POSIX
# so it fails under this image's /bin/sh.
set -e

MINIO_ALIAS=local
MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://minio:9000}"

echo "Waiting for MinIO at ${MINIO_ENDPOINT}..."

# Retry rather than trusting the healthcheck alone: `mc alias set` is the first real authenticated
# call, and MinIO accepts TCP connections slightly before it will serve one.
attempt=1
until mc alias set "${MINIO_ALIAS}" "${MINIO_ENDPOINT}" \
        "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1; do
    if [ "${attempt}" -ge 30 ]; then
        echo "MinIO did not become ready after ${attempt} attempts." >&2
        exit 1
    fi
    attempt=$((attempt + 1))
    sleep 2
done

echo "Connected to MinIO."

# The three buckets in §23. `mc mb --ignore-existing` makes this idempotent, which matters because
# compose re-runs this container on every `up`.
for bucket in docreview-standards docreview-user-documents docreview-exports; do
    mc mb --ignore-existing "${MINIO_ALIAS}/${bucket}"
    echo "Bucket ready: ${bucket}"
done

# Buckets stay PRIVATE. These hold confidential organizational documents; the application reads them
# with credentials and streams bytes to authorised users itself (§7.4 — the browser never receives
# storage credentials, and it must never receive a public object URL either).
for bucket in docreview-standards docreview-user-documents docreview-exports; do
    mc anonymous set none "${MINIO_ALIAS}/${bucket}" >/dev/null 2>&1 || true
done

echo "MinIO initialization complete."
