#!/usr/bin/env sh
#
# Builds one reactor module, retrying a TRANSIENT dependency-download failure. Architecture §29.
#
#   ./infrastructure/build/mvn-package-retry.sh services/document-processing [extra mvn args...]
#
# WHY THIS EXISTS
# ---------------
# The service Dockerfiles each run a full reactor build, which downloads a few hundred artifacts. A
# dropped connection to Maven Central fails the whole image build, and because deploy.sh builds four
# images in sequence the loss is not one image but the entire release: no digests are written, so
# nothing can be deployed. Observed twice in one day:
#
#   repo.maven.apache.org:443 failed to respond          fetching org.hamcrest:hamcrest:pom:3.0
#   Premature end of Content-Length delimited message     fetching proto-google-common-protos
#
# Neither is a build error. Both succeed on a re-run because Maven caches whatever it already pulled,
# so each attempt starts closer to done.
#
# WHY IT RETRIES ONLY DOWNLOAD FAILURES
# -------------------------------------
# A blanket `for i in 1 2 3` would also retry a COMPILE error, turning a ten-second failure into three
# and burying the real message under two irrelevant repeats. Worse, a flaky test would eventually pass
# and ship. So the log is inspected and a retry happens only when the failure names a transfer
# problem; anything else exits immediately with Maven's own status.
set -eu

# A module path, or "." for the whole reactor. The two need different Maven arguments: `-pl . -am`
# selects only the root aggregator POM and silently builds nothing, which would produce an image with
# a stale jar rather than an error.
MODULE="${1:?usage: mvn-package-retry.sh <module-path>|. [mvn args...]}"
shift

if [ "$MODULE" = "." ]; then
    SELECT=""
    WHAT="the reactor"
else
    SELECT="-pl $MODULE -am"
    WHAT="$MODULE"
fi

ATTEMPTS=3
LOG=$(mktemp)
# Cleaned up on every exit path, including the failure ones below.
trap 'rm -f "$LOG"' EXIT

# Signatures of a transfer failure rather than a build failure. Matched case-insensitively against
# Maven's own wording. Built by appending so the list stays readable and each entry stays one string —
# a line-continued shell pattern needed post-processing to remove the continuations, which is a second
# thing to get wrong for no benefit.
TRANSIENT='could not transfer artifact|failed to respond'
TRANSIENT="$TRANSIENT|Premature end of Content-Length|transfer failed"
TRANSIENT="$TRANSIENT|Connection reset|Connection timed out|Read timed out"
TRANSIENT="$TRANSIENT|checksum validation failed|Could not resolve dependencies"
TRANSIENT="$TRANSIENT|Failed to read artifact descriptor|Could not collect dependencies"
TRANSIENT="$TRANSIENT|no route to host|temporary failure in name resolution"
TRANSIENT="$TRANSIENT|502 Bad Gateway|503 Service Unavailable|504 Gateway"

attempt=1
while :; do
    echo "[mvn-retry] building $WHAT (attempt $attempt of $ATTEMPTS)"

    # `clean` is intentionally NOT repeated on a retry: the point of retrying is to keep the artifacts
    # already downloaded and the modules already built. -T1C stays off — a parallel build interleaves
    # download errors from several threads and makes the log below hard to attribute.
    if [ "$attempt" -eq 1 ]; then
        GOALS="clean package"
    else
        GOALS="package"
    fi

    # Output goes to the log AND to the console, so a human reading the build sees it live while the
    # classifier below can still inspect it.
    #
    # NOT `mvnw | tee`: in a pipeline the exit status is the LAST command's, so a failed Maven behind a
    # successful tee reports success. That is the same false-green that made a failed image build
    # report exit 0 and nearly led to a deploy against a tag with nothing behind it. POSIX sh has no
    # PIPESTATUS, so Maven is run unpiped and the log is echoed afterwards.
    if ./mvnw $SELECT $GOALS "$@" > "$LOG" 2>&1; then
        cat "$LOG"
        echo "[mvn-retry] $WHAT built on attempt $attempt"
        exit 0
    fi
    cat "$LOG"

    if [ "$attempt" -ge "$ATTEMPTS" ]; then
        echo "[mvn-retry] FATAL: $WHAT failed after $ATTEMPTS attempts."
        exit 1
    fi

    if ! grep -qiE "$TRANSIENT" "$LOG"; then
        echo "[mvn-retry] FATAL: $WHAT failed for a reason that is not a download problem."
        echo "[mvn-retry]   Not retrying — a compile or test failure will fail identically, and"
        echo "[mvn-retry]   repeating it would bury the real error under two more copies."
        exit 1
    fi

    # Backoff, because an immediate retry against a repository that just dropped the connection
    # usually drops it again. Short enough not to dominate a build, long enough to matter.
    delay=$((attempt * 15))
    echo "[mvn-retry] transient dependency-download failure; retrying in ${delay}s"
    sleep "$delay"
    attempt=$((attempt + 1))
done
