# Sprint 16 — Leaderboards & Analytics

## Status

Athletes can filter their leaderboard by test, place, age group and session.
Officials have an analytics page: totals, five charts, and the session, upload
and verification-time figures, all counted by the server for one set of
filters.

- **Backend:** 618 tests green (39 new in `tests/test_leaderboards_analytics.py`, plus 2 for the Sprint 15 placeholder norms), ruff clean, `docs/openapi.yaml` regenerated. No
  migration.
- **Postgres 16, checked by hand:** a scratch database with 400 athletes,
  3,000 results, 3 sessions and 500 uploads. Every analytics figure the API
  returned was compared with independent SQL run in `psql` (below). All
  matched, after one fix: the 95th percentile was one rank too low.
- **Dashboard:** typecheck clean, 126 tests green (9 new), production build OK.
- **Mobile:** 334 unit tests green, 1 skipped (the reference-video harness);
  Android lint 0 errors, no warnings in Sprint 16 code.
- **Not done here:** the analytics page has not been looked at in a browser,
  and the leaderboard filters have not been tried on a phone. Both are covered
  by component tests that read the rendered values.

---

## What already existed, and was kept

- The athlete leaderboard (Sprint 9, README numbering): approved results only,
  best per athlete, opt-in, first name and initial, the athlete always sees
  their own position, ranked within gender and age band.
- The officials' leaderboard page, with test, region, gender and age filters,
  and `GET /api/dashboard/stats` for the dashboard's tiles.
- An `/admin/analytics` route and nav entry, holding a placeholder.

## Athlete leaderboard

**New filters** on `GET /api/athletes/leaderboard/{test}`:

| Parameter | Values | Default |
|---|---|---|
| `scope` | `region` (own state), `national` | `region` |
| `region` | any state or union territory (overrides `scope`) | — |
| `age_band` | `mine`, `all`, or a band such as `12-13` | `mine` |
| `session_id` | an assessment session | — |
| `limit`, `offset` | paging, `limit` up to 100 | 20, 0 |

The response also echoes `age_band`, `session_id`, `session_name` and `offset`.
`GET /api/athletes/leaderboard-options` lists the age bands, the athlete's own
band and state, and the sessions they took part in (the only sessions offered
as filters).

**Privacy under every filter.** Opt-in only, first name and initial, no ids or
ages, and the athlete always sees their own standing — tested against every
filter combination. Gender stays the athlete's own (a girl is never ranked
against boys); an athlete registered as "other" still sees an all-genders
board, as before.

**Changed: equal scores now share a rank** (44, 33, 33, 30 rank 1, 2, 2, 4).
Before, ties were numbered in whatever order the rows came back.

**Scaling.** The board used to load every approved result for the test into
Python and filter there, which for an all-India board means every result in
the country. Filtering, the best score per athlete, ranks and counts now run
in the database. A test checks that the number of queries for a national board
is the same for 4 athletes and for 44.

**Phone.** `LeaderboardScreen` has filter rows for place (my state / all of
India / any other state from a list), age group (mine, all ages, each band) and
session (only when the athlete has taken part in one), plus "Show more" for the
next page. The cohort line names the age band, place and session. 11 new
strings in English, Hindi, Tamil and Bengali.

## Officials' leaderboard

A session filter (`session_id`) on `GET /api/dashboard/leaderboard` and on the
dashboard page.

## Analytics

`GET /api/dashboard/analytics` (`routers/analytics.py`) — filters:
`submitted_from`, `submitted_to`, `region`, `test_type`, `session_id`.

| Plan metric | Where |
|---|---|
| Total athletes | `totals.athletes` (registered, region filter only), `totals.active_athletes` (submitted in the window) |
| Total / verified / flagged / rejected submissions | `totals`, `by_status` |
| Average scores | `tests[].average_score` — over results that count only (verified or approved), in the test's own unit |
| Regional performance | `regions[]`: athletes, submissions, counted, flagged, rejected |
| Test participation | `tests[]`: submissions, athletes, counted |
| Session participation | `sessions[]`, plus `outside_sessions` |
| Upload failures | `uploads`: started, completed, still uploading, abandoned (expired and never completed), refused by verification as unscorable |
| Verification time | `verification_time`: median and 95th percentile of the wait for a verdict and of the worker's time, and how many were within the target |

**Charts** (dashboard `/admin/analytics`): participation over time (columns per
India-time day, week or month, chosen by the span; empty buckets are zeros, not
gaps), verification status, regional distribution, test performance (with the
averages table), and score distribution (once a test is chosen — reps and
centimetres cannot share an axis). They are plain HTML bars in the Trials
tokens, so no chart library was added. Every mark has a hover title with its
exact value, and every chart has a "Show the numbers" table. Statuses use the
materials they use elsewhere (approved in the stamp's violet, flagged in red pen)
and are always labelled, so colour is never the only cue.

**Scope.** Officials only (401 signed out, 403 for an athlete). A regional
reviewer sees only their region, and asking for another region is refused
(403) instead of showing a page of zeros that looks like a real answer. A
reviewer whose region was never set sees nothing, as everywhere else.

**Empty states.** No matching submissions gives zeros and empty lists from the
API, and on the page a plain "No submissions match these filters" instead of
empty charts.

**Not cached.** Against 3,000 results on Postgres, the unfiltered analytics call
took about 330 ms and a filtered one about 100 ms, and a national athlete board
about 60 ms (in-process, one run each). Whether Redis belongs in front of it is
left to the Sprint 23 load test.

## Manual verification against the database (Definition of Done)

Seeded on Postgres 16 with a fixed random seed: 400 athletes in 6 states,
3,000 results across all 6 tests and every status, 3 sessions, 500 uploads.
The API figures were then compared with SQL written separately
(`count … FILTER`, `percentile_disc`), run in `psql`:

| Figure | API | SQL |
|---|---|---|
| Results by status (approved / verified / flagged / rejected / processing / resubmission) | 1176 / 640 / 482 / 209 / 223 / 270 | same |
| Athletes registered / active | 400 / 400 | same |
| Squats: submissions, athletes, counted, average | 512, 290, 310, 34.80 | same |
| Bihar: athletes, submissions, counted, flagged, rejected | 87, 629, 364, 110, 45 | same |
| Sessions (Trial 2/1/0) and outside | 482 / 451 / 448, 1619 | same |
| Uploads started / completed / abandoned; refused | 500 / 400 / 57; 209 | same |
| Turnaround measured / within 300 s / median | 2777 / 1991 / 219 s | same |
| Turnaround 95th percentile | **383 s** → 384 s after the fix | 384 s |
| Last 30 days, squats: submissions, athletes, average | 258, 186, 34.95 | same |
| Reviewer for Andhra Pradesh: submissions, athletes | 459, 60 | same |
| Athlete board, squats, all India, all ages: ranked, top 5 | 54; 58, 57, 57, 55, 54 (ranks 1, 2, 2, 4, 5) | same |

**What was wrong, and is fixed:** the shared percentile helper (also used by
`/health/verification` since Sprint 11) rounded the rank instead of taking the
ceiling, so it picked a value one place too low whenever p × n had a fraction
under a half. It is now the nearest-rank definition Postgres uses, with a test.

## Tests

- Backend `tests/test_leaderboards_analytics.py` (39): every new
  board filter, shared ranks, paging, privacy under each filter, bad filters,
  the options endpoint, constant query count, the officials' session filter;
  analytics totals, averages over counted results only, regions and sessions,
  upload failures, verification time, filled day buckets, weekly buckets, each
  filter, the score distribution, reviewer scoping and refusal, an unset
  region, officials only, no data, bad filters, histograms, percentiles.
- Dashboard `AnalyticsPage.test.tsx` (9): totals, every chart against the API
  values, the score distribution after choosing a test, every filter sent,
  reviewer view, empty state, failure and retry, helpers.
- Mobile `LeaderboardFiltersTest` (6): every filter in the request, paging,
  options, an older server's response.

## Known limits

- Analytics are computed on every request. Fine at pilot scale (above); the
  load test decides whether to cache.
- Upload counts ignore the session filter: an upload belongs to a session only
  once it has been submitted, and an abandoned one never is. The page says so.
- Day buckets are India time; a window chosen in another time zone starts at
  that browser's midnight.
- The analytics page has not been viewed in a browser, nor the leaderboard
  filters on a phone.
