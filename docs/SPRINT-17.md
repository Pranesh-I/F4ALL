# Sprint 17 — Notifications

## Status

Athletes are told when a session opens or is about to close, and when their
submission is received, checked, flagged, approved, rejected or sent back. Each
notification is in the athlete's own language, goes only to their account, and
opens the screen it is about.

- **Backend:** 706 tests green (86 new in `tests/test_notifications.py`, 2 new
  production-settings cases), ruff clean, `docs/openapi.yaml` regenerated. **One
  migration** (`e4b19c7d2f63`). It upgrades, passes `alembic check`, downgrades
  and re-upgrades on SQLite and on **Postgres 16**.
- **Live run on Postgres, 13/13 checks:** the real API (uvicorn), a shared
  phone's token moving between two athletes, an admin opening a session over
  HTTP, an official approving a result through the real review endpoint, the
  notifier as its own process, and the athlete's inbox read over HTTP (below).
- **Mobile:** 343 unit tests green, 1 skipped (the reference-video harness);
  9 new. Android lint: 0 errors, no new warnings.
- **Not done here:** a notification has not been seen on a real phone. The app
  builds, and every rule it applies is unit-tested.

---

## How it works

```text
API / Celery worker / dashboard          (unchanged: they never call the notifier)
        │ change rows
        ▼
     Postgres ──► notifier (python -m app.notifications.dispatch)
                    1. find events in the database
                    2. store them: notifications table, unique dedupe_key
                    3. deliver: push provider, else the inbox
        ▼
  athlete's inbox  ◄── phone checks it (WorkManager, every 15 min) and shows
  GET /api/athletes/me/notifications     new ones as Android notifications
```

**Decoupled.** Notifications are found by reading the database, not by
calls placed in the submission, verification or review code (the plan asks
for exactly this: "Do not place notification logic directly inside every
controller"). If the notifier is down, assessment carries on, and the events it
missed are found when it comes back: its position is stored in the database.
A test checks that nothing in the assessment path imports the notifier.

**The eight events.**

| Event | When | Dedupe key |
|---|---|---|
| Session opened | an enabled session is inside its window, for each athlete it is open to (its region, or everyone) who still has tests to record | `session_opened:{session}:{athlete}` |
| Session ending soon | 24 hours before the close, or halfway through a shorter session, for those who still have tests to record | `session_ending:{session}:{athlete}` |
| Submission received | a result is `uploaded` / `processing` | `received:{result}` |
| Verification completed | `verified` | `verified:{result}` |
| Submission flagged | `flagged` — worded as "being reviewed", never naming the check | `flagged:{result}` |
| Approved | `approved` | `approved:{result}` |
| Rejected | `rejected` | `rejected:{result}` |
| Resubmission requested | `pending_sync` | `resubmission:{result}` |

A result is described by its *current* status: one checked before the notifier
looked yields "checked by SAI", not also "received". On its first run the
notifier looks back one hour only, so history is not sent as news.

**The record** (`notifications`) holds what the plan lists: id, recipient,
event type, title, body, created, sent, delivery status, read — plus the data
the app needs to open the right screen, the language, attempts, next attempt
and last error. `title`/`body` are rendered on the server in the athlete's
language (`app/notifications/messages.py`, 4 languages).

## Reliability (the plan's list)

| Case | Handling |
|---|---|
| Duplicate events | unique `dedupe_key`; finding an event twice stores it once; a second notifier racing the first skips the row |
| Provider failure | `retrying` with backoff (30 s doubling, capped at 30 min), `failed` after 5 attempts. Always still in the inbox |
| Provider crash | caught per notification; the others in the batch are still delivered |
| Invalid / expired token | FCM `UNREGISTERED`, `SENDER_ID_MISMATCH`, or `INVALID_ARGUMENT` about the token: the token is disabled and never used again. `INVALID_ARGUMENT` about the message is *not* treated as a dead token: a bug in our payload must not switch off every athlete's phone |
| Expired access token | FCM's 401 drops the cached OAuth token; the next send fetches a new one |
| No device | delivered to the inbox (`inbox`) |
| Notifier crash | the loop logs it and tries again next pass |

## Provider

`NOTIFICATION_PROVIDER`:

- `console` — logs each push (development);
- `fcm` — Firebase Cloud Messaging, HTTP v1, as a service account (a JWT
  signed with its key, exchanged for an OAuth token). Implemented and tested
  against a fake FCM and token endpoint;
- `inbox` — no pushes; phones read the inbox.

Production refuses `console`. `fcm` needs `FCM_PROJECT_ID` and
`FCM_SERVICE_ACCOUNT_FILE`.

**What is missing for real pushes:** a Firebase project, which SAI has to own,
like the SMS gateway. Until it exists the phone does not include the Firebase
SDK, and gets its notifications by checking the inbox (below). When the
project exists, two steps remain: add the Firebase client to the app (with the
project's `google-services.json`), and have it send its token to
`POST /api/athletes/me/devices`, which is built and tested.

## Phone

- **Inbox screen** (Home → Notifications, with the unread count): newest first,
  unread marked with a marigold dot (and said to a screen reader), "Mark all as
  read", "Show older". Tapping one marks it read and opens the result, or the
  sessions on Home.
- **Phone notifications without Firebase:** `NotificationWorker` checks the
  inbox every 15 minutes with a network (WorkManager's minimum), and at
  start-up. New ones are posted as Android notifications — at most 5 at once,
  only from the last 3 days, never the same one twice — on a "Results and
  sessions" channel, hidden on the lock screen.
- **Language:** the app words each event itself, in the athlete's chosen
  language, as every other screen does. A backend test checks the app's
  wording matches the server's push wording in all four languages, and that
  test names match.
- **Shared phones:** the inbox comes from the signed-in athlete's account;
  "already shown" is remembered per athlete; signing out clears every posted
  notification; a push token is moved to whoever registers it last and removed
  on sign-out; a tapped notification opens nothing if nobody is signed in.
- **Permission:** asked once on Android 13+, on the Home screen after signing
  in. A refusal is respected; the inbox still has everything.
- 25 new strings in English, Hindi, Tamil and Bengali.

## Live evidence (Postgres 16)

| Check | |
|---|---|
| Ravi registers the phone's token | PASS |
| Asha signing in on the same phone takes the token over | PASS |
| Admin opens a Kerala session (HTTP) | PASS |
| Official approves Asha's sit-ups (real review endpoint; never waits on notifications) | PASS |
| The notifier runs as its own process and exits cleanly | PASS |
| Two pushes went to the shared phone, both Asha's | PASS |
| Asha's inbox: session opened and approved | PASS |
| …in Hindi ("सिट-अप: मंज़ूर") | PASS |
| …with the result id the app opens | PASS |
| Ravi (Punjab) got nothing | PASS |
| A second pass sends nothing again | PASS |
| Reading one leaves one unread | PASS |
| Signing out removes the phone's token | PASS |

## Definition of Done

- [x] Notification provider integrated — FCM HTTP v1 on the server, tested
  against a fake FCM; inbox delivery to the phone working today. **Real FCM
  pushes need SAI's Firebase project.**
- [x] Required events trigger notifications — all eight.
- [x] Duplicate behaviour controlled — unique keys, tested.
- [x] Invalid tokens handled — disabled, never retried.
- [x] Delivery and read state tracked — `delivery_status`, `sent_at`, `read_at`.
- [x] Failures do not break assessment — separate process; nothing in the
  assessment path imports it; tested and walked live.
- [x] The athlete understands the action — each message says what happened and
  what to do, in their language.

## Known limits

- No Firebase project yet, so no instant pushes: the phone checks every
  15 minutes when it has a network.
- Officials get no notifications; every event is about an athlete.
- Notifications are kept indefinitely; a retention period belongs to the
  Sprint 20 retention job.
- "Session open" goes to every athlete in the session's region who still has
  tests to record, including someone who registers after it opened. That is
  intended, but it means a large region gets a large fan-out in one pass
  (one insert per athlete).
- Not seen on a real phone.
