# Sprint 15 — Results, History & Benchmarking

## Status

An athlete can open any official result and see, in one place, the score and
how it was written down, whether it counts, SAI's own form verdict, their
personal best, the attempt before it, and where it stands against their
cohort. A new "My results" screen lists official tests and practice together,
newest first, and never mixes the two up.

- **Backend:** 577 tests green (38 new in `tests/test_results_history.py`, 1 updated for the additive `result_id`), ruff clean, `docs/openapi.yaml` regenerated. **One
  migration** (`d2f6a8b41c90`, regional benchmarks). It upgrades, passes
  `alembic check`, downgrades and re-upgrades on SQLite and on **Postgres 16**;
  on Postgres the new partial unique index refused a duplicate all-India row.
- **Mobile:** 328 unit tests green, 1 skipped (the reference-video accuracy harness); 13 new. Android lint: 0 errors; none of the 67 pre-existing warnings are in Sprint 15 code.
- **Dashboard:** no feature change. Typecheck clean, 117 tests green, no unhandled errors. One test fixed: the queue
  filter test submitted the search *input* rather than its form, which made
  jsdom throw an unhandled error on every run.
- **Not done here:** walking the screens on a physical phone. Everything they
  compute is unit-tested and the app builds; the layout has not been seen on a
  device.

---

## What already existed, and was kept

- The paged history API (`GET /api/athletes/me/history`), personal bests,
  `/me/summary`, and benchmarks by age band and gender with a `source` on every
  row and `provisional` carried to the athlete (Sprint 7, README numbering).
- The phone's server result screen (pencil / pen / stamp writing, the
  acknowledgement slip, the review note, the benchmark card), the profile, and
  the practice history with its personal-best garland (Sprint 6).
- The practice result sheet already showed form score, validity, personal best
  and the change from the previous attempt. Practice is never benchmarked.

## The rules, now written down once

`backend/app/services/athlete_results.py` holds the rules that the profile,
history, result screen and badges all use, so they cannot disagree.

**Which score counts.** Only a score SAI stands behind:

| Status | Counts? | Score used |
|---|---|---|
| `approved` | yes, **official** | the official's `final_score` |
| `verified` | yes, not yet official | the server's `server_score` |
| `uploaded`, `processing` | not yet | — |
| `flagged` | not until a reviewer decides | — |
| `rejected`, `pending_sync` | no | — |

Every result stays in the history with its status; only counting ones can be a
personal best or be benchmarked for the athlete.

**What a personal best is.** The best counting score per test: highest for reps
and the jump, lowest for a timed test (`tests.higher_is_better`). Equalling a
best does not take it, so the earlier result keeps it — except that an approved
result takes a tie from a merely verified one, because "official" is the
stronger claim. The phone uses the same rule for its "Counts?" line
(`ResultPresentation.validity`).

**Previous attempt.** The athlete's attempt at the same test just before this
one, whatever became of it, ordered by attempt number (timestamps can tie).

## What was wrong, and is fixed

| Problem | Effect | Fix |
|---|---|---|
| A flagged or rejected result with a server score was benchmarked for the athlete | A score a reviewer might throw out could be shown as "Top 10%" | The athlete's benchmark needs a counting result; the reason it is missing is now told apart (being checked / being reviewed / not counted / no group). Reviewers still see it, as context |
| Personal-best ties depended on row order | The "best" result could change between requests | Oldest-first by attempt number, with the approved-beats-verified rule above |
| The phone's history showed no date and no session | An athlete could not tell a district trial from an open test | `session_name` on history rows and results; dates on every row |
| Official history and practice lived on two screens, and the profile listed every result at once | No single place answered "what have I done" | "My results" (paged), the profile shows the latest five |
| An offset-less timestamp failed to parse on the phone | One such field made a whole response a "server error" | Times without an offset are read as UTC, the server's storage convention |

## API changes (all additive)

- `GET /api/results/{id}` adds `created_at`, `session_id`, `session_name`,
  `server_form`, `server_invalid_reason`, `personal_best` and
  `previous_attempt` (a history item).
- `server_form` is `{score, faults: [{code, reps_affected}] | null}` — the
  two-member plan's §6.2 contract — and is also on `GET /api/verification/{id}`.
  `code` is a `FormIssue` name. `faults` is null for a result verified before
  this sprint (never an empty stand-in). The worker now records
  `form_faults` in the server snapshot; this is a record of events the
  analyzer already produced, so no scoring changed and the Kotlin/Python
  parity contract is untouched.
- History items add `session_name`; personal bests add `result_id`;
  benchmark comparisons add `region` (null = all-India norms).

**API and phone agree on values.** `test_the_result_and_history_fixtures_match_the_api`
renders real responses into `backend/tests/fixtures/api/*.json` and fails when
they drift; the phone's `ResultContractTest` parses those same files. Regenerate
with `UPDATE_API_FIXTURES=1`.

## Benchmarking

**Cohort dimensions:** test, gender, age band, **region** (new), and the score
band (`top_10` / `top_25` / `above_average` / `below_average`).

- `benchmarks.region` holds a state's own norms; blank means all-India. A row
  for the athlete's state wins, all-India is the fallback, and another state's
  row never applies.
- Two partial unique indexes replace `uq_benchmark_cohort`. A plain constraint
  over a nullable region would treat each null as distinct and stop guarding
  the all-India rows it guarded before.
- The seeder accepts an optional `region` column, stores it in the canonical
  spelling athletes' regions use, and refuses a name that is not a state or UT.

**Source and status (Definition of Done item).** There is still **no official
SAI table** for these tests: Khelo India's norms cover a different battery. The
bundled `benchmarks_provisional.csv` has sit-up and vertical-jump rows only,
every one marked `PROVISIONAL`; the API sends `provisional: true` and the phone
shows the caveat on every comparison, undismissable.

Squats, push-ups, lunges and bicep curls got placeholder rows too (decided
after the sprint review, 2026-10-13), so every test in the battery has a
comparison for both genders at every age from 9 to 25 — a test checks there
are no gaps and no overlaps. They are weaker than the sit-up and jump rows,
and each row's `source` says why: SAI has no norm for these tests, the app
scores **one continuous set with no time limit** (so a count measures
endurance as much as strength), and curls are done with **no standard load**.
Replace or delete them as soon as SAI defines these tests. No regional rows
exist yet; the engine is ready for them.
An official table loads with `seed-benchmarks --file … --replace` and the
caveat goes away with no code change.

## Phone

- **Result screen** (`ServerResultScreen`): new "Your record" sheet — date,
  session, *Counts?* (with SAI's reason when it could not score the video),
  personal best ("this result is your personal best" when it is), and the
  previous attempt, which opens that result. A form sheet shows SAI's form
  score and the faults behind it, or "not available yet"; it is hidden for the
  jump. The benchmark card says whose norms were used ("All India" or the
  state).
- **My results** (`ui/history/HistoryScreen`): official and practice in one
  list, filterable All / Official / Practice, "Show older results" for the next
  page. Official rows carry the ink OFFICIAL tag and the session name; practice
  rows the pencil PRACTICE tag and "never counts". `HistoryTimeline.merge` holds
  back practice older than the oldest official result loaded, so the list is in
  true date order however far the athlete has scrolled.
- Practice comes from this phone and the athlete's account, filtered to the
  signed-in athlete (the shared-phone rule). A local copy of an official attempt
  is never listed beside SAI's record of it.
- 28 new strings, in English, Hindi, Tamil and Bengali.

## Tests

- Backend `tests/test_results_history.py` (38): what counts, timed tests, ties,
  approve-after-flag, per-test bests, session names, every status in history,
  paging without gaps, privacy between athletes, personal best and previous
  attempt on a result, server form (new, legacy, before measurement), the
  server's invalid reason, form-fault counting by rep, benchmark gating per
  status, the reviewer's view, regional lookup and fallback, both unique
  indexes, regional seeding, and the API fixtures.
- Mobile `ResultsHistoryTest` (10) and `ResultContractTest` (3).

## Known limits

- **Placeholder norms for squats, push-ups, curls and lunges** are rough
  magnitudes, not measurements, and the tests themselves have no time limit
  (and curls no load). They are labelled provisional everywhere, but they will
  be wrong for many athletes until SAI defines the tests. Pilot data
  (Sprint 24) should replace them.
- **`GET /api/results/{id}` still returns the automatic flags** (reason and
  detail) to the athlete, while `/api/verification/{id}` deliberately hides
  them so cheat checks are not explained to the person they check. The phone
  never reads them. Recommended for Sprint 20 (security & privacy).
- An official test recorded offline and not yet submitted appears on the Sync
  screen, not in "My results", until SAI has it.
- Region names are shown as stored (English), as elsewhere in the app.
- The new translations have not had a native-speaker review (Sprint 19).
- Not walked on a physical phone.
