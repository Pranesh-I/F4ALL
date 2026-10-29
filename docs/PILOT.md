# F4ALL — Pilot Protocol (Sprint 24)

The final check before release: the complete journey, with real athletes on
their own phones, observed and measured. This document is the protocol; the
sheets in [pilot/](pilot/) are where the pilot's data goes.

**Before inviting athletes**, run the rehearsal: every link of the chain,
against the real stack, with nobody watching:

```bash
cd backend
python -m loadtest.env setup && python -m loadtest.rehearsal
```

It must pass (see [pilot/rehearsal.json](pilot/rehearsal.json)). The pilot
then answers what the rehearsal cannot: can real people do this, and are the
numbers right?

## 1. Participants

| | Target |
|---|---|
| Athletes | 15–25, aged 12–18, with guardian consent, both genders, from at least 2 districts |
| Experience | some new to the tests, some trained |
| Phones | their own. At least 3 entry-level (≤ 3 GB RAM), and at least one 32-bit or Android Go phone if any athlete has one |
| Connectivity | at least one rural or low-signal site; some athletes record offline and send later |
| Officials | 1 SAI admin, 2 regional reviewers (one per district) |
| Evaluators | 2 coaches, scoring independently of the app (§4) |

Record each participant in `pilot/participants.csv`, which is kept off the
repository: it identifies children. The other sheets use only the pilot id
(P01…).

## 2. Test battery

Each athlete: **practice** any test once, then the **official** session:

| Test | Official | Evaluator measures |
|---|---|---|
| Sit-ups | yes | reps, strict count |
| Squats | yes | reps, strict count |
| Push-ups | yes | reps, strict count |
| Vertical jump | yes | height against a wall chalk mark; standing height measured on the day |
| Shuttle run 4 × 10 m | yes | stopwatch time on a taped course |
| Endurance run 600 m | where a measured route exists | stopwatch time |

Bicep curls and lunges are optional extras if time allows.

## 3. Instructions given

Athletes get **only what the app gives them**: the in-app instructions, in
their language. The facilitator may help with installing the app and with
signal, nothing else. Every time someone needs help, it is an observation
(§5).

## 4. Human evaluator procedure

- Two evaluators score every official attempt independently, by eye, on the
  sheet, without looking at the phone.
- A disagreement between them over 1 rep (or 2 cm, or 0.2 s) is settled from
  the video afterwards; the settled number is the ground truth.
- Mark each attempt's form as good or faulty, and note anything unusual
  (stopped early, a second person in frame, a phone that fell over).
- The videos and ground truth then go into `docs/accuracy/dataset.csv`, and
  `python -m app.cli accuracy` produces Sprint 22's report from real data.

## 5. Data collection

| Sheet | One row per | What |
|---|---|---|
| [pilot/attempts.csv](pilot/attempts.csv) | official attempt | human, mobile and server results side by side; time to verdict; flags; review outcome |
| [pilot/observations.csv](pilot/observations.csv) | thing seen | the stage it happened at (registration, OTP, instructions, gesture, camera, detection, form feedback, upload, offline, result), what happened, and whether help was needed |
| [pilot/issues.csv](pilot/issues.csv) | problem | P0–P3 (§7), owner, status |
| [pilot/threshold-changes.md](pilot/threshold-changes.md) | changed threshold | old and new value, the evidence, who decided |

**Backend, during the pilot:**
- `GET /health/verification` (queue time, processing time, SLA breaches);
- `python -m app.cli consistency` at the end of each day;
- the API error log;
- notification failures (`notifications.delivery_status = failed`).

**Admin, during the pilot:** for each step, the reviewer notes whether it
worked: session creation, a submission arriving, review, video playback,
flags shown, approval, rejection, asking for a resubmission, and the audit
history.

## 6. Incident reporting

Anything that stops an athlete, loses data, or shows a wrong result:

1. The facilitator writes it in `issues.csv` straight away, with the pilot id,
   the time, the phone and what was on screen. A photo of the screen helps.
2. P0 and P1 go to the developer the same day.
3. Never "fix" a result by hand. A reviewer decides through the dashboard,
   with a reason, so the audit trail stays complete.

## 7. Priorities

| | Meaning | Before release |
|---|---|---|
| **P0** | Blocks an official assessment, loses a submission, or shows a wrong official result | must be fixed |
| **P1** | Major problem for a user or the system; a workaround exists | must be fixed |
| **P2** | A usability problem | fix or accept, with reason |
| **P3** | A minor improvement | backlog |

## 8. Benchmark and anti-cheat tuning

From the pilot data, look for:
- checks that fired on honest athletes (false flags);
- tricks the checks missed;
- device–server differences beyond tolerance;
- benchmark bands that put everyone in one group.

**Do not change a threshold silently.** Each change goes in
`threshold-changes.md` with the evidence, then into the code with its test,
and the accuracy report is regenerated.

## 9. Done when

- Every participant completed the battery, or their blocker is recorded as an
  issue.
- Every official attempt has human, mobile and server results.
- No open P0 or P1.
- The accuracy report is regenerated from pilot data and compared with the
  targets (±1 rep, ±3 cm).
- The consistency check is clean at the end of every day.
- Threshold changes are recorded with their evidence.
