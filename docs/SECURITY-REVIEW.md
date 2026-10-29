# F4ALL — Security Review (Sprint 20)

An internal review against the plan's Sprint 20 list, done 2026-10-13 on the
code in this repository. Each finding is either **fixed** (with the test that
now holds it) or **accepted**, with the reason. This is not a substitute for
an independent review before the pilot; that is on the deferred list in
`TASK.md`.

## Summary

| Area | State |
|---|---|
| HTTPS / TLS | App: HTTPS only in release builds (fixed). Database: TLS required in production (fixed). Redis: accepted, see R2 |
| Encrypted storage | Bucket SSE-AES256 on every object; face photos also encrypted by the app (AES-GCM). `check-storage` verifies the bucket default |
| Private bucket, signed URLs | No public ACL anywhere. 15-minute presigned links. `check-storage` verifies the public access block |
| JWT / session security | HS256, 60-minute access tokens, 90-day refresh tokens stored as hashes, rotated on use, revocable; secret 32+ characters enforced in production |
| Password / OTP abuse | Officials: scrypt, lockout after 5 failures for 15 minutes. OTP: 5 tries per code, 5 codes per window, HMAC-stored. **Plus rate limits (new)** |
| Rate limiting | New: per IP and per verified account, Redis-backed in production |
| Input validation | Pydantic on every body; typed path parameters. New sweep test: no malformed input reaches a 500 |
| Secrets | New: secret scanner in CI. Production refuses to start with development secrets |
| Dependency scanning | New CI job. pip-audit: none. npm audit: 4 in dev tooling, fixed (F7) |
| Audit logs | Review decisions were already audited (Sprint 14). New: an access log of every officials' request and every sign-in |
| Data retention | New daily job; periods in [PRIVACY.md](PRIVACY.md) |
| Consent | Registration and face verification, separately; guardian consent for under-18s, enforced by the server (Sprint 8) |
| Role-based access | Athlete / regional reviewer / SAI admin. New test: generated from the OpenAPI document, so a new endpoint is covered automatically |

## Findings

### Fixed

| # | Finding | Severity | Fix | Held by |
|---|---|---|---|---|
| F1 | The release app allowed plain HTTP (`usesCleartextTraffic="true"`) and the server address could be changed to any `http://` URL | High | A network security config refuses cleartext traffic and user-installed CAs. The release server is fixed at build time (`f4all.apiUrl`, must be `https://`); a stored override is ignored outside debug builds | `TransportSecurityTest` (4 tests) |
| F2 | No rate limits: sign-in, OTP and every endpoint could be hammered from many addresses, and account lockout alone lets one attacker lock every official out | High | `RateLimitMiddleware`: per IP and per verified account, small allowances for sign-in and OTP, 429 with `Retry-After`. A forged token cannot spend someone else's allowance. `X-Forwarded-For` is trusted only when configured. Production requires the Redis backend | `test_security.py` (6 tests) |
| F3 | `GET /api/results/{id}` returned automatic flags (reason and detail, e.g. which integrity check fired) to the athlete. `/api/verification` already withheld them | Medium | The athlete's result always carries an empty `flags` list. The field is kept so older apps still parse the response | `test_submit_and_results`, `test_review_workflow`, `test_anti_cheat` |
| F4 | No record of who viewed athlete data on the dashboard; only review decisions were audited | Medium | `access_log`: every officials' request (actor from a verified token, path, query, outcome, IP, request id), including refused ones; every sign-in attempt. Signed media links are logged without the link. Admin-only search endpoint | `test_security.py` (8 tests) |
| F5 | Postgres connections were not required to use TLS | Medium | Production refuses to start unless `DATABASE_URL` has `sslmode=require`, `verify-ca` or `verify-full` | `test_backend_foundation` |
| F6 | No retention: videos, identity checks, notifications, OTPs, tokens and partial uploads were kept forever | Medium | `python -m app.cli retention`, daily. Decided videos and GPS traces go after a year, while the result and audit trail stay | `test_retention.py` (10 tests), run on Postgres too |
| F7 | npm audit: tinypool (critical, via vitest 3), source-map-js (high), 4 in all. All in dev or build tooling; the shipped bundle had none (`npm audit --omit=dev`: 0) | Low | vitest 3 → 5, `npm audit fix`. Typecheck, 130 tests and the build pass. 0 vulnerabilities | CI `security` job |
| F8 | Swagger UI, ReDoc and the OpenAPI document were served in production | Low | Not served when `ENVIRONMENT=production`. The contract is published as `docs/openapi.yaml` | `test_backend_foundation` |
| F9 | The hand-written list of admin endpoints in the authorization tests missed the newer ones (`/analytics`, `/access-log`) | Low | The new authorization tests are generated from the OpenAPI document | `test_security.py::test_the_lists_cover_the_api` |
| F10 | Nothing stopped a credential being committed | Low | `scripts/scan_secrets.py` in CI. The repository is clean today | `test_secret_scan.py` |

### Accepted

| # | Risk | Why accepted | Revisit |
|---|---|---|---|
| R1 | HS256 (shared secret), not RS256 | One service signs and verifies. The secret is 32+ characters, enforced in production | When a second service must verify tokens |
| R2 | Redis without TLS | It carries Celery job ids and rate-limit counters, no personal data. It runs on the private network beside the API | If Redis moves off the private network |
| R3 | Access tokens cannot be withdrawn for up to 60 minutes | Refresh tokens are revoked at once; the access-token lifetime is kept short for this reason | — |
| R4 | The rate limit fails open if Redis is down | A monitoring outage must not take the API down. The lockout and OTP limits in the database still apply | Load test, Sprint 23 |
| R5 | In S3 production, a reviewer's video playback goes straight to S3 and does not reach the API | The review-detail request that issues the signed link is logged, with the reviewer and the result id. S3 server access logging covers the rest, if SAI turns it on | SAI's AWS account setup |
| R6 | The Android debug build allows HTTP and user CAs | Debug builds only (`src/debug`), for a developer's laptop server. They never ship | — |
| R7 | No certificate pinning in the app | Pinning to a certificate SAI has not issued yet would brick the app on rotation. Release builds trust only system CAs | Once SAI's domain and CA are fixed |
| R8 | Retained video rows keep the 16x16 frame fingerprint | Too coarse to identify anyone, and still needed to catch resubmitted footage | — |

## Checks that run on every change

- Backend: `test_security.py` covers who may call what, malformed input, rate
  limits and the access log. Also `test_retention.py`, `test_secret_scan.py`
  and the production-settings tests.
- Mobile: `TransportSecurityTest`.
- CI `security` job: the secret scan, `pip-audit -r requirements.txt` and
  `npm audit --audit-level=high`.

## Needs SAI's infrastructure (cannot be checked from the repository)

- Run `python -m app.cli check-storage` against the real bucket. It checks the
  public access block, default encryption, and that versioning does not keep
  deleted videos. A lifecycle rule must expire noncurrent versions.
- A TLS certificate on the API's domain; HSTS at the load balancer.
- Postgres with TLS and encrypted storage; backups encrypted and covered by
  the same retention.
- Secrets (`JWT_SECRET`, `IDENTITY_ENCRYPTION_KEY`, database, S3, FCM) held in
  a secrets manager, not in files on the host.
- An independent penetration test before the pilot.
