# Sprint 9 — Gamification & UX Polish

## Status

Implementation complete across backend and mobile.

- **Backend:** 244 tests green (22 new), lint clean, migration applies and
  reverses, `alembic check` clean.
- **Mobile:** 151 unit tests green (27 new), APK builds, **Android lint passes
  with zero errors** (and now runs in CI).
- **Not done, and not doable from here:** the Definition of Done needs a
  non-technical person outside the dev circle, a phone, and a throttled
  network. See "What is not proven".

---

## Goal

Athletes want to use this repeatedly, not just once — in their own language, on
the connection they actually have.

---

## Languages

The app now ships in **English, Hindi, Tamil and Bengali**. Every athlete-facing
string moved to resources: 248 of them, including every screen built in Sprints
1–8.

**Language is the first question the app asks**, before a word of English, and
each option is written in its own script (हिन्दी, தமிழ், বাংলা). Someone who cannot
read English has to be able to get past the first screen.

The choice is per-app, not the phone's system language — phones here are often
shared with family. It is stored on the phone and on the athlete's account.

### Things that would have broken silently

- **App Bundle language splitting.** Play installs only the language resources
  matching the phone's *system* language. An athlete with an English phone who
  picked Hindi would have got English, because the Hindi strings were never
  downloaded. Found by Android lint (`AppBundleLocaleChanges`); splitting by
  language is now disabled.
- **Codes and diagnostics leaking through.** The server sends
  `score_discrepancy`, the analyzers produce English messages like "Start
  position never detected — lie back fully before starting", upload failures
  store English errors. None of it reaches the athlete any more. `Labels` maps
  every code to a translated string, and the analyzer's raw event log
  (`rep_rejected_partial — Reached 82 deg`) became a plain summary: "Not
  counted, did not sit all the way up: 2".
- **Routes keyed on display names.** Navigation passed "Sit-ups" and looked the
  test up by that name. With translated names that lookup fails. Routes now
  carry the enum name.
- **English-only formatting.** "51st percentile" has no equivalent in these
  languages; the phrasing is now "Around percentile 51". Counts use a neutral
  "Waiting to send: 3" form instead of English plurals.

### Guarded by tests

- `TranslationCompletenessTest` — every language has exactly the English keys,
  **the same format arguments** (a `%1$d` translated as `%1$s` crashes that
  screen in that language only), and nothing left as copied English.
- `LabelsTest` — reads the analyzer **source files** and fails if any reason
  message there has no translation, so a new analyzer message cannot reach an
  athlete in English.

### Translation quality

The Hindi, Tamil and Bengali strings were written for this app, not produced by
a certified translator. **They must be reviewed by native speakers before
release** — ideally coaches who talk to young athletes daily. State and union
territory names stay in their official English form, matching what reviewers
see on the dashboard.

---

## Badges

Seven badges: first test, first result checked by SAI, personal best, all-rounder
(a checked result in every test), and 2-, 4- and 8-week streaks.

**Computed, never stored.** A stored badge outlives its evidence: an official
rejects a looped video a week later and the athlete still holds "personal best"
for a score that never happened. Computing from results means a rejection
removes the badge with no extra code.

**Effort and achievement count differently.**
- First test and streaks count any submission not rejected — an athlete who
  tested on Sunday deserves the streak before SAI finishes checking.
- Personal best, checked-by-SAI and all-rounder count **only verified or
  approved scores**. The phone's provisional number never earns an achievement.

**Weeks are Indian weeks.** A test at 12:30am Monday in IST is still Sunday in
UTC; counting it as Sunday would break an honest streak. And the current streak
is not shown as broken on Monday morning before the athlete has had a chance to
test that week.

Unearned badges are shown with progress ("Progress: 2 of 4") — an empty screen
tells a new athlete nothing.

---

## Leaderboards and privacy

- **Off by default.** Most athletes are minors; nobody is made public by a
  default or by a migration.
- **Minimal identity when on:** first name, last initial, state. No full name,
  no age, no id — together those identify a child.
- **You always see yourself.** The athlete's own rank is returned whether or not
  they opted in, computed among visible athletes plus themselves. Seeing where
  you stand does not require being seen, and the screen says which applies.
- **Fair comparison:** approved results only, best attempt per athlete, within
  the athlete's age band and gender, in their state or across India.
- Athletes registered as "other" see an all-genders board for their age band.
  Which board they belong on is SAI's policy to set; this is a placeholder, not
  a decision.
- The **officials'** leaderboard is unaffected: officials see every approved
  result, because spotting talent is their job.

Settings has the visibility switch, with plain explanations of both states and a
reminder that videos and photos are only ever seen by SAI officials, whatever is
chosen.

---

## Onboarding and instructions

**Onboarding:** language, then three pages — what the app is for, that the
phone's score is not the official one, and who sees the videos.

**Instructions before every test**, with diagrams (vector drawables, tinted for
dark mode):
- camera setup — distance, steadiness, side-on, light — shown for every test,
  because setup is the most common reason an honest attempt goes unscored;
- start and top positions for sit-ups, stand and jump for the vertical jump;
- numbered steps and exactly what counts.

**Instructional videos were not made.** They need filming real athletes. The
diagrams and steps cover the same ground; videos are future work.

---

## Accessibility

- Screen titles and section titles are semantic **headings**, so TalkBack users
  can jump between them.
- **Errors are live regions**, announced when they appear.
- The **live rep count** and the **countdown** are live regions — an athlete
  mid-sit-up cannot look at the screen, but can hear it.
- Badge and leaderboard rows read as **one announcement** ("Position 2, Asha P.,
  Kerala, 44 reps") instead of four separate stops.
- The leaderboard visibility setting is one large **toggleable row** with the
  switch role, not a small switch beside text.
- Radio groups for language choice use proper `selectable` semantics.
- Diagrams have translated **content descriptions**; decorative images are
  marked decorative.
- Text uses theme typography in `sp`, and the screens scroll, so large font
  sizes do not clip content.
- Result history cards name their action ("Open result") for screen readers.

---

## Low bandwidth

- Every network call has **bounded timeouts** (30s connect, 60s read) — patient
  enough for 2G, never infinite. A test pins that they stay in that range.
- `Sprint9ApiTest` runs a response **throttled to ~1 KB/s** and asserts it
  completes, and a **stalled** connection and asserts it fails as a network
  error within the timeout rather than hanging.
- Every screen that loads data shows a spinner, then either the content or a
  translated message with **Try again** — never an indefinite spinner.
- Upload queue states ("Waiting for a connection", "Sending to SAI…") were
  already in place from Sprint 4 and are now translated.

To throttle manually on an emulator:

```bash
emulator -avd <name> -netspeed gsm -netdelay gprs
# or at runtime from the emulator console:
network speed edge
network delay gprs
```

---

## Lint fixes along the way

Running Android lint for the accessibility pass also turned up pre-existing
problems, now fixed:

- Media3 Transformer's unstable API was used without opt-in (Sprint 4) — lint
  **errors** that would have failed any CI that ran lint.
- The camera was not declared as a required feature, so stores could offer the
  app to devices it cannot work on.
- The jump score was formatted with the device locale; scores now use the same
  digits in every language, matching what officials see.

---

## Sprint 9 Definition of Done

| Criterion | Status |
|---|---|
| Progress badges: first test, personal best, streaks | Done — plus checked-by-SAI and all-rounder |
| Regional / age-group leaderboards visible to athletes | Done |
| Privacy: opt-in for public leaderboard display | Done — off by default, minimal identity |
| Onboarding polish | Done |
| Hindi + 2–3 regional languages | Done — Hindi, Tamil, Bengali; **native review pending** |
| Instructional videos/diagrams for each test | **Diagrams done; videos not made** (need filming) |
| Low-bandwidth testing | Done in automated tests; manual 2G walk not done |
| Accessibility pass | Done |
| **A non-technical user outside the dev circle completes onboarding and a full test cycle without help, in a non-English language, on a throttled connection** | **Not done — needs a real person, a phone and a throttled network** |

---

## What is not proven

- **The DoD itself.** It is a usability test with a real person; nothing
  automated substitutes for watching someone who has never seen the app try to
  use it in Tamil on a bad connection.
- **Translation quality.** Written carefully, not reviewed by native speakers.
- **Screen-reader behaviour on a device.** The semantics are in place and
  compile; no one has walked the app with TalkBack from this machine.
- **The screens on a phone at all** — as in Sprints 7 and 8, the mobile app
  builds, lints clean and its logic is tested, but has not been run on a device
  here.
