# SAI Talent Assessment — Official Dashboard

Where SAI officials review submissions the server has verified or flagged, and
approve, reject or ask for a resubmission. React + TypeScript + Vite, Tailwind,
React Query.

## Run it

```bash
# backend on :8000 first (see backend/README.md), with an official account:
#   python -m app.cli create-official --email you@sai.example --name "You" --role sai_admin

cd dashboard
npm install
npm run dev            # http://localhost:5173, /api proxied to :8000
```

A backend on another port: `F4ALL_API_URL=http://localhost:8010 npm run dev`.

A production build talking to a separately deployed API sets
`VITE_API_BASE_URL` at build time; that API must list this origin in
`CORS_ORIGINS`.

```bash
npm run typecheck
npm test               # vitest + Testing Library
npm run build
```

## What a reviewer sees

- **Queue** — ordered by highest unresolved flag severity, then oldest. Tabs for
  work needing review, results awaiting approval, and decided results.
- **Review** — the recording with the *server's* skeleton drawn over it, phone
  vs server vs official scores, every flag with its severity and a jump-to
  timestamp, the registration photo beside the face-check outcome, the
  benchmark (marked provisional), and the audit trail.
- **Decision** — approve, reject, or ask to record again. Rejections and
  resubmission requests require a note, because the athlete sees it. A result
  the server could not score can only be approved with a score the reviewer
  enters and explains; the phone's own number is never promoted.
- **Leaderboard** — approved results only, best attempt per athlete.

## Rules enforced by the server, not this UI

Region scoping, allowed actions, and every validation above are enforced by the
API. The UI mirrors them for a better experience; it is not what protects them.

Sessions live in `sessionStorage` so a refresh token for an account that
approves children's results does not outlive the tab on a shared office machine.
