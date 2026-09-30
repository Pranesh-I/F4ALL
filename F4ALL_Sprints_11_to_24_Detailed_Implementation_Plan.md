# F4ALL — Detailed Sprint Implementation Plan
## Sprint 11 → Sprint 24

> **Scope:** This document expands the master F4ALL implementation plan from **Sprint 11 onward**.
>
> **Assumption:** Sprint 0–10 are already completed. Do not rebuild completed modules unless an integration, security, correctness, or performance issue requires it.
>
> **Source of truth:** The uploaded F4ALL master implementation plan. The original plan defines Sprint 11 as Server-Side AI Verification and continues through Sprint 24 as the End-to-End Pilot.

---

# How to Use This Document

Every sprint below follows the same structure:

1. **What this sprint means**
2. **Goal**
3. **Why it matters**
4. **Inputs / prerequisites**
5. **Detailed implementation tasks**
6. **Expected output**
7. **Testing**
8. **Definition of Done**
9. **Completion evidence**
10. **What must NOT be considered complete**
11. **Next sprint dependency**

### Important working rule

Do not move to the next sprint merely because the code compiles.

A sprint is complete only when its **Definition of Done is demonstrably satisfied** with tests, screenshots/logs/results, and a short completion report.

---

# Sprint 11 — Server-Side AI Verification

## 11.1 What is this sprint?

Sprint 11 turns the backend into the **authoritative verification layer**.

The Android application performs the first-pass AI processing because the athlete needs immediate feedback and the application must work offline. However, the mobile result must remain **provisional**.

The server independently processes the submitted assessment and produces the authoritative result.

The core invariant is:

```text
Mobile Result = PROVISIONAL
Server Result = AUTHORITATIVE
```

The master plan defines the server pipeline as:

```text
Upload
  ↓
Validate Video
  ↓
Extract Frames
  ↓
MediaPipe / OpenCV
  ↓
Exercise Detection
  ↓
Rep / Measurement Calculation
  ↓
Form Validation
  ↓
Cheat Checks
  ↓
Compare Mobile Result
  ↓
Final Result
```

## 11.2 Goal

For every official submission:

- receive the uploaded video;
- validate it;
- process it independently;
- calculate the server-side score;
- compare it with the mobile score;
- produce a verification status;
- store the complete verification result;
- make the result available to the dashboard.

## 11.3 Prerequisites

Before starting:

- Sprint 10 FastAPI backend is working;
- mobile upload flow is working;
- video reaches the backend;
- PostgreSQL schema exists;
- object storage integration exists or has been prepared;
- exercise result format is defined;
- mobile provisional result is stored with the submission.

## 11.4 Detailed implementation

### A. Submission lifecycle

Create a clear state machine:

```text
CREATED
  ↓
UPLOADING
  ↓
UPLOADED
  ↓
PROCESSING
  ↓
VERIFIED
```

Failure paths:

```text
PROCESSING
   ├── FLAGGED
   └── REJECTED
```

Do not allow a submission to silently jump from upload to approved.

### B. Video validation

Validate:

- file exists;
- supported format;
- file size;
- duration;
- resolution;
- readable frames;
- frame extraction success;
- expected exercise/test type;
- athlete/submission relationship;
- duplicate upload identity where applicable.

Return a structured error instead of a generic 500 error.

### C. Frame extraction

Build a reusable server-side video processing component.

It should:

- open the video;
- determine frame count;
- determine FPS;
- sample frames;
- reject corrupted videos;
- expose frames to the pose pipeline;
- record processing metadata.

### D. Server-side pose pipeline

Reuse the same conceptual exercise rules as the mobile implementation, but do not trust the mobile output.

For supported pose-based tests:

- obtain landmarks;
- filter low-confidence frames;
- calculate joint angles;
- run the exercise state machine;
- calculate repetitions or measurement;
- identify invalid movement;
- generate a server-side score.

### E. Mobile vs server comparison

Store both:

```text
mobile_rep_count
server_rep_count

mobile_measurement
server_measurement

mobile_form_score
server_form_score
```

Define configurable discrepancy thresholds.

Example:

```text
if abs(mobile_reps - server_reps) > allowed_difference:
    FLAG
```

Do not hard-code a threshold without recording why it was selected.

### F. Finalization

Create a finalization service that determines:

```text
VERIFIED
FLAGGED
REJECTED
```

The final status must be traceable to:

- server result;
- validation checks;
- discrepancy checks;
- cheat checks;
- processing errors.

### G. Persistence

Store enough information to reproduce the verification decision later.

At minimum:

```text
submission_id
mobile_result
server_result
verification_status
processing_started_at
processing_completed_at
processing_duration
verification_reason
model/pipeline version
```

## 11.5 Expected output

By the end of Sprint 11 you should have:

```text
Android App
   ↓
Upload
   ↓
FastAPI
   ↓
Stored Video
   ↓
Background Verification
   ↓
Server AI Result
   ↓
Mobile vs Server Comparison
   ↓
Verification Status
   ↓
Database
```

And an API through which the client/dashboard can retrieve the verification state.

## 11.6 Testing

### Unit tests

Test:

- video validation;
- state transitions;
- score comparison;
- discrepancy thresholds;
- result finalization.

### Integration tests

Test:

```text
upload → storage → worker → AI → DB
```

### AI tests

Use known reference videos and compare server output against human ground truth.

### Failure tests

Test:

- corrupt video;
- missing video;
- unsupported format;
- empty frame extraction;
- worker exception;
- database failure;
- duplicate processing.

## 11.7 Definition of Done

- [ ] Server independently processes a real mobile submission.
- [ ] Mobile result is never treated as authoritative.
- [ ] Server result is stored.
- [ ] Verification status is stored.
- [ ] Processing failures are represented explicitly.
- [ ] Mobile/server discrepancies can create a flag.
- [ ] At least one real end-to-end submission has been verified.
- [ ] Tests cover success and failure paths.
- [ ] Processing time is measured against a defined SLA.
- [ ] Verification results can be retrieved through the API.

## 11.8 Completion evidence

Save:

- API test output;
- worker logs;
- database result;
- one verified submission;
- one discrepancy/flagged submission;
- processing-time measurement.

## 11.9 Not complete if

- only the API endpoint exists;
- the server simply trusts the mobile score;
- verification runs only manually;
- failures disappear into logs;
- there is no stored final status.

## 11.10 Next dependency

Sprint 12 consumes the verified submission pipeline and adds authenticity/anti-cheat signals.

---

# Sprint 12 — Anti-Cheat & Authenticity

## 12.1 What is this sprint?

Sprint 12 determines whether an assessment appears authentic.

The objective is **not** to claim that cheating can be perfectly detected. The objective is to automatically identify obvious or suspicious cases and provide meaningful reasons for human review.

The master plan explicitly requires reasons to be stored rather than only:

```text
is_cheat = true
```

## 12.2 Goal

Detect and flag:

- multiple people;
- no person;
- wrong person;
- suspicious video structure;
- duplicated/looped frames;
- abrupt cuts;
- suspicious playback speed;
- invalid metadata;
- impossible movement;
- mobile/server score mismatch;
- duplicate official submission.

## 12.3 Detailed implementation

### A. Single-person validation

For each sampled frame:

- detect pose/person;
- count relevant people;
- determine whether the expected athlete is visible.

Aggregate over the video.

Possible outcomes:

```text
VALID_PERSON
NO_PERSON
MULTIPLE_PEOPLE
UNCERTAIN
```

Do not reject the entire submission because of one bad frame. Use configurable thresholds.

### B. Frame duplication

Detect:

- exact duplicate frames;
- near-duplicate frame sequences;
- repeated loops.

Record:

```text
duplicate_ratio
loop_detected
affected_frame_range
```

### C. Abrupt-cut detection

Look for:

- sudden visual discontinuity;
- major scene changes;
- timestamp anomalies.

Flag rather than automatically reject when confidence is insufficient.

### D. Playback-speed sanity

Compare:

- expected exercise duration;
- frame timing;
- movement speed;
- suspiciously accelerated/slowed sequences.

### E. Identity consistency

For official tests, compare the registered identity representation with the test video when this capability is approved for the implementation.

The system should produce:

```text
IDENTITY_MATCH
IDENTITY_MISMATCH
IDENTITY_UNCERTAIN
```

Avoid storing unnecessary biometric information.

### F. Impossible movement checks

Use exercise-specific physical constraints.

Examples:

```text
rep duration too short
movement displacement physically implausible
sudden impossible landmark jump
```

### G. Duplicate submission detection

Check:

- same athlete;
- same session;
- same test;
- repeated official submission;
- identical or near-identical video.

## 12.4 Flag model

Create a structured flag:

```text
flag_id
submission_id
flag_type
severity
reason
evidence
detected_at
status
reviewed_by
reviewed_at
```

Example:

```text
flag_type: MULTIPLE_PERSONS
severity: HIGH
reason: More than one person detected in 37% of sampled frames.
```

## 12.5 Flag aggregation

A submission may contain multiple flags.

Do not overwrite one reason with another.

Example:

```text
Submission
 ├── SCORE_MISMATCH
 ├── MULTIPLE_PERSONS
 └── VIDEO_CUT
```

## 12.6 Expected output

A suspicious submission should reach the dashboard as:

```text
FLAGGED
```

with enough evidence for an official to understand why.

## 12.7 Testing

Create a controlled anti-cheat dataset:

```text
Normal video
Looped video
Duplicated frames
Wrong person
Multiple people
No person
Edited/cut video
Wrong duration
Suspicious speed
Mobile/server mismatch
Duplicate submission
```

Measure:

- detected;
- not detected;
- false flag;
- reason generated.

## 12.8 Definition of Done

- [ ] Every required v1 anti-cheat check exists.
- [ ] Each flag stores a reason.
- [ ] Multiple flags can coexist.
- [ ] Deliberately tampered test videos are detected/flagged where expected.
- [ ] False positives are recorded rather than hidden.
- [ ] Dashboard/API can retrieve flag details.
- [ ] Duplicate official submissions are prevented or flagged.
- [ ] No raw `is_cheat` boolean is used as the only evidence.
- [ ] Anti-cheat decisions are auditable.

## 12.9 Not complete if

- only a boolean cheat field exists;
- there is no evidence/reason;
- the system has never been tested against intentionally modified videos;
- every suspicious case is automatically rejected without a review path.

---

# Sprint 13 — Admin Dashboard Foundation

## 13.1 What is this sprint?

Sprint 13 creates the operational dashboard through which administrators control assessment sessions and inspect platform data.

The dashboard must not be treated as only a UI project. It is an operational control surface over the backend.

## 13.2 Goal

Build:

```text
/admin/login
/admin/dashboard
/admin/sessions
/admin/sessions/create
/admin/sessions/:id
/admin/submissions
/admin/submissions/:id
/admin/athletes
/admin/athletes/:id
/admin/leaderboards
/admin/analytics
```

The initial focus is authentication, dashboard shell, session management, and data retrieval.

## 13.3 Detailed implementation

### A. Admin authentication

Implement:

- login;
- session/token handling;
- logout;
- protected routes;
- role-aware access;
- unauthorized handling.

Roles should follow the final approved product specification.

### B. Dashboard shell

Create:

- navigation;
- header;
- current administrator identity;
- route protection;
- loading states;
- empty states;
- error states.

### C. Session creation

Fields:

```text
Session name
Description
Start date/time
End date/time
Duration
Allowed tests
Instructions
Status
```

### D. Session lifecycle

Use:

```text
DRAFT
SCHEDULED
ACTIVE
ENDED
DISABLED
```

Do not let frontend state alone determine whether a session is active. Backend must be authoritative.

### E. Session management

Admin should be able to:

- create;
- view;
- edit;
- enable/disable;
- inspect;
- close/end according to backend rules.

### F. Submission listing foundation

Show at least:

```text
Athlete
Session
Test
Status
Submitted time
```

Detailed review is Sprint 14.

## 13.4 Expected output

An admin can:

```text
Login
 ↓
Open Dashboard
 ↓
Create Session
 ↓
Configure Tests
 ↓
Activate Session
 ↓
See Session
 ↓
View Submission List
```

## 13.5 Testing

### Frontend

Test:

- protected routes;
- forms;
- validation;
- loading;
- errors;
- session status rendering.

### API integration

Test:

- create;
- read;
- update;
- disable;
- authorization failure.

## 13.6 Definition of Done

- [ ] Admin login works.
- [ ] Protected routes work.
- [ ] Session creation works end-to-end.
- [ ] Session states are represented correctly.
- [ ] Backend controls session truth.
- [ ] Active sessions can be retrieved by mobile.
- [ ] Admin can list sessions.
- [ ] Basic submission list is visible.
- [ ] Authorization is tested.
- [ ] Empty/error/loading states exist.

## 13.7 Not complete if

- sessions are only mocked;
- active status exists only in React state;
- protected routes can be opened directly;
- create-session works only with hard-coded data.

---

# Sprint 14 — Athlete Submission Review

## 14.1 What is this sprint?

Sprint 14 gives officials the ability to make an informed decision about an official submission.

The dashboard must expose the same evidence that the verification system used.

## 14.2 Goal

Build a review workflow:

```text
Submission List
   ↓
Submission Detail
   ↓
Review Evidence
   ↓
Approve / Reject / Request Resubmission / Flag
   ↓
Audit Record
```

## 14.3 Submission list

Display:

- athlete;
- session;
- test;
- score;
- AI status;
- server status;
- flag status;
- submitted time;
- review status.

Add filters for:

- session;
- test;
- athlete;
- status;
- flag state.

## 14.4 Review screen

Display:

### Athlete

- name;
- athlete identifier;
- relevant profile data.

### Test

- test type;
- session;
- submission time.

### Video

- secure playback;
- video metadata;
- skeleton overlay if available.

### AI evidence

- rep count;
- measurement;
- form score;
- validity;
- on-device result;
- server result.

### Anti-cheat evidence

- flag type;
- severity;
- reason;
- relevant evidence.

## 14.5 Review actions

Actions:

```text
APPROVE
REJECT
REQUEST_RESUBMISSION
FLAG
```

Every action must generate an audit event.

Example:

```text
review_action
reviewer_id
submission_id
old_status
new_status
reason
timestamp
```

## 14.6 Resubmission flow

If resubmission is requested:

- store the reason;
- notify/mark the athlete;
- keep the original submission;
- do not destroy audit history;
- permit a new submission only according to session rules.

## 14.7 Expected output

An official can open one submission and answer:

- Who performed it?
- Which session/test?
- What did the mobile AI calculate?
- What did the server calculate?
- Was anything suspicious?
- What decision was made?
- Who made the decision and when?

## 14.8 Definition of Done

- [ ] Submission list works.
- [ ] Filters work.
- [ ] Detail page exposes complete verification context.
- [ ] Secure video playback works.
- [ ] Flags are visible.
- [ ] Approve works.
- [ ] Reject works.
- [ ] Resubmission request works.
- [ ] Flag action works.
- [ ] Every action creates an audit record.
- [ ] Athlete-facing status can reflect the decision.
- [ ] No review action silently changes data.

---

# Sprint 15 — Results, History & Benchmarking

## 15.1 What is this sprint?

Sprint 15 converts individual test results into understandable athlete information.

The system now needs to answer:

```text
What was my score?
What is my personal best?
How did I perform previously?
How does my result compare with the correct benchmark cohort?
```

## 15.2 Goal

Implement:

- result screen;
- history;
- personal bests;
- benchmark comparison.

## 15.3 Athlete result screen

Show:

```text
Test
Score
Form Score
Validity
Benchmark
Personal Best
Previous Attempt
Status
```

For official results, distinguish clearly between:

```text
PROVISIONAL
VERIFIED
FLAGGED
REJECTED
APPROVED
```

## 15.4 History

Each history entry should include:

```text
Date
Test
Score
Status
Practice / Session
```

Add pagination if needed.

## 15.5 Personal bests

Define exactly what constitutes a best.

Examples:

- highest valid rep count;
- highest jump height;
- lowest time where lower is better.

Do not mix invalid, rejected, or flagged results into personal-best calculations unless the approved product rules explicitly allow it.

## 15.6 Benchmarking

The master plan calls for cohort dimensions such as:

- age group;
- gender;
- region;
- test;
- score band.

The official SAI benchmark dataset must be verified before being presented as official.

Until verified, label benchmark information appropriately.

## 15.7 Expected output

Athlete sees:

```text
My Result
   ↓
Verification Status
   ↓
Personal Best
   ↓
Previous Results
   ↓
Benchmark Comparison
```

## 15.8 Definition of Done

- [ ] Results are displayed correctly.
- [ ] History contains previous attempts.
- [ ] Practice and official attempts are distinguishable.
- [ ] Personal best calculation is tested.
- [ ] Invalid/rejected results are handled correctly.
- [ ] Benchmark cohort logic is implemented.
- [ ] Benchmark source/status is documented.
- [ ] No unverified SAI benchmark is presented as official.
- [ ] API and UI agree on result values.

---

# Sprint 16 — Leaderboards & Analytics

## 16.1 What is this sprint?

Sprint 16 adds aggregated views for athletes and administrators.

It should answer:

```text
Who performed strongly within the selected cohort?
How many athletes participated?
How many submissions were verified?
Where are failures happening?
```

## 16.2 Goal

Build:

- athlete leaderboard;
- admin analytics;
- filters;
- charts;
- aggregation APIs.

## 16.3 Athlete leaderboard

Filters:

- test;
- age group;
- region;
- session.

Respect privacy requirements and any opt-in rules.

## 16.4 Admin analytics

Metrics:

```text
Total athletes
Total submissions
Verified submissions
Flagged submissions
Rejected submissions
Average scores
Regional performance
Test participation
Session participation
Upload failures
Verification time
```

## 16.5 Charts

Implement:

- participation over time;
- regional distribution;
- test performance;
- verification status;
- score distribution.

## 16.6 Backend aggregation

Do not calculate large datasets entirely in the browser.

Create backend/API aggregation where appropriate.

Cache expensive analytics using the approved Redis architecture when useful.

## 16.7 Expected output

Admin dashboard:

```text
Filters
   ↓
Backend aggregation
   ↓
KPIs + charts
```

Athlete:

```text
Selected cohort
   ↓
Leaderboard
```

## 16.8 Definition of Done

- [ ] Leaderboards work with filters.
- [ ] Analytics metrics are backed by real database data.
- [ ] Charts match API values.
- [ ] Empty states work.
- [ ] Large result sets are paginated/aggregated appropriately.
- [ ] Privacy controls are enforced.
- [ ] Admin-only analytics are protected.
- [ ] At least one analytics dataset is manually verified against DB queries.

---

# Sprint 17 — Notifications

## 17.1 What is this sprint?

Sprint 17 connects important platform events to athlete/admin notifications.

Notifications should communicate events that require awareness or action.

## 17.2 Goal

Support:

- session opened;
- session ending soon;
- submission received;
- verification completed;
- submission flagged;
- submission approved;
- submission rejected;
- resubmission requested.

The master plan allows Firebase Cloud Messaging or the final approved notification provider.

## 17.3 Event architecture

Use an event-oriented flow:

```text
Platform Event
   ↓
Notification Service
   ↓
Provider
   ↓
Device
```

Do not place notification logic directly inside every controller.

## 17.4 Notification record

Store:

```text
notification_id
recipient_id
event_type
title
body
created_at
sent_at
delivery_status
read_at
```

## 17.5 Reliability

Handle:

- provider failure;
- invalid device token;
- duplicate events;
- retry;
- expired token.

## 17.6 Expected output

Example:

```text
Submission approved
       ↓
Notification event
       ↓
FCM
       ↓
Athlete receives notification
```

## 17.7 Definition of Done

- [ ] Notification provider is integrated.
- [ ] Required events trigger notifications.
- [ ] Duplicate notification behavior is controlled.
- [ ] Invalid tokens are handled.
- [ ] Delivery/read state is tracked where supported.
- [ ] Notification failures do not break core assessment processing.
- [ ] User can understand the action/status from the notification.

---

# Sprint 18 — Additional Test Battery

## 18.1 What is this sprint?

Sprint 18 expands beyond the core pose-based exercise system.

The master plan identifies:

- Sit-ups;
- Shuttle Run;
- Endurance Run.

These tests have different technical characteristics, especially Shuttle Run and Endurance Run.

## 18.2 Goal

Add the remaining assessment tests without destabilizing the already-working core exercises.

---

## 18.3 Sit-ups

### Implementation

Build:

- pose-based rep counter;
- torso-angle calculation;
- up/down state machine;
- full-range validation;
- invalid rep rejection;
- local result;
- server verification.

### Output

```text
Sit-up Test
 ↓
Rep Count
 ↓
Validity
 ↓
Server Verification
```

### Definition of Done

- [ ] Sit-up rep counting works.
- [ ] Partial reps are rejected according to the defined rule.
- [ ] Results are stored.
- [ ] Server independently verifies the result.
- [ ] Reference-video accuracy is measured.

---

## 18.4 Shuttle Run

### Implementation

Use the approved combination of:

- movement tracking;
- direction-change detection;
- accelerometer/gyroscope;
- optional camera verification;
- time measurement.

The test instructions must define the marked-court setup because the environment affects measurement.

### Output

```text
Start
 ↓
Movement
 ↓
Direction Change
 ↓
Finish
 ↓
Elapsed Time
 ↓
Validation
```

### Definition of Done

- [ ] Test setup is clearly defined.
- [ ] Start/finish detection works.
- [ ] Direction changes are detected.
- [ ] Time is measured.
- [ ] Server verification exists.
- [ ] Manual reference runs validate accuracy.

---

## 18.5 Endurance Run

### Implementation

Use:

- GPS;
- distance;
- time;
- pace;
- motion validation;
- server verification.

Video is secondary evidence; telemetry is primary.

### Definition of Done

- [ ] GPS tracking works.
- [ ] Distance is calculated.
- [ ] Time is calculated.
- [ ] Pace is calculated.
- [ ] Motion validation exists.
- [ ] Server verification exists.
- [ ] Reference runs validate the result.

## 18.6 Overall Sprint 18 Definition of Done

- [ ] All required additional tests are implemented.
- [ ] Mobile results work.
- [ ] Backend accepts the new test types.
- [ ] Server verification exists.
- [ ] Dashboard can display them.
- [ ] Benchmarking supports them where data exists.
- [ ] Existing tests are not regressed.
- [ ] Accuracy is documented.

---

# Sprint 19 — UX, Languages & Accessibility

## 19.1 What is this sprint?

Sprint 19 makes the product usable by the broad athlete population targeted by F4ALL.

The master plan specifies initial language support:

- English;
- Tamil;
- Hindi.

## 19.2 Goal

Improve:

- instructions;
- demonstrations;
- navigation;
- error recovery;
- offline status;
- upload status;
- result explanations;
- language support;
- accessibility.

## 19.3 Localization

Move all user-facing strings out of hard-coded UI code.

Use a translation structure such as:

```text
English
Tamil
Hindi
```

Do not translate only the happy path.

Translate:

- errors;
- validation messages;
- permissions;
- loading;
- offline states;
- result states;
- notification text;
- accessibility labels.

## 19.4 Exercise instructions

Every test should explain:

1. required setup;
2. camera placement;
3. body position;
4. gesture;
5. start countdown;
6. expected movement;
7. completion;
8. result.

Add demonstration visuals/video where approved.

## 19.5 Accessibility

Test:

- dynamic font sizes;
- contrast;
- screen-reader labels;
- touch target size;
- understandable errors.

## 19.6 Expected output

A first-time user should be able to:

```text
Open
 ↓
Understand
 ↓
Start test
 ↓
Recover from an error
 ↓
Complete
 ↓
Understand result
```

without developer assistance.

## 19.7 Definition of Done

- [ ] English complete.
- [ ] Tamil complete.
- [ ] Hindi complete.
- [ ] No major user-facing strings remain hard-coded.
- [ ] Instructions exist for every test.
- [ ] Offline/upload status is understandable.
- [ ] Dynamic font sizing tested.
- [ ] Screen-reader labels tested on key screens.
- [ ] Contrast checked.
- [ ] A non-technical user can complete the flow without help.

---

# Sprint 20 — Security & Privacy

## 20.1 What is this sprint?

Sprint 20 is the release-security hardening sprint.

F4ALL handles:

- athlete identity;
- phone numbers;
- videos;
- face/identity information;
- location;
- potentially minor data.

Therefore security is a release requirement.

## 20.2 Goal

Protect:

- identity;
- authentication;
- videos;
- APIs;
- database;
- admin access;
- logs;
- secrets;
- personal data.

## 20.3 Transport security

Verify:

```text
Mobile
  ↓ HTTPS/TLS
Backend
  ↓ encrypted connection
Database / Storage
```

No production HTTP endpoints for sensitive traffic.

## 20.4 Storage security

Verify:

- private video bucket;
- encryption at rest;
- signed URLs;
- restricted bucket permissions;
- no public video access.

## 20.5 Authentication security

Implement/review:

- secure token handling;
- token expiry;
- refresh strategy if applicable;
- OTP abuse prevention;
- login rate limiting;
- brute-force protection.

## 20.6 API security

Test:

- input validation;
- authorization;
- object-level authorization;
- rate limiting;
- oversized uploads;
- malicious parameters;
- invalid IDs;
- unauthorized admin operations.

## 20.7 Secrets

No:

```text
API keys in Git
JWT secrets in source
AWS credentials in source
FCM credentials in source
```

Use approved secret management/environment configuration.

## 20.8 Privacy

Document:

- what data is collected;
- why it is collected;
- who can access it;
- how long it is retained;
- deletion/retention rules;
- consent;
- minor/parental consent where applicable.

## 20.9 Audit logs

Record security-sensitive events such as:

- admin login;
- review action;
- approval;
- rejection;
- resubmission;
- permission-sensitive changes.

## 20.10 Definition of Done

- [ ] HTTPS/TLS verified.
- [ ] Private video storage verified.
- [ ] Signed URL access verified.
- [ ] Encryption requirements verified.
- [ ] Authentication hardened.
- [ ] Rate limiting exists.
- [ ] Input validation exists.
- [ ] Secrets removed from source.
- [ ] Dependency vulnerability scan completed.
- [ ] Audit logs verified.
- [ ] Data retention policy documented.
- [ ] Consent flow documented/implemented.
- [ ] Role-based admin access tested.
- [ ] Security issues found during testing are tracked to closure or formally accepted.

## 20.11 Not complete if

- the app is secure only because the frontend hides buttons;
- S3/video files are publicly accessible;
- secrets exist in Git;
- admin APIs lack authorization;
- there is no retention policy.

---

# Sprint 21 — Device Compatibility

## 21.1 What is this sprint?

Sprint 21 proves the application is not dependent on the developer's phone.

The project explicitly targets low-end Android devices and rural/low-bandwidth conditions.

## 21.2 Goal

Test at least:

```text
2–3 entry/low-end devices
2–3 mid-range devices
```

The exact device list should be recorded in the test report.

## 21.3 Test matrix

For every device test:

- Android version;
- RAM;
- CPU;
- storage;
- camera;
- FPS;
- pose accuracy;
- battery drain;
- heat;
- memory usage;
- upload;
- offline mode;
- background sync;
- app lifecycle.

## 21.4 Camera tests

Test:

- first camera start;
- repeated start/stop;
- orientation;
- permission changes;
- background/foreground;
- app resume;
- multiple attempts;
- camera release.

## 21.5 AI performance

Measure:

```text
FPS
Inference time
Dropped frames
CPU usage
Memory
Battery
Temperature
```

Do not use only subjective statements such as "it feels smooth."

## 21.6 Offline tests

For each device:

```text
Record offline
Close app
Reopen
Reconnect
Sync
```

## 21.7 Expected output

Create a device matrix:

| Device | Camera | FPS | Pose | Battery | Heat | Memory | Offline | Sync | Result |
|---|---|---:|---|---|---|---|---|---|---|
| Device A | PASS/FAIL | measured | PASS/FAIL | measured | measured | measured | PASS/FAIL | PASS/FAIL | PASS/FAIL |
| Device B | PASS/FAIL | measured | PASS/FAIL | measured | measured | measured | PASS/FAIL | PASS/FAIL | PASS/FAIL |

## 21.8 Definition of Done

- [ ] Full device matrix tested.
- [ ] Low-end devices included.
- [ ] No critical camera lifecycle failure.
- [ ] No unacceptable memory crash.
- [ ] AI performance measured.
- [ ] Battery/heat measured.
- [ ] Offline mode tested.
- [ ] Background sync tested.
- [ ] Known device limitations documented.
- [ ] Release minimum-device requirements documented.

---

# Sprint 22 — Accuracy Validation

## 22.1 What is this sprint?

Sprint 22 provides the formal evidence that the AI actually measures what the project claims it measures.

This is different from "the algorithm works on my videos."

## 22.2 Goal

Create a formal validation dataset with human ground truth.

For every exercise:

```text
Video
Human Ground Truth
AI Result
Absolute Error
Validity
Device
Lighting
Distance
Camera Angle
```

## 22.3 Ground truth process

For each reference video:

1. record the video;
2. have a human evaluator determine the expected result;
3. run the same video through the AI;
4. record AI result;
5. calculate error;
6. classify the outcome;
7. store metadata.

## 22.4 Metrics

Track:

### Rep counting

```text
Rep-count accuracy
False positive rate
False negative rate
```

### Measurement

```text
Absolute error
Mean error
Maximum error
```

### Form classification

```text
Correct classification
Incorrect classification
```

### Cheat detection

```text
True detection
False detection
Missed detection
```

## 22.5 Dataset diversity

Include variation in:

- people;
- body proportions;
- clothing;
- lighting;
- camera distance;
- camera angle;
- background;
- device capability.

## 22.6 Example

```text
Human reps: 20
AI reps:    19
Absolute error: 1
```

## 22.7 Output

Create an accuracy report per test:

```text
Test: Squat

Dataset size:
Human ground truth:
AI result:
Mean error:
Max error:
False positives:
False negatives:
Device distribution:
Known limitations:
```

## 22.8 Definition of Done

- [ ] Formal dataset exists.
- [ ] Human ground truth exists.
- [ ] AI results are recorded.
- [ ] Error metrics are calculated.
- [ ] Each core exercise has a report.
- [ ] Test conditions are recorded.
- [ ] Accuracy targets are compared with actual results.
- [ ] Poor-performing cases are documented.
- [ ] No accuracy claim is made without evidence.

---

# Sprint 23 — Backend Load & Reliability

## 23.1 What is this sprint?

Sprint 23 proves that the backend remains usable when many athletes submit assessments at the same time.

The system must handle:

```text
Many uploads
Many verification jobs
Queue backlog
Worker failures
Database pressure
Storage pressure
```

## 23.2 Goal

Validate:

- API throughput;
- upload handling;
- worker queues;
- database;
- Redis;
- object storage;
- recovery.

## 23.3 Load scenarios

Test:

### Scenario A — Concurrent uploads

Multiple athletes upload simultaneously.

Measure:

- request latency;
- upload completion;
- error rate.

### Scenario B — Verification backlog

Send more videos than workers can process immediately.

Verify:

```text
UPLOAD
  ↓
QUEUE
  ↓
WORKER 1
WORKER 2
...
```

No submission should disappear.

### Scenario C — Worker crash

Kill a worker during processing.

Expected:

```text
Job remains recoverable
```

### Scenario D — API failure

Temporarily make API unavailable.

Mobile should retain its offline/queued state.

### Scenario E — Database failure

Verify the system fails safely and does not create contradictory result states.

### Scenario F — Redis unavailable

Verify queue-dependent components fail clearly and recover after Redis returns.

### Scenario G — Interrupted upload

Resume from the correct point rather than forcing the entire upload again.

## 23.4 Tools

The master plan suggests:

- Locust;
- k6.

Choose one and document the test configuration.

## 23.5 Reliability rules

The system must avoid:

```text
uploaded but missing
verified but no result
approved without verification
duplicate official result
partial database state
```

## 23.6 Metrics

Record:

- requests/sec;
- p50/p95/p99 latency;
- upload success rate;
- queue wait time;
- processing time;
- worker failure rate;
- DB errors;
- retry count;
- duplicate processing;
- recovery time.

## 23.7 Definition of Done

- [ ] Concurrent uploads tested.
- [ ] Queue backlog tested.
- [ ] Worker crash tested.
- [ ] API failure tested.
- [ ] DB failure tested.
- [ ] Redis failure tested.
- [ ] Upload interruption tested.
- [ ] No official result corruption observed.
- [ ] Recovery behavior documented.
- [ ] Load target is explicitly defined.
- [ ] System meets or formally documents gaps against that target.

---

# Sprint 24 — End-to-End Pilot

## 24.1 What is this sprint?

Sprint 24 is the final real-world validation sprint before treating the platform as a release candidate.

This is not another unit-testing sprint.

It is the complete journey with real users under realistic conditions.

## 24.2 Goal

Validate the complete system:

```text
Athlete
  ↓
Registration
  ↓
OTP
  ↓
Profile
  ↓
Face Verification
  ↓
Practice
  ↓
Session
  ↓
Gesture
  ↓
Countdown
  ↓
Exercise
  ↓
Result
  ↓
Upload
  ↓
Backend
  ↓
Server Verification
  ↓
Flag / Approve
  ↓
Admin Review
  ↓
Audit
```

## 24.3 Pilot participants

Recruit a small real pilot group.

Ideally include:

- different experience levels;
- different devices;
- low-connectivity users;
- at least one rural/low-connectivity environment where practical.

## 24.4 Pilot protocol

Before testing:

1. define participant list;
2. define test battery;
3. define test instructions;
4. define human evaluator procedure;
5. define data collection sheet;
6. define incident reporting process.

## 24.5 Athlete-side observation

Record:

- registration issues;
- OTP issues;
- instruction confusion;
- gesture failures;
- camera failures;
- exercise detection errors;
- form-feedback confusion;
- upload problems;
- offline behavior;
- result understanding.

## 24.6 Human ground truth

A human coach/evaluator should independently score the same tests where applicable.

Compare:

```text
Human result
vs
Mobile result
vs
Server result
```

## 24.7 Backend observation

Track:

- API errors;
- queue time;
- processing time;
- verification failures;
- storage issues;
- notification failures.

## 24.8 Admin observation

Verify:

- session creation;
- submission arrival;
- review;
- video playback;
- flag visibility;
- approval;
- rejection;
- resubmission;
- audit history.

## 24.9 Pilot issue prioritization

Classify issues:

```text
P0 — Blocks official assessment
P1 — Major user/system problem
P2 — Usability problem
P3 — Minor improvement
```

Fix P0/P1 issues before release.

## 24.10 Benchmark and anti-cheat tuning

Use real pilot data to identify:

- overly strict thresholds;
- overly loose thresholds;
- false flags;
- missed suspicious cases;
- benchmark assumptions that need correction.

Do not silently change thresholds. Record each change.

## 24.11 Pilot report

Produce:

```text
Pilot Summary
Participants
Devices
Locations / connectivity conditions
Tests performed
Accuracy results
User feedback
System failures
Anti-cheat observations
Security observations
Performance observations
Known limitations
Fixes completed
Remaining risks
Post-MVP roadmap
Release recommendation
```

## 24.12 Definition of Done

- [ ] Real users completed the end-to-end journey.
- [ ] Real device data was collected.
- [ ] Real connectivity conditions were tested.
- [ ] Human ground truth was collected where applicable.
- [ ] Mobile/server differences were analyzed.
- [ ] Admin workflow was exercised.
- [ ] Major issues were fixed or explicitly documented.
- [ ] Accuracy data is available.
- [ ] Known limitations are documented.
- [ ] Pilot report is completed.
- [ ] A clear post-MVP roadmap exists.

---

# Final Release Gate — F4ALL v1.0 Candidate

After Sprint 24, do not simply say "project completed."

Run this release gate.

## Mobile

- [ ] Registration works.
- [ ] OTP works.
- [ ] Login works.
- [ ] Profile works.
- [ ] Face verification works where required.
- [ ] Practice Mode works.
- [ ] Session Mode works.
- [ ] Gesture verification works.
- [ ] Countdown works.
- [ ] Squat works.
- [ ] Push-up works.
- [ ] Bicep curl works.
- [ ] Lunge works.
- [ ] Vertical jump works.
- [ ] Additional required tests work.
- [ ] Form correction works.
- [ ] Voice feedback works.
- [ ] Results work.
- [ ] History works.
- [ ] Offline queue works.
- [ ] Background sync works.
- [ ] Camera restarts without app restart.
- [ ] No black-screen camera issue.
- [ ] No stale MediaPipe state.
- [ ] Low-end devices tested.

## Backend

- [ ] Authentication API works.
- [ ] Athlete API works.
- [ ] Session API works.
- [ ] Submission API works.
- [ ] Upload API works.
- [ ] Server verification works.
- [ ] Anti-cheat works.
- [ ] Result finalization works.
- [ ] Notifications work.
- [ ] Audit logging works.
- [ ] Error handling works.
- [ ] Monitoring works.
- [ ] Load testing completed.

## Dashboard

- [ ] Admin login works.
- [ ] Dashboard works.
- [ ] Session creation works.
- [ ] Session editing works.
- [ ] Session enable/disable works.
- [ ] Submission list works.
- [ ] Submission details work.
- [ ] Video review works.
- [ ] AI result visible.
- [ ] Server result visible.
- [ ] Flag reasons visible.
- [ ] Approve works.
- [ ] Reject works.
- [ ] Resubmission works.
- [ ] Athlete list works.
- [ ] Athlete history works.
- [ ] Leaderboards work.
- [ ] Analytics work.
- [ ] Audit trail works.

## Security

- [ ] HTTPS/TLS.
- [ ] Secure authentication.
- [ ] Role-based access.
- [ ] Private video storage.
- [ ] Signed URLs.
- [ ] Encryption.
- [ ] Rate limiting.
- [ ] Input validation.
- [ ] Audit logs.
- [ ] Consent.
- [ ] Data retention policy.

## Testing

- [ ] Unit tests.
- [ ] Integration tests.
- [ ] API tests.
- [ ] UI tests.
- [ ] AI accuracy tests.
- [ ] Device tests.
- [ ] Offline tests.
- [ ] Network failure tests.
- [ ] Load tests.
- [ ] Security tests.
- [ ] Pilot test.

---

# Standard Sprint Completion Report

Use this exact format after every sprint from Sprint 11 onward.

```text
SPRINT:
GOAL:

WHAT WAS IMPLEMENTED:
1.
2.
3.

EXPECTED OUTPUT:
1.
2.
3.

FILES / MODULES CHANGED:
1.
2.
3.

DATABASE CHANGES:
1.
2.

API CHANGES:
1.
2.

TESTS:
- Unit:
- Integration:
- API:
- Device:
- AI / Accuracy:
- Manual:

TEST RESULTS:
- Passed:
- Failed:
- Skipped:

KNOWN ISSUES:
1.
2.

DEFINITION OF DONE:
[ ] Complete
[ ] Pending

EVIDENCE:
- Screenshot:
- Logs:
- Test report:
- Accuracy report:

GIT:
- Branch:
- Commit:
- PR:

NEXT SPRINT:
```

---

# Sprint Dependency Map

```text
Sprint 11
Server Verification
      │
      ▼
Sprint 12
Anti-Cheat
      │
      ▼
Sprint 13
Admin Dashboard
      │
      ▼
Sprint 14
Submission Review
      │
      ▼
Sprint 15
Results + History + Benchmarking
      │
      ▼
Sprint 16
Analytics + Leaderboards
      │
      ▼
Sprint 17
Notifications
      │
      ▼
Sprint 18
Additional Test Battery
      │
      ▼
Sprint 19
UX + Languages + Accessibility
      │
      ▼
Sprint 20
Security + Privacy
      │
      ▼
Sprint 21
Device Compatibility
      │
      ▼
Sprint 22
Accuracy Validation
      │
      ▼
Sprint 23
Load + Reliability
      │
      ▼
Sprint 24
Real-World Pilot
      │
      ▼
F4ALL v1.0 Release Candidate
```

---

# Important Project Rule

Do not rewrite working F4ALL modules merely to make the code look different.

Preserve:

- existing MediaPipe integration;
- CameraX pipeline;
- existing exercise logic;
- existing squat implementation;
- existing vertical-jump implementation;
- correct gesture logic;
- existing result calculation;
- Kotlin/Compose structure.

The implementation strategy remains:

```text
KEEP WORKING CODE
      +
FIX BUGS
      +
EXTRACT REUSABLE COMPONENTS
      +
ADD MISSING FEATURES
      =
FULL F4ALL
```

---

# Final Outcome

When Sprint 24 is complete, F4ALL should have a validated end-to-end path:

```text
ATHLETE
  ↓
Register
  ↓
OTP
  ↓
Profile / Identity
  ↓
Practice
  ↓
Official Session
  ↓
Gesture
  ↓
Countdown
  ↓
AI Exercise
  ↓
Local Provisional Result
  ↓
Offline Queue
  ↓
Upload
  ↓
Server Verification
  ↓
Anti-Cheat
  ↓
Final Verification
  ↓
ADMIN DASHBOARD
  ↓
Review
  ↓
Approve / Reject / Resubmit
  ↓
Audit Trail
  ↓
History / Benchmark / Analytics
  ↓
Pilot Validation
  ↓
F4ALL v1.0 Release Candidate
```

**The project is not considered complete because every screen exists. It is complete when the entire journey works reliably, the AI results have measured accuracy, official submissions are server-verified, suspicious submissions are explainably flagged, the dashboard workflow is auditable, security requirements are satisfied, and real users have completed the pilot.**
