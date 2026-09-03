# Bruno collection

Every browser-facing endpoint, grouped by the flow it belongs to, with notes on what each response
should show and which invariants to check.

## Getting a credential

Both environments run **SSO**, so there is no password to send and HTTP Basic returns 401. Requests
carry a session cookie from a signed-in browser instead — which also means these calls exercise
exactly the path the real UI uses, rather than a parallel one that might be authorized differently.

1. Sign in at `http://localhost:8080` (or the GCP host).
2. DevTools → Application → Cookies → copy the `JSESSIONID` pair, e.g. `JSESSIONID=A1B2C3…`.
3. Paste it into the environment's `sessionCookie` variable.

The cookie expires. When everything 401s at once, re-copy it — **`01-auth/auth config` still
returning 200 is the tell**: the service is healthy and only your credential is stale.

## Run order

`01-auth` first — it proves the service is up and the cookie works. After that the folders are
independent, but within `04-comparisons` the read requests need a `comparisonId`, and
`finding evidence` needs a `findingId` copied from `get comparison`.

## Requests that WRITE

Marked in their own docs, listed here so nothing is a surprise:

| request | effect |
|---|---|
| `04-comparisons/create comparison` | starts a review — **costs model calls** |
| `04-comparisons/rerun` | new review from the same inputs — costs model calls |
| `04-comparisons/retry requirement` | re-evaluates one requirement — costs model calls |
| `05-applicability/review condition` | records a governed decision |
| `06-admin/*` PUT/POST | changes configuration |

Everything else is read-only.

## What to check, not just that it returns 200

**Coverage arithmetic** — `04-comparisons/get comparison`:

```
compared + notApplicable + notEvaluated                                    == totalRequirements
applicabilityConfirmed + applicabilityReviewRequired + extractionReviewRequired == compared
```

`conclusionEligible` is a subset of `compared`, not equal to it. An extraction-review result was
measured and is visible, but excluded from the conclusion because a stated obligation went
unassessed.

**No-match selection** — `04-comparisons/candidates`: `message` is populated when nothing clears the
0.35 relevance floor. That is the honest "no standard in this library corresponds to your document"
outcome, rather than comparing against the closest one and returning a wall of MISSING.

**The exclusion guard** — `05-applicability/review condition`, the highest-value check in the
collection:

1. POST with `reviewedRole: SCOPE_GATE`, `authoriseExclusion: true` → response shows
   `canDriveNotApplicable: true`.
2. POST again with `reviewedRole: CONDITIONAL_OBLIGATION`, `authoriseExclusion` still `true` →
   must come back **`false`**.

Exclusion authority is recomputed on every revision, never carried over. Otherwise a requirement
would stay excluded from reviews on the strength of a classification that no longer exists.

**Key never leaves the server** — `06-admin/ai config` returns `apiKeySet` as a boolean. A key value
in that response is a defect worth reporting immediately.

**Promoted prompt** — `06-admin/prompts` should read `COMPARE-V2`. V3 briefly let the model report
that our own extraction was incomplete and was withdrawn: evaluated requirements fell from 187 to 46
across one 191-requirement run.

## Expected empty results in production

Not failures:

- `05-applicability/conditions` returns an empty list. The production corpus is `REQSET-V1`, which
  predates the field storing applicability conditions, so there is nothing to review.
- `policy-owner-queue` is empty for the same reason.

## The `07-worker` folder

Internal routes on the retrieval-comparison service. They need an `X-Internal-Token` header added
manually, with the token from the container environment, and on GCP an SSH tunnel — the worker is not
published to the internet.

Useful for the identity question: a requirement's `_id` is
`standardVersionId:requirementSetVersion:requirementId`. `requirementId` **alone is not unique** — it
recurs across standards, 47 of 191 in one three-standard comparison.

## Scope

Only `review-core`'s `/api/**` surface plus two worker routes. Not covered: document-processing
internals, and multipart upload (`POST /api/uploads`), which is easier to drive from the UI than to
hand-assemble.
