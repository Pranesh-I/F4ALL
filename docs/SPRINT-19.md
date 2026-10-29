# Sprint 19 — UX, Languages & Accessibility

## Status

Most of this sprint's ground was already covered by earlier work:
- English, Hindi, Tamil and Bengali, with a translation-completeness test
  (README Sprint 9);
- an accessibility pass (README Sprint 9);
- the Trials Day design.

This sprint closed the gaps against the plan. It also turned what had only
been claimed into checks that run on every build.

- **Mobile:** 363 unit tests green (5 new), 1 skipped (the reference-video
  harness). Android lint: 0 errors, no new warnings.
- **Dashboard:** 130 tests green, production build OK (one colour token
  changed).
- **Backend:** unchanged this sprint (761 green at the end of Sprint 18).
- **Not done here, and not doable from here:** a non-technical user completing
  the flow without help, screen-reader and 200% text walks on a real phone, and
  a native-speaker review of the translations. All three are on the deferred
  list in `TASK.md`.

---

## What was added

| Plan item | Done |
|---|---|
| **Progress indicator** through the official flow | `FlowProgress`: photo check → hand signal → countdown → test → sent to SAI (field runs skip the hand signal). Shown on the photo check, the camera screen (follows its phase), the results slip and the field-run screen; read aloud as one sentence ("Step 2/5 · Hand signal") |
| **Exercise demonstrations** | A looping demonstration on every camera test's instructions: the start and end positions, cross-fading. It is skipped when the phone's animations are switched off; the same two diagrams stay below, side by side |
| **Instructions for every test**, all eight points | Points 7 (how it ends) and 8 (what happens to the result) were missing and are added to every test. Practice and official tests have different result texts. Field runs gained the result step too. A test checks every test has all eight |
| **Offline / upload status understandable** | Official field runs recorded offline were invisible to the Home "waiting to send" count and the Sync screen. Both now include them, with their own wording ("Saved on this phone — it will be sent to SAI when you have signal"), where before they said "Video sent", which was wrong for a run with no video |
| **Contrast checked** | Every text/surface pair the screens use is now computed against WCAG AA in a test. **One failed:** pencil grey on the shaded fill (disabled buttons, Material's variant surfaces) was 4.43:1. Pencil is now `#666666` (4.77:1), on the phone and on the dashboard, which had the same pair |
| **No hard-coded user-facing text** | The audit found none in the screens. My own Sprint 18 run screen had hard-coded units ("s", "/km"), now translated. A test scans the UI code and fails on any new literal (animation debug labels exempt) |
| **Touch targets** | Every custom tappable element was checked. Rows, buttons, chips, the consent tick, the language picker and the leaderboard switch were all 48 dp or more already. The camera screen's voice toggle and record button now guarantee 48 × 48 dp whatever their label length |
| **Dynamic font sizes** | Checked in the code for fixed heights around text and for single-line text. None clip: the fixed heights are bars, rules and images, and the two single-line texts truncate with an ellipsis on purpose. **Not yet walked at 200% on a phone** |
| **Screen-reader labels** | Already in place on the key screens (README Sprint 9). New this sprint: the progress strip, the notification inbox's unread state (Sprint 17) and the demonstration's descriptions |
| **Error recovery** | Audited below; every failure the athlete can meet has an action |
| **Languages** | English, Hindi, Tamil and Bengali — complete, enforced by `TranslationCompletenessTest` (same keys, same format arguments, nothing left in English). 15 new strings in each. **Native-speaker review is still needed** |

## Error recovery audit

| Where | Failure | What the athlete can do |
|---|---|---|
| Any screen loading from SAI | no network, server error | the reason in their language, and **Try again** |
| Sign-in | wrong code, too many tries | the reason, when to retry, resend |
| Photo check | face not matched, no face | retake, or continue (a reviewer looks) |
| Camera | permission refused | why it is needed, and the way to settings |
| Camera | hand signal not seen | try again, with the signal shown |
| Camera | interrupted (call, app left) | try again; nothing half-recorded is sent |
| Official test | session slot taken, session closed | said before recording, with nothing to press |
| Upload | no signal | "saved, will be sent" (camera and field); the Sync screen's **Send now** |
| Upload | refused for good | "not accepted", why, and that resending will not help |
| Shuttle run | no gyroscope | said on the setup screen, before starting |
| Endurance run | no location permission | why it is needed, and **Allow location** |
| Endurance run | weak GPS | **Start** waits for GPS ready; a run lost to bad GPS gets its reason |
| Any run not scored | the analyzer's reason | in their language, with what to change; an official run that could not be scored is not sent, so the session slot stays free |

## Tests

`AccessibilityRulesTest` (5):
- contrast of 19 text/surface pairs, and field borders;
- the official flow's steps;
- all eight instruction points for every camera test;
- no hard-coded text.

## Known limits

- The demonstrations loop the existing diagrams; filmed demonstration videos
  are still to be made.
- A chest number at 200% text can be wider than a small phone's screen. It is
  read out in full by the screen reader, but has not been seen at that size.
- Bengali was added in README Sprint 9; the plan names English, Tamil and
  Hindi. Whether to add languages for the pilot regions is SAI's decision.
