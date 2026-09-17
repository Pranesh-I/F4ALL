# Sprint 7 — Auth, Athlete Profiles & Benchmarking

## Status

Implementation complete across backend and mobile.

- **Backend:** OTP login, rotating refresh tokens, registration, profile, photo,
  history, benchmarking. Part of the 222-test backend suite, lint clean, both
  migrations apply and reverse, `alembic check` reports no drift.
- **Mobile:** login, registration, registration photo, profile and result
  screens, encrypted token storage, authenticated sync, and the submit call the
  app never made. 136 unit tests green; Room migration v1→v2 with an
  instrumented test (compiles; needs a device to run).
- **Live:** the whole journey walked over real HTTP against a running server —
  see "Verified end to end" below.

---

## Goal

Athletes have real accounts, and their scores mean something against age and
gender norms.

---

## The benchmark problem, stated plainly

The brief says to encode SAI's published benchmarks. **There are none for the
two tests this app scores.** The Khelo India 9–18 battery measures a 600m run,
50m dash, sit-and-reach, push-ups and *partial curl-ups* — no vertical jump, and
not the classic sit-up.

Inventing numbers and labelling them SAI norms would have a government platform
misinform a teenager about themselves. So:

- The engine is built properly — cohort lookup, band, percentile, next target.
- It ships with `app/data/benchmarks_provisional.csv`, whose header explains why
  the numbers are placeholders.
- Every `benchmarks` row carries a `source`. Anything not declaring an official
  source — including a blank one — is `provisional: true` in the API.
- The phone and the dashboard both show the caveat, every time.
- `python -m app.cli seed-benchmarks --file official.csv --replace` swaps in a
  real table and the caveat disappears with no code change.

**No percentile below the median.** The table stores p50/p75/p90. Between those
anchors interpolation is defensible; below p50 nothing describes the shape of
the distribution, so a score there could be the 49th percentile or the 5th. The
API returns `percentile: null` and a band ("Keep training — you're building
towards the average") plus the next target. An athlete always gets a true
statement.

**Gender `other` gets no cohort.** Assigning a non-binary athlete to the male or
female table is a selection-policy decision with real consequences. It belongs
to SAI, not to a default in a lookup function. The API says no cohort is defined.

**Timed tests compare the other way.** `tests.higher_is_better` is a column, not
a caller's argument, and the seeder validates percentile order in each test's
own direction. Sprint 10's runs will need it; getting it backwards tells the
fastest athletes they are the slowest.

---

## Authentication

### OTP

- Codes are HMAC-SHA256'd with the server secret, salted by phone. The hash is
  not what protects a six-digit code — **five guesses per code** and a
  **five-minute expiry** are, both enforced in the database.
- Resend cooldown 60s, five requests an hour per phone, `429` with `Retry-After`.
- Requesting a new code retires the old one.
- `request-otp` answers identically for registered and unregistered numbers, so
  it cannot be used to test whether someone has an account.
- Phone numbers are normalised (`+91 98765 43210` ≡ `9876543210`), otherwise the
  per-phone limit could be walked around by adding a space.
- SMS sits behind an interface like storage: console in development, HTTP
  gateway in production, and production refuses to start OTP delivery without
  one. Indian transactional SMS needs a DLT-registered sender and template,
  which SAI holds; the HTTP sender is a URL and payload template, not a vendor
  SDK.

### Tokens

- Access tokens: 60 minutes. Refresh tokens: 90 days, stored as SHA-256,
  **rotated on every use**.
- Presenting a rotated refresh token revokes the subject's whole session chain.
  Two parties hold it and there is no way to tell which is the athlete; one
  forced login is a far smaller harm than an attacker with a session on a
  minor's account.
- Logout can revoke every device — phones here are shared, sold and lost.

### Registration

A verified phone with no profile receives a `registering` token. Only
`/api/athletes/register` accepts it, and the phone is taken **from the token,
never the body** — otherwise a token for one number could create an account on
another.

---

## Bugs found

**Registration left the phone holding a useless session.** The registering
token's subject is the phone, not the new athlete. Every request after
registering — the photo upload, the first video — would have failed as "unknown
athlete", and refreshing could not fix it because rotation preserves the
subject. Registration now revokes the registering session and returns athlete
tokens. `test_registration_hands_back_a_session_that_actually_works` walks it.

**`/api/tests/submit` was not idempotent.** A phone that loses the response to a
committed submission retries; that created a second attempt and verified the
same video twice. It now returns the existing result for a video already
submitted.

**Any athlete could submit anyone's video.** No ownership check on `video_id`.
Now checked against the upload session, answering exactly as for an unknown id.

**The phone never submitted anything.** Sprint 4 uploaded the video and stored
`video_id`, leaving the submit call for later. Without it no result exists on
the server, nothing is verified, and there is nothing to benchmark. The sync
worker now submits after upload, and keeps retrying uploaded-but-unsubmitted
tests (`result_id IS NULL`) until the server has them.

**Region was free text.** Region scopes a reviewer's queue; "Tamilnadu" would
make an athlete invisible to the official responsible for them. It is now a
fixed list of states and UTs, canonicalised on the server, mirrored on the
phone, and a backend test fails if the two lists drift.

**`is_provisional("")` returned False.** A row with no provenance would have been
presented as official. Fixed and tested.

---

## Mobile

| Piece | Notes |
|---|---|
| `F4allApi` | Pure JVM (OkHttp + org.json), tested against MockWebServer |
| `AuthSession` | Refreshes 2 minutes before expiry; refreshes once for concurrent callers; **a network failure never logs the athlete out** — only a 401 on refresh does. Two locks so the UI never waits on a refresh |
| `EncryptedTokenStorage` | EncryptedSharedPreferences, Keystore key, `commit()` so a rotated token survives process death; excluded from backup and device transfer (a restored ciphertext is undecryptable) |
| `AppServices` | One session per process — two would each refresh and trip reuse detection |
| Sync worker | Waits (does not fail) while signed out; authenticated upload; submits; `SubmissionPolicy` decides retry vs refused |
| Room v2 | `result_id` column, real migration, schema `2.json` exported, instrumented `MigrationTest` |
| Screens | Login (phone → code, resend countdown), registration (date picker, region list, required height), registration photo (downscaled to 1024px, EXIF rotation applied, original deleted), profile (bests marked official or not), result (machine score never labelled official; provisional benchmark caveat always shown), sync rows link to the SAI result |

---

## Verified end to end

Against a live server with a real file database, through all three migrations:

```
PASS  request OTP
PASS  immediate resend is rate limited with Retry-After
PASS  wrong OTP refused
PASS  verify OTP -> unregistered session
PASS  register
PASS  athlete token works immediately after registering
PASS  registering refresh token revoked
PASS  registration photo upload
PASS  upload init
PASS  upload complete, checksum verified
PASS  submit is idempotent on retry
PASS  unverified result is not benchmarked
PASS  verification pipeline ran
  ... (Sprint 8 checks) ...
PASS  athlete sees approval, official score and review
PASS  athlete sees a PROVISIONAL-labelled benchmark
PASS  official personal best
```

---

## Sprint 7 Definition of Done

| Criterion | Status |
|---|---|
| OTP auth backend, rate limiting, expiry | Done |
| JWT issuance + refresh | Done — rotating, reuse-detecting |
| Mobile auth flow + encrypted token storage | Done |
| Registration: name, DOB, gender, region, height, weight | Done |
| Registration photo capture | Done — feeds Sprint 6's identity check |
| Research SAI benchmarks and encode | **Researched: none exist for these tests.** Engine done; provisional table loaded and labelled as such |
| Benchmark seed script | Done — `seed-benchmarks`, idempotent, validated |
| Benchmark comparison service | Done |
| Feedback UI vs cohort | Done |
| Profile screen: history, personal bests, benchmark | Done |
| **A registered athlete completes a test and sees an accurate benchmark comparison** | **Done over HTTP with the real pipeline — the comparison is accurate to the loaded table, and that table is honestly labelled provisional** |

---

## What is not proven

- **On a phone.** The mobile code compiles, and every rule in it is unit-tested,
  but the screens have not been walked on a device from this machine.
- **SMS delivery.** No gateway or DLT registration exists; production OTP cannot
  be sent until SAI provides both.
- **The benchmark numbers.** Placeholders by design, until SAI supplies norms.
