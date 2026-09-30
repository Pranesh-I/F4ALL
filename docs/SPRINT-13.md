# Sprint 13 — Admin Dashboard Foundation

## Status

The admin dashboard now runs under `/admin/*`, with a sidebar shell and route
protection. It has session management across the full lifecycle, a dashboard
home, and a submission list and detail. All of it reads from the backend.

- **Backend:** 505 tests green (47 new in `tests/test_admin_dashboard.py`),
  ruff clean, and `docs/openapi.yaml` regenerated. **No migration:** the
  Sprint 7/11/12 schema already held everything, and `alembic check` is clean
  after applying all nine migrations to an empty Postgres 16 database.
- **Dashboard:** typecheck clean, 85 tests green (30 before), and it builds.
- **Mobile:** no code changed. A forced re-run (`testDebugUnitTest --rerun`)
  gave 309 tests, 0 failures, 1 skipped.
- **Live E2E:** Postgres, Redis, the API, the Celery worker (MediaPipe) and
  the Vite dev server ran together. The API script passed 38/38 checks and
  the headless-Edge browser run passed 33/33 (see below).
- **Still pending from Sprint 11:** validation on a real Android device.

Most of Sprint 13's backend already existed: official login from Sprint 8,
session CRUD with server-computed status from Sprint 7, the stats and review
endpoints, and `/api/sessions/active` for the phone. This sprint reused all
of it, fixed two authorization gaps, and added the pieces that were missing.

---

## Fixed

| Problem | Effect before | Fix |
|---|---|---|
| `current_official` honoured the dev auth bypass (`ALLOW_UNAUTHENTICATED`, on by default in development) | On a local server, an anonymous request to any `/api/dashboard/*` endpoint was treated as a **national admin** | Official endpoints always require a token. The bypass still applies to athlete endpoints, which is what it was for |
| `GET /api/verification/{id}` treated "no caller" as an official | Anonymous dev requests got the official view of any submission | Only a signed-in official gets the official view; anonymous callers get 401 |
| Session `submission_count` ignored region | A regional reviewer saw how many submissions a national session had from every region | The count is region-scoped for reviewers, like the submission list. Admin-only rules (delete, removing a test) still use the true total |
| Sign-out only cleared the browser tab | A copied refresh token stayed usable for 90 days | `POST /api/dashboard/auth/logout` revokes it on the server (or every session with `all_devices`) |

## Added (backend)

| Endpoint | Purpose |
|---|---|
| `GET /api/dashboard/submissions` | Every submission the official may see, newest first. Filters: `status` (comma-separated), `test_type`, `region`, `session_id`. Paged, with the total in `X-Total-Count`, and region-scoped like the queue. Rows carry session id/name, athlete id, final score and verification reason |
| `GET /api/dashboard/tests` | The tests the backend can score (`TestType`, the list session creation validates against), named from the `tests` table. The dashboard offers these rather than a hard-coded list |
| `GET /api/dashboard/sessions?status=` | Filters on the status the server computes (`status_of`), so "active" is never worked out from a browser clock |
| `POST /api/dashboard/sessions/{id}/end` | Ends an **active** session now, by server time (409 otherwise). Unlike disabling, recordings made before the end still arrive within the grace period |
| `POST /api/dashboard/auth/logout` | Server-side sign-out (above) |
| `session_id`/`session_name` on `GET /api/dashboard/reviews/{id}` | Submission detail shows its session |

The session lifecycle is still the existing four states: `disabled`,
`scheduled`, `active`, `ended`. They are derived on the server from `enabled`
and the window. The plan's `DRAFT` is represented by `disabled`, since sessions
are created switched off. No conflicting state was introduced.

## Added (dashboard)

| Route | What it is |
|---|---|
| `/admin/login` | Existing sign-in. It returns to the page that was asked for |
| `/admin/dashboard` | Server counts by state (each opens the matching list), active sessions (`?status=active`) and recent submissions |
| `/admin/sessions` | List with a server-side status filter. Admins enable, disable or delete (unused sessions only) |
| `/admin/sessions/create` | SAI admin only (the route checks the role, and the API checks again). Tests come from `/api/dashboard/tests`. Redirects to the new session with a confirmation |
| `/admin/sessions/:id` | Detail, what the state means for athletes, enable/disable, **End now**, edit, delete, and the session's submissions. Refreshes every 60 s so status follows the server clock |
| `/admin/submissions` | List with status/test/session/region filters in the URL, and paging |
| `/admin/submissions/:id` | Read-only: session, phone vs server result, verification checks and timing, integrity flags, identity check. **Review and decide** opens the existing review view; deciding stays there (Sprint 14) |
| `/admin/reviews`, `/admin/reviews/:id` | The Sprint 8 queue and review page, unchanged apart from the address |
| `/admin/leaderboards` | The existing leaderboard |
| `/admin/athletes`, `/admin/athletes/:id`, `/admin/analytics` | Labelled placeholders for later sprints. They show no invented numbers |

The old addresses (`/login`, `/reviews`, `/reviews/:id`, `/leaderboard`,
`/sessions`) redirect, filters included.

These components were extracted: `AdminLayout` (sidebar + header, collapsing
behind **Menu** at phone width), `ProtectedRoute` (role-aware),
`LoadingState`, `EmptyState`, `ErrorState` (with retry, not offered for
403/404), `NotPermitted`, `SuccessNotice`, `PageHeader`, `SubmissionTable`,
`SessionForm` (create and edit), `SessionStatusBadge`, `useTestCatalog`, and
`routes.ts`. `Layout.tsx` is replaced by `AdminLayout`.

## Live E2E evidence

This ran against an isolated `f4all_e2e` database in the compose Postgres, so
development data was not touched. Accounts were made with
`app.cli create-official`: one admin, a Kerala reviewer and a Tamil Nadu
reviewer. All requests went through the dashboard origin
(`localhost:5173` → proxy → `:8010`).

**API script (38/38).**

- Anonymous requests: 401 on every admin endpoint. Athlete tokens: 403.
  Reviewer creating or ending a session: 403.
- Admin login, then create a session with all six tests. It is `active` by
  server clock, read back, and found through `?status=active`.
- A real athlete (OTP → register, Kerala) sees it on `/api/sessions/active`.
- The athlete uploaded a 6 s MP4 and submitted SIT_UPS to the session. The
  Celery worker ran the Sprint 11/12 pipeline and flagged it, with
  `server_could_not_score`, `no_subject`, `looped_frames`, and on the second
  run `duplicate_submission`.
- The admin list (session filter) and detail both show it.
- The Kerala reviewer sees it. The Tamil Nadu reviewer gets an empty list,
  404 on detail and on verification, and a session count of 0.
- Logout, then refresh: 401. The Postgres rows were checked directly.

**Browser (headless Edge, 33/33).**

- A signed-out visit to `/admin/sessions` goes to sign-in. A wrong password
  shows the server's refusal. Signing in returns to the page.
- Sidebar and header, then the dashboard tiles, which equal
  `/api/dashboard/stats`.
- Created a session in the form (six tests offered). It redirected with a
  confirmation, was confirmed stored via the API, and survived a reload.
- **End now** gave `Ended`. The status filters list it correctly.
- Submission list, filtered empty state, and detail (session, phone vs
  server, checks, flags).
- Sign out: a protected URL goes back to sign-in and the tokens are gone.
- Tamil Nadu reviewer: empty list; opening the Kerala submission by URL shows
  "outside your region"; the create page shows "no permission".
- At 390 px the navigation collapses and nothing overflows.

## Known limits

- The dev bypass still lets anonymous callers use **athlete** endpoints on a
  development server, by design. Production and staging ignore it.
- `/admin/athletes` and `/admin/analytics` are placeholders.
- `flag_count` in list rows lazy-loads flags per row (inherited from the
  queue). That is fine at 25 rows; revisit under Sprint 23 load testing.
