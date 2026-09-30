# SAI Talent Assessment — Official Dashboard

Where SAI officials run assessment sessions, see what athletes have submitted,
and review submissions the server has verified or flagged. React + TypeScript +
Vite, Tailwind, React Query.

## Run it

```bash
# backend on :8010 first (see backend/README.md), with an official account:
#   python -m app.cli create-official --email you@sai.example --name "You" --role sai_admin

cd dashboard
npm install
npm run dev            # http://localhost:5173/admin, /api proxied to :8010
```

A backend on another port: `F4ALL_API_URL=http://localhost:8000 npm run dev`.

A production build talking to a separately deployed API sets
`VITE_API_BASE_URL` at build time; that API must list this origin in
`CORS_ORIGINS`.

```bash
npm run typecheck
npm test               # vitest + Testing Library
npm run build
```

## Pages (Sprint 13)

Everything lives under `/admin`, behind sign-in (`/admin/login`). The older
addresses (`/reviews`, `/sessions`, `/leaderboard`, `/login`) redirect.

- **Dashboard** (`/admin/dashboard`) — submission counts by state, active
  sessions and recent submissions, all from the API.
- **Sessions** (`/admin/sessions`, `/create`, `/:id`) — list by status, create
  and edit (SAI admins only), enable/disable, end now, and each session's
  submissions. A session's status is the server's, computed against its own
  clock; the page never decides that a session is active.
- **Submissions** (`/admin/submissions`, `/:id`) — every submission, newest
  first, filtered by status, test, session or region. The detail shows the
  phone's result beside the server's, where verification got to, and the
  integrity flags. Deciding happens in the review queue.
- **Athletes** and **Analytics** — placeholders for later sprints.

The tests offered when creating a session come from `GET /api/dashboard/tests`,
not a list kept in this app.

## What a reviewer sees (Sprint 14)

- **Queue** (`/admin/reviews`) — ordered by highest unresolved flag severity,
  then oldest. Tabs are the server's review states: *needs a decision*
  (flagged, or the server could not verify it), *awaiting approval*, *being
  verified*, and *decided*. Filters: test, session, integrity state, athlete
  (name or ID), submitted dates, and region for SAI admins, all applied by the
  server.
- **Review** (`/admin/reviews/:id`) — the recording through a short-lived
  signed link, with the *server's* skeleton drawn over it. Beside it: phone vs
  server results for that exercise (reps, or a measurement for the jump),
  whether the allowed difference was exceeded, every integrity flag with its
  measurements, the limit it crossed and a jump to the moment in question, the
  automated verification record (verdict, checks, pipeline version), identity,
  benchmark, and the audit trail.
- **Decision** — *Approve result*, *Reject submission*, *Request
  resubmission*, or *Flag for further review*. Only the actions the server
  allows are offered. Every action but approval needs a reason. Rejections and
  resubmission requests need a note the athlete will see; a flag needs an
  internal note that the athlete never sees. Each action is confirmed in a
  dialog that states its outcome. A result the server could not score can only
  be approved with a score the reviewer enters and explains; the phone's own
  number is never promoted.
- **Conflicts** — a decision carries the review version it was made against.
  If another official acted first, nothing is overwritten: the reviewer is
  told, and the page shows the current decision.
- **Unsaved work** — leaving the page with a half-written decision asks first.
- **Leaderboard** — approved results only, best attempt per athlete.

## Rules enforced by the server, not this UI

Region scoping, allowed actions, and every validation above are enforced by the
API. The UI mirrors them for a better experience; it is not what protects them.

Sign-in tokens live in `sessionStorage` so a refresh token for an account that
approves children's results does not outlive the tab on a shared office machine.
Signing out also revokes the refresh token on the server.
