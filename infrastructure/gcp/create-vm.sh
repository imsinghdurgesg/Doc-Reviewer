#!/usr/bin/env bash
#
# Creates the VM, firewall rules, and the disk snapshot schedule. Architecture §27.
#
# Idempotent: safe to re-run. Anything that already exists is left alone.
set -euo pipefail

PROJECT="${PROJECT:-uit-uit-et-servicenow-ai-dev}"
REGION="${REGION:-us-central1}"
ZONE="${ZONE:-${REGION}-a}"
VM="${VM:-docreview-vm}"
SA="docreview-vm@${PROJECT}.iam.gserviceaccount.com"

# Deployment settings, passed as instance metadata so startup.sh needs no editing per environment.
DOMAIN="${DOMAIN:?DOMAIN must be set}"
# OPTIONAL, like the model provider. An administrator sets the bucket under Admin -> Cloud sync, and
# both storage adapters resolve it per call. Supplying it here only seeds the value so the first
# upload works before anyone opens the screen; leaving it blank is a supported deployment.
GCS_BUCKET="${GCS_BUCKET:-}"
GOOGLE_CLIENT_ID="${GOOGLE_CLIENT_ID:?GOOGLE_CLIENT_ID must be set}"
SSO_ADMIN_EMAILS="${SSO_ADMIN_EMAILS:-}"
SSO_STANDARDS_ADMIN_EMAILS="${SSO_STANDARDS_ADMIN_EMAILS:-}"
REPO_URL="${REPO_URL:-}"
SECURITY_MODE="${SECURITY_MODE:-sso}"

log() { echo "[create-vm] $*"; }

# --- Firewall -----------------------------------------------------------------
# 80 as well as 443: Let's Encrypt's HTTP-01 challenge is served on 80, so closing it means Caddy
# can never obtain a certificate.
if ! gcloud compute firewall-rules describe docreview-web --project="$PROJECT" >/dev/null 2>&1; then
  log "creating firewall rule docreview-web (80, 443)"
  gcloud compute firewall-rules create docreview-web \
    --allow=tcp:80,tcp:443 --target-tags=docreview \
    --description="HTTPS and the ACME challenge for the review workspace" \
    --project="$PROJECT"
else
  log "firewall rule docreview-web exists"
fi

# SSH via IAP only — no port 22 open to the internet. IAP authenticates with Google identity, so
# there is no key to distribute and no bastion to run.
if ! gcloud compute firewall-rules describe docreview-ssh-iap --project="$PROJECT" >/dev/null 2>&1; then
  log "creating firewall rule docreview-ssh-iap"
  gcloud compute firewall-rules create docreview-ssh-iap \
    --allow=tcp:22 --source-ranges=35.235.240.0/20 --target-tags=docreview \
    --description="SSH via IAP forwarding only" \
    --project="$PROJECT"
else
  log "firewall rule docreview-ssh-iap exists"
fi

# --- VM -----------------------------------------------------------------------
if gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" >/dev/null 2>&1; then
  log "instance $VM already exists; updating metadata only"
  gcloud compute instances add-metadata "$VM" --zone="$ZONE" --project="$PROJECT" \
    --metadata="domain=$DOMAIN,gcs-bucket=$GCS_BUCKET,security-mode=$SECURITY_MODE,\
google-client-id=$GOOGLE_CLIENT_ID,sso-admin-emails=$SSO_ADMIN_EMAILS,\
sso-standards-admin-emails=$SSO_STANDARDS_ADMIN_EMAILS,\
repo-url=$REPO_URL"
  exit 0
fi

log "creating $VM in $ZONE"
# e2-standard-4: LibreOffice rendering plus Mongo and Qdrant on one host. Smaller and parsing a
# large DOCX competes with the datastores for memory.
# 100 GB pd-balanced: documents, vectors, and Docker images.
gcloud compute instances create "$VM" \
  --project="$PROJECT" --zone="$ZONE" \
  --machine-type=e2-standard-4 \
  --image-family=debian-12 --image-project=debian-cloud \
  --boot-disk-size=100GB --boot-disk-type=pd-balanced \
  --address=docreview-ip \
  --tags=docreview \
  --service-account="$SA" \
  --scopes=https://www.googleapis.com/auth/cloud-platform \
  --metadata="domain=$DOMAIN,gcs-bucket=$GCS_BUCKET,security-mode=$SECURITY_MODE,\
google-client-id=$GOOGLE_CLIENT_ID,sso-admin-emails=$SSO_ADMIN_EMAILS,\
sso-standards-admin-emails=$SSO_STANDARDS_ADMIN_EMAILS,\
repo-url=$REPO_URL" \
  --metadata-from-file=startup-script=infrastructure/gcp/startup.sh

# --- Backups ------------------------------------------------------------------
# MongoDB is self-hosted on this disk, so a snapshot schedule is the ONLY backup. Without it a disk
# loss takes every stored comparison with it — and §19.2's promise that a saved result stays
# inspectable depends on the data still existing.
if ! gcloud compute resource-policies describe docreview-daily --region="$REGION" \
      --project="$PROJECT" >/dev/null 2>&1; then
  log "creating daily snapshot schedule (14-day retention)"
  gcloud compute resource-policies create snapshot-schedule docreview-daily \
    --region="$REGION" --project="$PROJECT" \
    --max-retention-days=14 --daily-schedule --start-time=09:00 \
    --on-source-disk-delete=apply-retention-policy
fi

log "attaching the snapshot schedule"
gcloud compute disks add-resource-policies "$VM" --zone="$ZONE" --project="$PROJECT" \
  --resource-policies=docreview-daily 2>/dev/null || log "  already attached"

log "done. First boot installs Docker and pulls images — allow ~5 minutes."
log "  logs:  gcloud compute ssh $VM --zone=$ZONE --tunnel-through-iap --project=$PROJECT \\"
log "           --command 'sudo journalctl -u google-startup-scripts -f'"
log "  site:  https://$DOMAIN"
