# Sprint 4 — Offline Queue, Compression & Sync

## Status

Implementation complete. 98 unit tests green, including the full resumable
upload protocol exercised against MockWebServer.

**End-to-end verification is outstanding on two counts**, both external:

- No backend exists yet to upload to (Sprint 5 builds it).
- No low-end device is available for the airplane-mode Definition of Done walk.

---

## Goal

A test recorded with no internet reliably reaches the server once connectivity
returns.

---

## The pipeline

```
 record ─> RECORDED ─> COMPRESSING ─> COMPRESSED ─> QUEUED ─> UPLOADING ─> SYNCED
                │            │             │           │          │
                └────────────┴─────────────┴───────────┴──────────┴──> FAILED
                                                                        │
                                                              athlete taps Retry
```

`SYNCED` means SAI received the video. It does **not** mean the result counts —
the server re-scores in Sprint 5 and an official approves in Sprint 8. The sync
screen says "Sent to SAI", never "Verified", because an athlete who thinks they
are finished when they are not is the failure this wording exists to prevent.

---

## Substituted dependency: FFmpeg-Kit → Media3 Transformer

**The README's compression dependency no longer exists.** Arthenica retired
FFmpeg-Kit, and every `com.arthenica:ffmpeg-kit-*` artifact has been removed
from Maven Central — `https://repo1.maven.org/maven2/com/arthenica/` now
contains only `smart-exception-*`. It cannot be resolved at any version, so this
was a forced substitution rather than a preference.

`androidx.media3:media3-transformer` replaces it, and is a better fit anyway:

| | FFmpeg-Kit | Media3 Transformer |
|---|---|---|
| Encoder | Software (libx264) | Hardware (MediaCodec) |
| On a ₹8,000 phone | Pins all cores, throttles, drains battery | Uses the dedicated video encoder |
| Maintenance | Retired | AndroidX, actively maintained |
| Already in the README stack | Yes | Yes ("Media3/ExoPlayer") |

The project's second guiding principle is that low-end performance is a feature.
A software transcode on a four-core budget phone is exactly the kind of thing
that makes an app unusable on the hardware it was built for, so the forced
change moves in the right direction.

What is lost: arbitrary filter graphs, and encoder behaviour that varies by
vendor. Neither matters for "make this smaller at 480p".

**Output target is 480 on the SHORT side**, not the tall side. These are
portrait recordings; asking for 480 height would yield a 270-wide video and
throw away detail the server's pose pass in Sprint 5 depends on. Compression is
not purely a bandwidth decision here — over-compress and the server's landmarks
get noisier than the phone's were, which reads as a score discrepancy and
auto-flags an honest athlete.

---

## Contract gap found and closed

The Sprint 0 OpenAPI contract had `/api/tests/submit` accept a `video_id`
described as "ID of the uploaded test video" — but **defined no endpoint that
uploads a video**. There was nothing to code the client against.

Four endpoints added to [openapi.yaml](openapi.yaml):

```
POST /api/videos/upload/init                      reserve, get upload_id
GET  /api/videos/upload/{id}                      which chunks did you get?
PUT  /api/videos/upload/{id}/chunks/{index}       send one chunk
POST /api/videos/upload/{id}/complete             reassemble + verify checksum
```

Sprint 5 implements the server side. The client is already written against this
contract and tested against a mock of it.

---

## Why resumable, and what makes it actually resume

A 6MB upload over patchy 3G will be interrupted. Restarting from byte zero every
time burns mobile data the athlete paid for and may not be able to replace — and
on a bad enough link, an upload that always restarts never finishes at all.

Two design decisions do the real work:

**The client asks the server what arrived.** It never trusts its own record of
what it sent. A chunk that left the device but never landed is precisely the
case resumption exists for, and client-side optimism there produces a corrupt
file that only fails at the final checksum.

**The `upload_id` is persisted before any bytes move.** `onUploadIdIssued` fires
the moment the server allocates it, and the callback writes it to the database
immediately. If the process is killed during the first chunk, that one column is
the difference between resuming and re-uploading the whole video. A test pins
this: it asserts zero chunk requests have been made when the id is issued.

---

## Bugs found during the sprint

### Three livelock paths in the state machine

Writing recovery tests before believing my own state table caught this. The
transition rules allowed `QUEUED → UPLOADING` and `COMPRESSED → QUEUED`, but not
`QUEUED → COMPRESSING` or `COMPRESSED → COMPRESSING`.

Android reclaims app storage under pressure, and users clear app data. When the
compressed transcode vanishes from under a queued attempt, the worker correctly
decides to rebuild it — and the transition is refused. The attempt then retries
forever without progressing, showing the athlete "waiting for a connection"
while their test never leaves the phone.

The manual Retry button had the same shape of bug: `retry()` validated the
transition to `COMPRESSING` but then wrote `RECORDED`, a status the table did not
permit from `FAILED`. Tapping Retry on an attempt whose transcode was gone would
have silently done nothing.

Both fixed, with tests. A general guard was added too: every non-terminal status
must have at least one legal exit, so no future edit can create a state an
athlete's test gets stuck in.

### Blocking I/O on the CPU dispatcher

`CoroutineWorker.doWork` runs on `Dispatchers.Default`, a small pool sized for
CPU work. The upload client blocks (OkHttp `execute()`), and so does hashing a
multi-megabyte file. Holding a Default thread for the length of a 3G upload
starves everything else scheduled on that pool. Both are now on `Dispatchers.IO`.

---

## Decisions worth knowing about

**`NetworkType.CONNECTED`, not `UNMETERED`.** Waiting for wifi would be kinder to
the athlete's data bill, but many of the athletes this is built for have mobile
data and nothing else. A test that only uploads on wifi is a test that never
uploads. Compressing to 480p first is what keeps the data cost defensible.

**256KB chunks.** Sized for the network, not for throughput. A larger chunk
throws away more when a 2G/3G connection drops mid-chunk; a smaller one adds
round trips that each carry their own latency and failure chance.

**Jittered backoff.** Forty athletes at a coaching camp recovering from the same
tower outage must not all retry on the same millisecond. Costs nothing, removes
a self-inflicted thundering herd.

**Sequential, not parallel.** One hardware video encoder, four weak cores, and a
3G link. Two concurrent transcodes are slower than two sequential ones.

**`ExistingWorkPolicy.KEEP`.** A worker already mid-upload is allowed to finish
rather than being replaced — replacing it discards progress the athlete already
paid for in data.

**No `fallbackToDestructiveMigration`.** This table can hold the only record of a
test an athlete travelled to record. A future schema change must ship a real
migration; Room's exported `app/schemas/…/1.json` is committed so migrations are
reviewable in a diff.

**Only scored attempts are enqueued.** Uploading a recording the device could not
score spends the athlete's data on a video an official would reject anyway.
They are told to retry immediately, while they are still standing there.

**The source recording is deleted once synced.** The original is several times
the size of the 480p upload, on a phone that may have very little free space.

---

## Test coverage

98 tests, all on the JVM, no device needed.

| Area | Tests | What is actually verified |
|---|---|---|
| `UploadClientTest` | 13 | Full protocol against MockWebServer: chunk reassembly is byte-identical to the source, resume sends only missing chunks, dropped connections are retryable, checksum mismatch is not, upload id arrives before any bytes |
| `ChunkPlanTest` | 10 | Chunk sizes sum exactly to the file size across awkward sizes; offsets are contiguous with no gaps or overlaps |
| `SyncStateTest` / `SyncRecoveryTest` | 13 | Legal transitions, no dead ends, recovery targets land in the work queue |
| `RetryPolicyTest` | 7 | Exponential growth, cap, jitter band, retryable vs. terminal failures |
| `ChecksumTest` | 5 | Known vector, streaming across block boundaries, tamper detection |
| Sprint 3 analyzers | 49 | Unchanged, still green |

One test infrastructure note: `org.json` in `android.jar` is a stub that throws
"not mocked" on the JVM, so the real `org.json:json` is on the **test** classpath
only. Production keeps using Android's built-in implementation — no APK cost.

---

## Sprint 4 Definition of Done

> Record a test in airplane mode, close the app, reopen with wifi on later —
> video compresses and uploads automatically without user intervention.

| Piece | Status |
|---|---|
| Room queue with the full status state machine | Done |
| Compression to 480p H.264 off the main thread | Done |
| WorkManager job, connectivity-constrained | Done |
| Retry with exponential backoff, capped attempts, terminal failures surfaced | Done |
| Chunked resumable upload, resuming from the last acknowledged chunk | Done, verified against MockWebServer |
| SHA-256 computed on-device over the uploaded bytes | Done |
| Sync status UI with per-test state and manual retry | Done |
| Recovery from process death mid-compression / mid-upload | Done |
| **The end-to-end walk itself** | **Blocked — no backend to upload to (Sprint 5), no low-end device** |

The pieces are individually verified. The DoD is a single end-to-end journey
that cannot be walked until there is a server at the other end.

---

## Notes for the next sprint

- **`SyncConfig` points at `http://10.0.2.2:8000`** — the emulator's alias for
  the host. Sprint 5 should replace it with a build-variant setting so a release
  build cannot possibly be pointed at a developer's machine.
- **The upload client sends no auth token yet.** `authTokenProvider` is wired
  through and tested but returns null until Sprint 7 issues JWTs. The endpoints
  are already declared `bearerAuth` in the contract.
- **`/api/tests/submit` is not called yet.** The client uploads the video and
  stores the returned `video_id`; submitting the provisional score against it is
  a one-call addition once the endpoint exists.
- **Sprint 1's JPEG frame extraction still runs after every recording** and now
  has no consumer at all — scoring uses the live pose sequence, and upload uses
  the video. On a low-end device it is 100+ JPEG writes per test for nothing.
  This was flagged in Sprint 3; it should be deleted.
- **The debug APK is ~72MB**, dominated by MediaPipe and Media3 native libraries
  across all ABIs. A release build with minification and ABI splits (or an app
  bundle) will be far smaller, but download size matters a great deal for this
  audience and Sprint 11–12 should treat it as a real target, not an afterthought.
