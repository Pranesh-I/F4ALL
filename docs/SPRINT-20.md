# Sprint 20 — Security & Privacy

## Status

Authentication, the encrypted face photo, consent with a guardian for minors,
the private bucket with signed links and the review audit trail were already
in place from earlier sprints. This sprint reviewed all of it against the
plan's list and added what was missing:
- rate limits;
- an access log;
- retention;
- HTTPS-only release builds;
- the scans.

Every finding is tracked to closure in
[SECURITY-REVIEW.md](SECURITY-REVIEW.md). The data inventory, its purposes,
who sees it and how long it is kept are in [PRIVACY.md](PRIVACY.md).

- **Backend:** 923 tests green (162 new since Sprint 18's 761); ruff clean;
  secret scan clean; pip-audit 0 findings.
- **Mobile:** 367 unit tests green (4 new), 1 skipped. Android lint: 0 errors,
  67 warnings (baseline unchanged). The release APK builds.
- **Dashboard:** 131 tests green (1 new); vitest upgraded 3 → 5; typecheck and
  build OK.
- **Migrations:** upgrade, `alembic check`, downgrade and upgrade again, on
  SQLite and on a scratch Postgres 16.
- **Not doable from here:** checking SAI's real bucket (`check-storage`
  exists, for them to run), a real TLS certificate and domain, and an
  independent security review. All are on the deferred list.

---

## What was added

| Plan item | Done |
|---|---|
| **HTTPS / TLS** | The release app refused nothing: `usesCleartextTraffic="true"`, with a changeable `http://` server address. Now a network security config refuses HTTP and user CAs, and the server address is fixed at build time (`-Pf4all.apiUrl=https://…`; the build fails on anything else). Debug builds keep HTTP for a laptop server. Production also requires `sslmode=require` on Postgres |
| **Rate limiting** | `app/rate_limit.py`: per IP and per verified account, in one-minute windows. Sign-in 10, OTP 30, uploads and media 1200, everything else 300 (all settings). 429 with `Retry-After`. Counted in Redis in production (enforced), so all workers share one count |
| **Audit logs** | New `access_log` table. Every officials' request is logged: dashboard, an official's verification read, signed media. Each entry has who (from a verified token), path, query, outcome, IP and request id, refused attempts included. Every sign-in attempt is logged too; an unknown address only as a hash, and never the password. SAI admins search it at `GET /api/dashboard/access-log` |
| **Data retention** | `python -m app.cli retention [--dry-run]`, daily (a `retention` service in docker-compose). It deletes decided videos, landmarks, field traces and their GPS points a year after the decision; unreferenced identity checks after 90 days; notifications after a year; OTPs after 30 days; dead tokens after 30 days; stale uploads after 7 days; the access log after 2 years. Results, decisions and consents are never touched. On the dashboard, a retired recording says so |
| **Private bucket, encryption** | Already SSE-AES256 and signed-only. New: `python -m app.cli check-storage` checks the real bucket's public access block and default encryption. It also checks that versioning does not keep "deleted" videos |
| **Input validation** | A sweep feeds every API operation broken ids (including path traversal and 600-character strings) and broken bodies (invalid JSON, wrong types, 10 KB strings). All are answered with a 4xx; none with a 500 |
| **Role-based access** | Tests generated from the OpenAPI document cover four rules. Officials' endpoints refuse the anonymous, athletes and forged tokens. Athlete endpoints refuse the anonymous. One athlete cannot reach another's result. Reviewers stay in their region, and only admins read the access log or change sessions |
| **Information leak** | `GET /api/results/{id}` showed the athlete which automatic integrity check had fired. It now carries no flags, as `/api/verification` already did |
| **Secrets** | `scripts/scan_secrets.py` scans every file git would commit, and CI runs it. The repository is clean |
| **Dependency scanning** | CI `security` job: pip-audit (0 findings) and `npm audit --audit-level=high`. The dashboard's 4 findings were all in dev tooling, now fixed |
| **Production hardening** | `/docs`, `/redoc` and `/openapi.json` are not served in production |
| **Consent, minor consent** | Already enforced by the server (Sprint 8); documented in PRIVACY.md |

## Tests

- `test_security.py` (135):
  - who may call what, generated from the OpenAPI document;
  - the malformed-input sweep;
  - rate limits (6);
  - the access log (8).
- `test_retention.py` (10): each period, kept versus deleted, a dry run, a
  storage failure, the CLI, and the bucket checks.
- `test_secret_scan.py` (14): live credentials found without printing them,
  placeholders allowed, and the repository clean.
- `test_backend_foundation.py`: the new production rules (`DATABASE_URL`
  TLS, the Redis rate limit, no docs).
- `TransportSecurityTest` (mobile, 4): the release config and manifest, and a
  release build ignoring a stored server address.
- `ReviewPage.test.tsx`: a retired recording is explained.

## Known limits

- The rate limit fails open if Redis is unreachable (accepted, R4). The load
  test in Sprint 23 should confirm the limits suit real traffic. A whole
  school behind one IP address is the case to watch: raise
  `RATE_LIMIT_PER_MINUTE`, or trust the proxy's `X-Forwarded-For`.
- Retention dates a video from the result's last change, not from the moment
  of decision. A later change restarts the year, so this errs on keeping
  longer.
- In S3 production, video playback is logged at the moment the link is
  issued, not when it is played (R5).
- Account deletion on request is an SAI process, not yet a feature.
