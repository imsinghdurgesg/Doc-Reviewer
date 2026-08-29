# v1.0.0-pilot.3 — VM deploy runbook

The exact sequence, with the two environment facts that would otherwise break it.

## Two prerequisites that are easy to miss

**1. `GCS_BUCKET` must be set when running `deploy.sh`.**

The compose bundle carries `image-digests.env`, and `compose.gcp.yaml` refuses to start without a
digest per service. With `GCS_BUCKET` unset the script logs "skipping the compose bundle upload" and
exits 0 — a successful-looking run that leaves the VM unable to start the new release.

```bash
export GCS_BUCKET=stanford-docreview-standards   # the VM's own gcs-bucket metadata value
```

Verified before this deploy: the bundle already in the bucket contains `compose.yaml`,
`compose.gcp.yaml` and the `Caddyfile` but **no** `image-digests.env`, because the running stack was
deployed by tag rather than by digest.

**2. The VM currently runs `image-tag=users-110038`, deployed by TAG not digest.**

`startup.sh` looks for `releases/$IMAGE_TAG/image-digests.env` and, if absent, falls back to *any*
`image-digests.env` under `releases/`. That fallback is a hazard when an old manifest is present. Here
it is safe only because the VM has **no** manifest at all — confirmed by `find / -name
image-digests.env`, which returned nothing. So a missing upload fails loudly at startup rather than
silently pinning the wrong bytes.

## Sequence

```bash
# 1. Build, push, resolve digests from the registry, and upload the bundle.
export GCS_BUCKET=stanford-docreview-standards
TAG=v1.0.0-pilot.3 ./infrastructure/gcp/deploy.sh

# 2. Confirm all four digests resolved. deploy.sh exits non-zero if any is missing,
#    but check the registry directly rather than trusting an exit code — a failed
#    image build once reported exit 0 through a pipeline.
for svc in review-core document-processing retrieval-comparison review-web; do
  gcloud artifacts docker images describe \
    "us-central1-docker.pkg.dev/uit-uit-et-servicenow-ai-dev/docreview/$svc:v1.0.0-pilot.3" \
    --project=uit-uit-et-servicenow-ai-dev --format='value(image_summary.digest)'
done

# 3. Point the VM at the new tag.
gcloud compute instances add-metadata docreview-vm --zone=us-central1-a \
  --metadata image-tag=v1.0.0-pilot.3 --project=uit-uit-et-servicenow-ai-dev

# 4. Re-run the startup script: it downloads the bundle, pins the digests, pulls, and restarts.
gcloud compute ssh docreview-vm --zone=us-central1-a --tunnel-through-iap \
  --command 'sudo google_metadata_script_runner startup'

# 5. Confirm the running containers match the pinned digests, by digest not by tag.
gcloud compute ssh docreview-vm --zone=us-central1-a --tunnel-through-iap \
  --command 'sudo docker ps --format "{{.Names}}|{{.Image}}"'
```

## Then run `post-deploy-checks.md`

History compatibility first. The 640 stored findings carry no `conclusionEligible` key, and that is
the failure this release exists to prevent — it affects every historical comparison, not only new ones.

## Rollback

Pre-deploy digests are in `pre-deploy-baseline.md`. To revert:

```bash
gcloud compute instances add-metadata docreview-vm --zone=us-central1-a \
  --metadata image-tag=users-110038 --project=uit-uit-et-servicenow-ai-dev
gcloud compute ssh docreview-vm --zone=us-central1-a --tunnel-through-iap \
  --command 'sudo google_metadata_script_runner startup'
```

Note that rolling back to `users-110038` restores a TAG-based deploy, and `compose.gcp.yaml` now
requires digests. If the rollback needs to be exact, restore the pre-deploy digests into an
`image-digests.env` under that tag's release directory and re-upload the bundle, or roll the disk back
to snapshot `docreview-pre-pilot2-20260829`.

Data rollback, if implicated: `/var/tmp/pre-pilot2-20260829T114937Z.archive`.
