# Sprint 14 — Submission Review & Verification

## Status

Officials can find a submission, inspect the evidence the verification used,
and decide with a reason. The decision is persisted and audited, and the
automated evidence stays exactly as the machine left it.

- **Backend:** 539 tests green (34 new in `tests/test_review_workflow.py`),
  ruff clean, `docs/openapi.yaml` regenerated. **One migration**
  (`c5d81e3f9a27`). It upgrades, downgrades and re-upgrades on SQLite and on
  Postgres 16, with `alembic check` clean. It was also applied to a database
  holding real Sprint 13 data, and the backfill was verified there.
- **Dashboard:** typecheck clean, 116 tests green (30 at the start of Sprint
  13, 84 at its end), and it builds.
- **Mobile:** no code changed. A forced re-run (`testDebugUnitTest --rerun`)
  gave 309 tests, 0 failures, 1 skipped.
- **Live E2E:** Postgres, Redis, the API, the Celery worker (MediaPipe) and
  the dashboard ran together, using real squat footage. The API script passed
  36/36 checks and the headless-Edge browser run passed 21/21 (see below).
- **Still pending from Sprint 11:** validation on a real Android device. The
  device scores in these runs are claims sent by an HTTP client.

---

## What already existed, and was kept

The Sprint 8 review system was already in place and is extended here, not
replaced:

- the severity-ordered queue;
- the review page, with signed-URL video and the server's skeleton overlay;
- approve / reject / request-resubmission, with notes required and the
  hand-entered-score rule;
- the append-only `review_actions` table and region scoping (404 outside the
  reviewer's region);
- athlete-visible decisions, and resubmission freeing the session slot while
  the original is kept.

The Sprint 11 verification record (checks, mobile/server snapshots) and the
Sprint 12 flag evidence were served by `/api/verification/{id}`, but the
review page did not use them. It does now.

## What was wrong, and is fixed

| Problem | Effect | Fix |
|---|---|---|
| Officials could decide while a result was `uploaded`/`processing` | The worker then discarded its verdict (it only writes over `processing`), so the automated evidence was lost | No decision until the machine verdict exists (409, "Verification has not finished") |
| `status` was the only record of the machine verdict | After approval, nobody could tell whether the server had said *verified* or *flagged* | `test_results.verification_verdict`, written once by the worker and never by a reviewer |
| Audit rows held only action, notes and time | No structured reason and no record of the transition | `review_actions.reason`, `previous_status`, `new_status` |
| No concurrency protection beyond a status re-read | Two officials could act on the same result from stale screens | A row lock (`SELECT … FOR UPDATE`), plus `expected_version` (409 when stale) |
| A machine rejection could be *approved* with a hand score | An unreadable recording could become an official result | A machine rejection can only be confirmed or sent back for resubmission |
| The queue loaded flags row by row (Sprint 13 known issue) | N+1 queries | Counts come from correlated subqueries in one SELECT, with a test that the query count does not grow with rows. Index on `review_actions.test_result_id` |
| Sign-out, then another official signs in on the same tab | They landed on the previous official's last page (found by the E2E run) | An explicit sign-out carries no return page |
| A video the browser cannot decode | Showed "link may have expired" | Says the format cannot be played, and offers the recording through the same signed link |

## The review state machine

`TestResultStatus` is unchanged. No second lifecycle was added. What a
reviewer may do is decided in one place (`_allowed_actions` in
`routers/dashboard.py`). The dashboard shows `allowed_actions`; it never
decides.

| Current state | Review status (derived) | Allowed actions |
|---|---|---|
| `uploaded`, `processing` | `awaiting_verification` | none — 409 until the verdict |
| `verified` | `awaiting_approval` | approve, reject, request resubmission, flag |
| `flagged` | `needs_review` | approve, reject, request resubmission, flag |
| `rejected` by the machine, unreviewed | `invalid` | reject (confirm), request resubmission |
| `approved` | `approved` | none (settled) |
| `rejected` after review | `rejected` | none (settled) |
| `pending_sync` after review | `resubmission_requested` | none — the athlete records again |
| `pending_sync`, never reviewed | `awaiting_upload` | none |

The review status is computed on the server and returned on every queue row
and on the detail (`review_status`).

### Decisions

| Action (`review_actions.action`) | Result becomes | Reason | Notes | Flags |
|---|---|---|---|---|
| `approved` | `approved`; `final_score` = server score, or a hand-entered score with a note | optional | optional (required with a hand score) | open flags dismissed |
| `rejected` | `rejected` | required | required, shown to the athlete | open flags confirmed |
| `requested_resubmission` | `pending_sync`; the original is kept and the session slot freed | required | required, shown to the athlete | open flags dismissed (unchanged from Sprint 8) |
| `flagged` (new) | `flagged`, still decidable | required | required, **internal** (never shown to the athlete) | adds a `manual` flag with the reason and severity |

Reason codes (`ReviewReason`): `identity_mismatch`, `multiple_people`,
`invalid_video`, `score_discrepancy`, `technical_issue`, `form_issue`,
`duplicate_submission`, `other`.

### Automated verification vs human review

A decision changes only `status`, `final_score`, flag resolution, and (for
`flagged`) a new manual flag. It never touches `verification_verdict`,
`verification_reason`, `server_score`, `provisional_score`, `server_result`,
`mobile_result`, `verification_checks`, or the automatic flags themselves
(they are resolved, never deleted). The E2E run compared all of these in
Postgres before and after the decisions.

## Audit

Every action appends one `review_actions` row: result, official, action,
reason, notes, previous status, new status and time. Rows are never updated
or deleted. The detail's `review_history` returns each row with the
official's name and id. The athlete's result view shows only the latest
*decision* (`flagged` is excluded) and never shows a reviewer's own flag.

## Authorization

This is unchanged from Sprint 13 and verified again:

- an anonymous caller gets 401 on every review endpoint and on verification,
  even with `ALLOW_UNAUTHENTICATED` on;
- an athlete token gets 403;
- a regional reviewer gets 404 for another region's detail, verification and
  decisions, and never sees it in the queue;
- an SAI admin reviews every region.

Media is served only through short-lived signed links. A tampered or missing
token returns 404.

## API changes

| Endpoint | Change |
|---|---|
| `POST /api/dashboard/reviews/{id}/action` | Adds `action: "flagged"`; `reason` (required except for approve); `severity` (flag only); `expected_version`. The response adds `review_status` and `review_version`. 409 for a stale version, an unverified result, a disallowed transition, or a settled result |
| `GET /api/dashboard/reviews` | Returns the richer submission rows. New filters: `review_status`, `session_id`, `athlete` (id or part of the name), `flags` (`open`/`none`/`low`/`medium`/`high`), `submitted_from`/`submitted_to`. The default is `needs_review` + `invalid` |
| `GET /api/dashboard/submissions` | The same new filters, and the same row fields |
| `GET /api/dashboard/reviews/{id}` | Adds `review_status`, `verification_verdict`, `verification_reason`, `review_version`; history entries add `official_id`, `reason`, `previous_status`, `new_status` |

Clients that post a reject or resubmission must now send a `reason`. The only
client is the dashboard. Five backend tests were updated to send one, and one
that decided an `uploaded` result now sets the machine verdict first.

## Dashboard

- **Reusable review components** (`components/review/`): `ResultComparison`,
  `VerificationSummary`, `IntegrityFlagList`, `ReviewHistory`,
  `DecisionPanel`, `SubmissionFacts`, `IdentityPanel`/`BenchmarkPanel`.
- **Shared components:** `ConfirmDialog`, `Panel`, `SubmissionFilters` (with
  `useListFilters`, URL-driven and shared by the queue and the submission
  list), `ReviewStatusBadge`, `VerdictBadge`.
- The review page and the read-only submission page are built from the same
  components.
- The app now uses a data router (`createBrowserRouter`) so the decision panel
  can block navigation over unsaved input. The existing `<Routes>` tree is
  unchanged beneath it. `ActionForm` and its tests are replaced by
  `DecisionPanel` and its tests, including the four original behaviours.

## Live E2E evidence

This ran against the isolated `f4all_e2e` database (development data
untouched), through the dashboard origin. The accounts were the Sprint 13
admin, the Kerala reviewer and the Tamil Nadu reviewer. The video is "Squat -
exercise demonstration video" (FitnessScape, Wikimedia Commons, CC BY 3.0),
854×480, 213 frames, 7.1 s, with 2 squats counted by hand. The Sprint 11
transcode is MPEG-4 Part 2, which browsers cannot play. The browser run used
an H.264 re-encode of the same footage, which is what the app uploads.

**API script (36/36).**

- **Pipeline:**
  - Honest claim (2 reps): the server counted 2 and flagged it (low), for
    `duplicate_frames` (a benign artefact of this clip, documented in Sprint
    12) and `identity_unconfirmed`.
  - The same footage claimed as 9 by another athlete: flagged, with
    `score_discrepancy` high (evidence 9 / 2 / tolerance 2) and
    `duplicate_submission` high.
- **Queue:** the Kerala reviewer saw both. Filters by athlete name and by
  integrity severity worked on the server. The Tamil Nadu reviewer saw none.
- **Evidence and access:**
  - The signed video URL streamed the exact bytes; a tampered or bogus token
    returned 404.
  - Tamil Nadu reviewer: 404 on detail, verification and decision.
  - Anonymous: 401 on queue, detail, decision and verification.
  - Athlete: 403 on queue, detail and decision.
- **Decisions:**
  - A reject without a reason was refused (422).
  - A resubmission request with a reason was recorded, and a second decision
    on it was refused (409).
  - A flag for a second look was recorded.
  - Two officials then decided at the same instant, both at version 1:
    exactly one got 200 and the other got 409, naming who decided.
- **Resubmission:** accepted into the same session and verified; the original
  was kept.
- **Postgres afterwards:**
  - three audit rows, each with reviewer, reason and transition;
  - `verification_verdict`, scores, and the md5 of each snapshot and check
    unchanged;
  - automatic flags resolved, not deleted.
- **Athlete view:** the athlete sees the resubmission note, and never sees the
  internal flag note.

**Browser (headless Edge, 21/21), as the Kerala reviewer.**

- Queue, then the athlete filter, then the review page.
- **Evidence:**
  - the H.264 recording played through its signed link (duration 7.10 s);
  - phone and server results side by side (2 = 2, form 50 = 50, within the
    allowed difference);
  - all flags with their evidence and limits, and jump-to-flag moved the
    video;
  - the verification record (`f4all-verify/1.2`).
- **Unsaved changes:** a half-written rejection blocked navigation, and
  "Stay" kept the draft.
- **Decision:** reject with reason "Duplicate submission". The dialog stated
  the outcome and "cannot be undone". Then came the success notice, the panel
  closed, and the history showed "Rejected by E2E Kerala Reviewer · Reason:
  Duplicate submission · Flagged → Rejected". It survived a reload and was
  confirmed by the API (verdict kept: flagged).
- **Conflict:** with a submission open, the admin rejected it through the API.
  The reviewer's resubmission request was refused. The page kept "Your
  decision was not recorded — another official acted on this submission
  first" and showed the admin's decision. Only one audit row exists.
- **Region isolation:** the Tamil Nadu reviewer opening that URL saw "outside
  your region", and their queue did not list it.

## Known limits

- `requested_resubmission` dismisses open flags, as in Sprint 8. A per-flag
  confirm/dismiss choice is not part of this sprint.
- Downgrading past `c5d81e3f9a27` after reviewers have used `flagged` needs
  those audit rows handled first: older code cannot load that action value.
- Decisions are refused while verification runs. A result stuck in
  `processing` needs an admin to re-run verification
  (`POST /api/verification/{id}/process`, or `app.cli reverify-pending`).
- The review queue still loads the stats bar separately. That is fine at
  current scale; left for Sprint 23 load testing.
