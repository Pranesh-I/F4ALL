# Sprint 8 — Official Dashboard v1

## Status

Implementation complete. `dashboard/` is a working React app; the backend has
official sign-in, a triaged queue, full review context, the skeleton overlay
data, decision rules, leaderboard and stats.

- Backend: 30 dashboard tests within the 222-test suite, lint clean, migration
  applies and reverses.
- Dashboard: typecheck clean, 24 tests, production build, CI job added.
- **The Definition of Done was walked live**: an official signed in through the
  dashboard's dev server, reviewed a flagged submission that the real
  verification pipeline had flagged, approved it, and the athlete's API showed
  the decision.

---

## Goal

SAI officials can review and act on submitted results.

---

## Rules the server enforces

The UI mirrors these; it is not what protects them.

**Region scoping in SQL, failing closed.** Every query joins through the athlete
and filters by the reviewer's region. The previous filter returned *everything*
for a regional reviewer whose region was unset — a half-provisioned account was
national. It now returns nothing. Out-of-region results are `404`, never `403`.

**No machine number becomes official by default.** Approval copies the
*server's* score. If the server could not score the video, approval is refused
unless the reviewer enters a score *and* a note saying how they got it; the
audit record is prefixed `[Score entered by reviewer: N]`. The phone's
provisional score is never promoted — previously it silently was.

**Rejections and resubmission requests need a reason.** The athlete sees it.

**Decided is decided.** Approved or rejected results return `409`. Changing a
settled selection-relevant outcome deserves its own deliberate workflow.

**Leaderboard is approved results only**, best attempt per athlete. A ranking
built on unreviewed numbers would put a tampered submission at the top of the
list officials use to spot talent.

---

## Official accounts

- Email + password, provisioned by `python -m app.cli create-official`. Nobody
  can make themselves a reviewer of children's results.
- scrypt from the standard library; 12-character minimum; password read from
  the terminal or an env var, never an argument (shell history, process list).
- Five failures lock the account 15 minutes; while locked the password is not
  even evaluated.
- Unknown email and wrong password return identical responses and spend the same
  scrypt time, so timing does not reveal which emails are accounts.
- `is_active` rather than deletion — the audit trail must still name who
  approved what. A deactivated official's unexpired token stops working at once.
- Athlete tokens get `403`; athlete refresh tokens cannot be exchanged on the
  dashboard refresh endpoint.
- The dashboard keeps its session in `sessionStorage`, not `localStorage`.

---

## The review screen

- **Video with the server's skeleton.** The verification task now stores the
  landmarks *it* extracted (`videos.pose_sequence_key`), served region-scoped at
  `/reviews/{id}/pose`. The overlay accounts for letterboxing, hides joints below
  0.5 visibility, and **draws nothing across a tracking gap** rather than
  freezing the last pose — a missing skeleton is itself evidence.
- **Scores:** phone, server, official, and the signed difference.
- **Flags** by severity with plain-language reasons; integrity flags with a
  timestamp get a "jump to" button.
- **Identity:** registration photo beside the face-check outcome, with explicit
  text that "did not run" is not a pass.
- **Benchmark**, marked provisional and "do not use for selection".
- **Audit trail:** who decided what, when, with their note.

The queue is ordered by highest unresolved flag severity, then oldest first, with
`X-Total-Count` for paging.

---

## Bugs found

**Every real verification job crashed on its first line.** Celery passes the
result id as a string; `db.get(TestResult, "…")` needs a `UUID` and raises. The
error handler repeated the same lookup and raised too, so the result stayed in
`processing` forever. Unit tests always passed UUID objects, so none of them
could see it; the live end-to-end run found it immediately. Ids are now
normalised at the task boundary, with a regression test.

**Region scoping failed open** — see above.

**Approval could promote the phone's number** — see above.

**Local video URLs were `file://` paths.** No browser would play them, so a
reviewer could never watch a video in development. `LocalStorage` now issues
signed, expiring URLs served by `/api/media/{token}` with HTTP range support.
That endpoint requires `purpose: media` in the token — otherwise an athlete's
login JWT, signed with the same secret, would have worked as a media link — and
refuses keys that resolve outside the storage root.

**No CORS.** A dashboard on its own origin could not call the API. Explicit
origins only, never `*`.

---

## Verified end to end

Live API on a real file database, dashboard dev server proxying to it, real
MediaPipe verification (26s on this machine — inside the 5-minute SLA):

```
PASS  verification pipeline ran
      {'status': 'flagged', 'server_score': 0.0, 'device_score': 14.0,
       'integrity_findings': ['no_subject']}  (26.1s)
PASS  dashboard serves its app shell
PASS  wrong official password refused
PASS  official login through proxy
PASS  result appears in the Kerala reviewer's queue
PASS  review detail with context
      status=flagged server=0.0 flags=['score_discrepancy', 'no_subject'] pose=True
PASS  signed video URL streams with range support
PASS  server pose sequence for overlay
PASS  official approves
PASS  decided result cannot be re-decided
PASS  approved result on leaderboard
PASS  athlete sees approval, official score and review
26/26 checks passed
```

The test video is a rendered stick figure, which MediaPipe correctly does not
recognise as a person — so the server flagged it `no_subject`, which is exactly
the path a reviewer needs to handle.

---

## Sprint 8 Definition of Done

| Criterion | Status |
|---|---|
| React + TypeScript (Vite, Tailwind, React Query) | Done |
| Official auth, `sai_admin` vs `regional_reviewer` | Done |
| Region scoping | Done — in SQL, fails closed |
| Review queue filterable by region / test / status | Done — plus severity triage and paging |
| Video review with skeleton overlay | Done — server landmarks |
| Flag reasons with severity | Done |
| Device vs server score comparison | Done |
| Approve / reject / request resubmission | Done |
| Audit trail in `review_actions` | Done — shown on the review screen |
| Leaderboard by test / region / age group | Done — plus gender |
| Dashboard in CI: typecheck + build | Done — plus tests |
| **Official logs in, reviews a flagged submission with full context, approves or rejects; logged and reflected to the athlete** | **Done, walked live** |

---

## What is not proven

- **In a browser by a person.** The app builds, its logic and forms are tested,
  and it was served and exercised over HTTP, but nobody has clicked through it in
  a real browser from this machine. The skeleton canvas drawing in particular is
  only verified through its geometry functions.
- **Against PostgreSQL** — still SQLite everywhere, as in Sprint 5.
- **With real athletes' videos** — still blocked on reference footage.
