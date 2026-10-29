# F4ALL — Personal Data: What Is Held, Why, Who Sees It, How Long

The athletes are often children. Everything below was decided with that in
mind. Where a choice was SAI's to make, it is marked **SAI to confirm**.

Every period in the last column is a setting (`RETENTION_*_DAYS`). The daily
job `python -m app.cli retention` applies them
([backend/app/services/retention.py](../backend/app/services/retention.py)),
and `tests/test_retention.py` checks each one.

## What is held

| Data | Why | Where, and how protected | Who can see it | Kept for |
|---|---|---|---|---|
| **Profile:** name, date of birth, gender, state, city/place, height, weight, phone number, achievements, language | Identifies the athlete. Age, gender and region choose the benchmark and leaderboard cohort | Postgres (TLS required in production) | The athlete; officials (reviewers only for their own state) | The life of the account |
| **Consent records:** purpose, version, given by self or guardian, guardian's name, when given or withdrawn | Proof of consent. A minor's is given by a parent or guardian | Postgres | Officials | The life of the account |
| **Registration face photo** | Pre-test identity check, only with consent to face verification | Object storage, encrypted by the app (AES-GCM) and by the bucket. Served only through the API, decrypted per request, on a 15-minute link | Officials reviewing that athlete | Until consent is withdrawn (it is deleted immediately) or the account is deleted |
| **Pre-test identity check:** outcome and similarity score only | Was the person recording the registered athlete? | Postgres. The photo taken is compared in memory and discarded | Officials | 90 days, unless a result refers to it; then as long as the result |
| **Test videos** | Server re-verification and human review | Private bucket, encrypted at rest (SSE-AES256), never public; 15-minute signed links | Officials reviewing that result | **1 year after the result is decided** (approved or rejected); undecided results are kept until decided |
| **Server pose landmarks** (body keypoints from the video) | Lets the reviewer see what the measurement saw | Private bucket | Officials | Deleted with the video |
| **Video fingerprint** (16 frames at 16x16 greyscale) | Recognises the same footage submitted again | Postgres. Too coarse to show a face | Nobody directly; used by the duplicate check | The life of the result |
| **Field-run trace:** motion sensor samples; for the endurance run, GPS positions | Server re-scoring of shuttle and endurance runs | Private bucket (trace); Postgres (up to 300 GPS points for the reviewer's map) | Officials | **1 year after the result is decided.** The trace and the GPS points both go; the measured splits and its hash stay |
| **Results:** scores (device, server, final), status, flags, review decisions and notes | The athlete's record; the reason a score can be trusted | Postgres | The athlete sees scores, status and the decision with its note. **Not the flags**, which describe the cheat checks. Officials see everything | The life of the account |
| **Practice attempts** | The athlete's own progress | Postgres | The athlete only. Nothing official reads them | The life of the account |
| **Notifications** | Telling the athlete their result is ready, decided, or needs re-recording | Postgres | The athlete | 1 year |
| **Push device tokens** | Delivering notifications to the phone | Postgres | Nobody (system use) | Removed at sign-out; deleted a month after they stop working |
| **OTP challenges:** phone, keyed hash of the code, attempts | Sign-in; evidence if an account is attacked | Postgres. The code is never stored in plain text | Nobody (system use) | 30 days |
| **Refresh tokens** (hash only) | Keeps the athlete signed in on patchy networks | Postgres | Nobody (system use) | A month after they expire or are revoked |
| **Upload sessions** and partial uploads | Resumable upload of a video | Server disk (staging) | Nobody | A week after they expire |
| **Officials:** name, email, role, region, password (scrypt hash) | Dashboard sign-in and region scope | Postgres | SAI admins | While the account is active |
| **Access log:** who (official), what (path), when, outcome, IP address | Accountability for every read and change of athlete data on the officials' side | Postgres | SAI admins (`GET /api/dashboard/access-log`) | **2 years**, longer than the data it audits |

Not collected: contacts, photos other than the two above, background location,
advertising identifiers. The phone's location is read only while an endurance
run is being recorded, and only for that run.

## On the phone

- Data belongs to the account, not the phone. History comes from the server
  for whoever is signed in, and everything kept on the phone is tagged with
  its athlete and shown only to them. On a shared phone, another athlete never
  sees it. Signing out also clears this athlete's notifications from the
  phone's notification shade.
- A release build talks only to SAI's HTTPS server. Plain HTTP is refused by
  the network security config, and so are certificates the user installed.
  The server address is fixed when the app is built and cannot be changed in
  the app.
- Videos waiting to be sent sit in the app's private storage and are deleted
  once the upload is complete.

## Consent

- **Registration** needs consent to hold the profile and results. For anyone
  under 18, that consent must come from a parent or guardian, whose name is
  recorded. The server refuses a minor's registration without it.
- **Face verification** is a separate consent. An athlete may refuse it and
  still practise. Withdrawing it in the app deletes the photo at once.
- Withdrawing consent to hold the profile means deleting the account. That is
  done by SAI on request, because results may be part of a selection decision
  already made. **SAI to confirm** the process and its time limit.

## Who sees what

- **The athlete:** their profile, results (scores, status, decision and its
  note), history, personal bests and notifications. Never another athlete's
  records. The athlete leaderboard shows other athletes only if they opted in.
- **Regional reviewer:** submissions, videos and photos for their own state
  only.
- **SAI admin:** everything, including the access log.
- Every request on the officials' side is in the access log, including refused
  ones. So is every sign-in attempt; for an unknown address only a hash of it
  is kept, and never the password.

## Open with SAI

- **SAI to confirm** each period above, especially videos (1 year after
  decision) and how long results and profiles outlive an inactive account.
- **SAI to confirm** the account deletion process, and who answers an
  athlete's or guardian's request for their data.
- The privacy notice shown in the app should be written from this document
  and reviewed by SAI's legal team (DPDP Act 2023: notice, consent, verifiable
  guardian consent for children, the right to erasure).
