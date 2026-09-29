# F4ALL — Full Implementation & Sprint Completion Plan

## 1. Purpose

This document is the **master implementation roadmap for completing the full F4ALL / SAI Sports Talent Assessment Platform**.

It combines:

1. The current product architecture and sprint plan documented in the existing README.
2. Features that were previously discussed for the actual F4ALL application but are missing or under-specified in the README.
3. A practical implementation order so the existing Android app can be taken from the current Sprint 3 state to a complete, testable platform.

The goal is not to rewrite working modules unnecessarily. Existing working functionality should be preserved and extended incrementally.

---

# 2. Current README vs Actual F4ALL Scope

The current README defines the broad platform correctly:

- Android native Kotlin + Jetpack Compose
- CameraX
- MediaPipe Pose Landmarker
- TFLite
- Offline-first processing
- Room + WorkManager
- FastAPI backend
- Server-side re-verification
- PostgreSQL/S3 architecture
- Cheat detection
- Athlete profiles
- Official dashboard
- Benchmarking
- Shuttle Run and Endurance Run
- Security, pilot testing and hardening

However, several previously discussed application-level features are either missing or not sufficiently represented.

## Missing / under-specified features

### Athlete application

- Register/Login flow
- OTP verification
- Face verification during registration/login
- Athlete profile completion
- DOB, place, city, state and achievements
- Practice Mode
- Session Mode
- Session availability controlled by admin
- One submission per session/test
- Test history
- Previous attempt/result viewing
- Personal bests
- Test-specific instructions
- Countdown before starting a test
- Gesture verification before camera/test start
- Camera lifecycle/restart handling
- Voice feedback during exercise
- Real-time form correction
- Anti-cheat randomization
- Result status: provisional / submitted / verified / flagged / rejected
- Offline pending queue visible to athlete

### Exercise / assessment coverage

The previous F4ALL implementation discussions covered:

1. Squats
2. Push-ups
3. Bicep curls
4. Lunges
5. Vertical jump

The original README primarily describes:

1. Vertical jump
2. Sit-ups
3. Shuttle run
4. Endurance run

Therefore the final implementation must explicitly define which exercises belong to the **F4ALL product battery** and implement the selected exercises consistently on mobile, backend and dashboard.

For the current F4ALL implementation, the working exercise roadmap should prioritize:

- Squat
- Push-up
- Bicep curl
- Lunge
- Vertical jump

Then add:

- Sit-up
- Shuttle run
- Endurance run

where required by the final SAI assessment specification.

### Admin dashboard

The README mentions a generic official dashboard, but the actual discussed dashboard requires:

- Admin login
- Session creation
- Session name
- Session duration
- Test selection
- Session enable/disable
- Active session visibility in mobile app
- Athlete submission list
- Session-specific submissions
- Submission details
- Video/result review
- AI score and final score
- Approve
- Reject
- Resubmission/request review
- Flag reasons
- Athlete/session filtering
- Audit history

### Backend/data model

The README proposes PostgreSQL/S3, while earlier implementation discussions also referenced MongoDB/Cloudinary.

The architecture must be finalized before production implementation.

The recommended final direction for the full platform is:

- PostgreSQL for structured platform data
- S3 for videos in production
- Redis for queues/cache
- FastAPI backend
- Celery/background workers

If the current prototype is already using MongoDB/Cloudinary, migration should be treated as a controlled architecture task rather than rewriting the application immediately.

---

# 3. Final F4ALL Product Architecture

```text
                         ┌───────────────────────────┐
                         │       ATHLETE APP         │
                         │ Android / Kotlin / Compose │
                         └─────────────┬─────────────┘
                                       │
                  ┌────────────────────┼────────────────────┐
                  │                    │                    │
                  ▼                    ▼                    ▼
             Authentication       Practice Mode       Session Mode
             OTP + Face           Unlimited attempts   Admin-created
                  │                    │                session
                  └────────────────────┼────────────────────┘
                                       ▼
                              Test Instructions
                                       │
                                       ▼
                              Gesture Verification
                                       │
                                       ▼
                                  Countdown
                                       │
                                       ▼
                           CameraX + MediaPipe
                                       │
                    ┌──────────────────┼─────────────────┐
                    ▼                  ▼                 ▼
                Rep Count         Form Feedback      Measurement
                    │                  │                 │
                    └──────────────────┼─────────────────┘
                                       ▼
                              Local Provisional Result
                                       │
                                       ▼
                               Room Offline Queue
                                       │
                                       ▼
                           WorkManager Background Sync
                                       │
                                       ▼
                         FastAPI Ingest / Upload API
                                       │
                    ┌──────────────────┼─────────────────┐
                    ▼                  ▼                 ▼
                   S3               PostgreSQL        Redis
                Video Store       Platform Data      Queue/Cache
                                       │
                                       ▼
                              Server-side Recheck
                                       │
                    ┌──────────────────┼─────────────────┐
                    ▼                  ▼                 ▼
              Pose Re-analysis   Cheat Detection    Identity Check
                    │                  │                 │
                    └──────────────────┼─────────────────┘
                                       ▼
                              Final Verification
                                       │
                                       ▼
                              ADMIN DASHBOARD
                                       │
                    ┌──────────────────┼─────────────────┐
                    ▼                  ▼                 ▼
                Sessions          Submissions       Analytics
                    │                  │                 │
                    ▼                  ▼                 ▼
                 Create             Review           Leaderboards
                 Manage             Approve          Regional Stats
                                   Reject
                                   Flag
```

---

# 4. Final Feature Matrix

| Module | Feature | Priority | Status |
|---|---|---:|---|
| Authentication | Registration | P0 | To verify |
| Authentication | Login | P0 | To verify |
| Authentication | OTP verification | P0 | To implement/verify |
| Authentication | Face verification | P0 | To implement/verify |
| Profile | Athlete profile | P0 | To implement/verify |
| Profile | Achievements | P1 | To implement |
| Practice | Practice Mode | P0 | To implement/verify |
| Session | Session Mode | P0 | To implement |
| Session | Admin-created sessions | P0 | To implement |
| Session | One submission per session | P0 | To implement |
| Exercise | Squat | P0 | Existing/verify |
| Exercise | Push-up | P0 | To implement |
| Exercise | Bicep curl | P0 | To implement |
| Exercise | Lunge | P0 | To implement |
| Exercise | Vertical jump | P0 | Existing/verify |
| Exercise | Sit-up | P1 | Later |
| Exercise | Shuttle run | P1 | Later |
| Exercise | Endurance run | P1 | Later |
| AI | MediaPipe pose | P0 | Existing |
| AI | Rep counting | P0 | Existing/continue |
| AI | Form correction | P0 | To complete |
| AI | Voice feedback | P0 | To implement/verify |
| AI | Gesture verification | P0 | Existing/verify |
| AI | Anti-cheat | P0 | To implement |
| Camera | Camera lifecycle | P0 | Must harden |
| Camera | Countdown | P0 | To implement/verify |
| Storage | Local Room queue | P0 | To implement |
| Sync | Background upload | P0 | To implement |
| Backend | Upload API | P0 | To implement |
| Backend | Server re-verification | P0 | To implement |
| Backend | Result finalization | P0 | To implement |
| Dashboard | Admin login | P0 | To implement |
| Dashboard | Session creation | P0 | To implement |
| Dashboard | Session management | P0 | To implement |
| Dashboard | Athlete submissions | P0 | To implement |
| Dashboard | Video review | P0 | To implement |
| Dashboard | Approve/reject | P0 | To implement |
| Dashboard | Flag reasons | P0 | To implement |
| Dashboard | Leaderboards | P1 | To implement |
| Dashboard | Analytics | P1 | To implement |
| UX | Multi-language | P1 | Later |
| UX | Accessibility | P1 | Later |
| Security | Encryption | P0 | To implement |
| Security | Audit logs | P0 | To implement |
| Testing | Device matrix | P0 | To implement |
| Testing | Accuracy validation | P0 | Ongoing |
| Testing | Field pilot | P0 | Final phase |

---

# 5. Sprint Strategy

The previous README used a broad 14-sprint roadmap. For the actual F4ALL project, implementation should be reorganized around the current application state.

## Phase A — Finish the existing AI mobile foundation

### Sprint 3 — Rep Counting & Measurement

**Goal:** Finish reliable exercise scoring.

### Tasks

#### Squat

- Stable standing calibration
- Down/up state machine
- Knee/hip angle thresholds
- Valid rep detection
- Partial rep rejection
- Incorrect-form detection
- Rep counter
- Set/test completion

#### Push-up

- Shoulder/hip/elbow angle calculation
- Down/up state machine
- Body alignment check
- Invalid repetition detection
- Rep counter

#### Bicep curl

- Elbow angle calculation
- Curl/down state machine
- Range-of-motion threshold
- Wrong-arm/wrong-angle detection
- Rep counter

#### Lunge

- Knee angle calculation
- Down/up state machine
- Front-knee alignment
- Depth validation
- Rep counter

#### Vertical jump

- Standing baseline
- Jump detection
- Peak detection
- Displacement calculation
- Jump height calculation
- Landing detection

### Definition of Done

Every supported exercise:

- starts reliably
- detects repetitions/measurement
- rejects invalid movements
- produces a provisional result
- works across multiple test videos/devices
- has measurable accuracy results

---

# 6. Sprint 4 — Real-Time Form Correction & Voice Feedback

**Goal:** Make F4ALL an actual AI fitness form-correction system, not only a counter.

### Tasks

- Define exercise-specific form rules.
- Calculate relevant joint angles.
- Detect bad posture.
- Detect insufficient depth/range.
- Detect excessive movement.
- Detect incorrect body alignment.
- Display real-time feedback.
- Add voice feedback.
- Avoid repeating the same voice warning continuously.
- Add cooldown/debounce for feedback.
- Show positive feedback for correct repetitions.
- Store detected form issues.
- Add final form-quality summary.

### Example

```text
Squat
 ├── Depth
 ├── Knee alignment
 ├── Back/torso angle
 ├── Stability
 └── Rep validity

Push-up
 ├── Body alignment
 ├── Elbow angle
 ├── Depth
 └── Rep validity
```

### Definition of Done

An athlete can perform an exercise and receive useful real-time visual/voice feedback while the test is running.

---

# 7. Sprint 5 — Gesture Verification & Camera Reliability

**Goal:** Make test initiation reliable.

### Tasks

- Gesture verification screen.
- Test-specific gesture.
- Gesture timeout.
- Clear success/failure state.
- Countdown: 3 → 2 → 1.
- Start camera only after successful verification.
- Stop/release camera correctly after test.
- Reinitialize camera for subsequent attempts.
- Handle lifecycle changes.
- Handle permission denial.
- Handle background/foreground transitions.
- Prevent black-camera state.
- Prevent reversed left/right detection.
- Prevent stale MediaPipe instances.
- Reset all test state between attempts.

### Definition of Done

An athlete can complete:

```text
Attempt 1
→ Gesture
→ Countdown
→ Test
→ Result

Attempt 2
→ Gesture
→ Countdown
→ Test
→ Result
```

without restarting the application.

---

# 8. Sprint 6 — Practice Mode

**Goal:** Allow athletes to train without being restricted by official sessions.

### Flow

```text
Home
 ↓
Practice
 ↓
Select Exercise
 ↓
Instructions
 ↓
Gesture Verification
 ↓
Countdown
 ↓
Test
 ↓
Result
 ↓
Save History
```

### Rules

- Unlimited practice attempts.
- Every attempt stored.
- Practice results are clearly marked.
- Practice results do not become official session submissions.
- Athlete can view previous attempts.
- Personal best can be calculated.
- Form feedback is available.

### Definition of Done

An athlete can perform unlimited practice attempts and view their history.

---

# 9. Sprint 7 — Session Mode

**Goal:** Introduce official/admin-controlled assessment sessions.

### Session data

```text
Session
 ├── session_id
 ├── name
 ├── description
 ├── start_time
 ├── end_time
 ├── enabled
 ├── allowed_tests
 └── rules
```

### Mobile behavior

The app checks whether an active session exists.

If no session:

```text
No active assessment session
```

If active:

```text
Assessment Session
May 2026
Available Test
→ Squat
```

### Rules

- Only enabled sessions appear.
- Only selected tests appear.
- Athlete can submit only once per session/test.
- Duplicate official submissions are blocked.
- Practice mode remains unlimited.
- Session submissions are stored separately or clearly typed as official session attempts.

### Definition of Done

Admin creates a session → athlete sees it → completes the test → submission is stored → second attempt is blocked.

---

# 10. Sprint 8 — Authentication, Profile & Identity

**Goal:** Complete the athlete identity layer.

### Registration

Collect:

- Name
- Phone number
- OTP
- Date of birth
- Gender
- Place
- City
- State
- Height
- Weight
- Achievements
- Profile photo
- Face verification

### Login

```text
Phone
 ↓
OTP
 ↓
Authenticated
 ↓
Home
```

### Face verification

Use the registered identity image to validate the person performing an official assessment.

Important:

- Store only what is required.
- Encrypt sensitive identity data.
- Clearly obtain consent.
- Provide an explicit failure/retry flow.

### Definition of Done

An athlete can register, verify OTP, complete their profile, authenticate again and pass identity verification for an official test.

---

# 11. Sprint 9 — Offline-First Storage & Sync

**Goal:** Make the app usable with poor/no internet.

### Local state machine

```text
RECORDED
   ↓
PROCESSING
   ↓
COMPRESSED
   ↓
QUEUED
   ↓
UPLOADING
   ↓
SYNCED
```

Failure:

```text
FAILED
 ↓
RETRY
```

### Tasks

- Room entities.
- Local result storage.
- Local video metadata.
- WorkManager.
- Network detection.
- Automatic retry.
- Upload progress.
- Resumable/chunked upload.
- Compression.
- Sync status UI.
- User-visible failed upload state.

### Definition of Done

Airplane-mode test → close app → reconnect later → app automatically syncs the assessment.

---

# 12. Sprint 10 — Backend Foundation

**Goal:** Connect the mobile app to a production-style backend.

### FastAPI modules

```text
backend/
├── app/
│   ├── main.py
│   ├── auth/
│   ├── athletes/
│   ├── tests/
│   ├── sessions/
│   ├── submissions/
│   ├── verification/
│   ├── uploads/
│   ├── results/
│   ├── admin/
│   └── notifications/
├── workers/
├── models/
├── schemas/
├── services/
└── migrations/
```

### APIs

#### Auth

```text
POST /auth/register
POST /auth/send-otp
POST /auth/verify-otp
POST /auth/login
```

#### Athlete

```text
GET  /athlete/profile
PUT  /athlete/profile
GET  /athlete/history
GET  /athlete/personal-bests
```

#### Sessions

```text
GET  /sessions/active
GET  /sessions/{id}
POST /admin/sessions
PUT  /admin/sessions/{id}
DELETE /admin/sessions/{id}
```

#### Tests

```text
POST /tests/practice
POST /tests/session
GET  /tests/{id}
```

#### Upload

```text
POST /uploads/initiate
POST /uploads/chunk
POST /uploads/complete
```

#### Verification

```text
POST /verification/process
GET  /verification/{submission_id}
```

---

# 13. Sprint 11 — Server-Side AI Verification

**Goal:** Server becomes the source of truth.

The invariant is:

```text
Mobile result = provisional

Server result = authoritative
```

### Processing pipeline

```text
Upload
 ↓
Validate video
 ↓
Extract frames
 ↓
MediaPipe/OpenCV
 ↓
Exercise detection
 ↓
Rep/measurement calculation
 ↓
Form validation
 ↓
Cheat checks
 ↓
Compare mobile result
 ↓
Final result
```

### Result states

```text
UPLOADED
PROCESSING
VERIFIED
FLAGGED
REJECTED
APPROVED
```

### Definition of Done

The server can independently process a mobile submission and produce a final result.

---

# 14. Sprint 12 — Anti-Cheat & Authenticity

**Goal:** Prevent obvious manipulation.

### Checks

- Multiple people detection.
- No-person detection.
- Wrong-person detection.
- Face verification.
- Video duration validation.
- Frame duplication detection.
- Loop detection.
- Abrupt cut detection.
- Suspicious playback speed.
- Resolution/metadata sanity checks.
- Impossible movement detection.
- On-device/server score mismatch.
- Randomized verification gesture/test.
- Duplicate submission detection.

### Flag system

Each flag must contain:

```text
flag_id
submission_id
flag_type
severity
reason
detected_at
status
reviewed_by
reviewed_at
```

Never store only:

```text
is_cheat = true
```

The reviewer needs to know **why** the submission was flagged.

---

# 15. Sprint 13 — Admin Dashboard Foundation

**Goal:** Give administrators control over the platform.

### Pages

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

### Session Creation

Fields:

- Session name
- Start date
- End date
- Duration
- Test selection
- Status
- Instructions

### Session states

```text
DRAFT
SCHEDULED
ACTIVE
ENDED
DISABLED
```

---

# 16. Sprint 14 — Athlete Submission Review

**Goal:** Officials can review official submissions.

### Submission table

Show:

- Athlete
- Session
- Test
- Score
- AI status
- Server status
- Flag status
- Submitted time
- Review status

### Review screen

Include:

- Athlete information
- Test information
- Video
- Skeleton overlay
- Rep count
- Measurement
- Form feedback
- On-device result
- Server result
- Flag reasons
- Verification status

### Actions

```text
Approve
Reject
Request Resubmission
Flag
```

Every action must create an audit record.

---

# 17. Sprint 15 — Results, History & Benchmarking

**Goal:** Turn raw scores into useful athlete information.

### Athlete result screen

```text
Test
Score
Form Score
Validity
Benchmark
Personal Best
Previous Attempt
```

### History

```text
Date
Test
Score
Status
Session/Practice
```

### Benchmarking

Support:

- Age group
- Gender
- Region
- Test
- Score band

The actual SAI benchmark dataset must be verified before being treated as official.

---

# 18. Sprint 16 — Leaderboards & Analytics

### Athlete leaderboard

Filters:

- Test
- Age group
- Region
- Session

### Admin analytics

Metrics:

- Total athletes
- Total submissions
- Verified submissions
- Flagged submissions
- Rejected submissions
- Average scores
- Regional performance
- Test participation
- Session participation
- Upload failures
- Verification time

### Charts

- Participation over time
- Regional distribution
- Test performance
- Verification status
- Score distribution

---

# 19. Sprint 17 — Notifications

### Mobile notifications

- Session opened
- Session ending soon
- Submission received
- Verification completed
- Submission flagged
- Submission approved
- Submission rejected
- Resubmission requested

Use Firebase Cloud Messaging or the final approved notification provider.

---

# 20. Sprint 18 — Additional Test Battery

Implement the remaining tests after the core pose-based tests are stable.

## Sit-ups

- Pose-based rep counter
- Torso angle
- Full-range validation
- Server verification

## Shuttle Run

- Movement tracking
- Direction change detection
- Accelerometer/gyroscope
- Optional camera verification
- Time measurement

## Endurance Run

- GPS
- Distance
- Time
- Pace
- Motion validation
- Server verification

These should not block completion of the core F4ALL exercise system.

---

# 21. Sprint 19 — UX, Language & Accessibility

### Languages

Start with:

- English
- Tamil
- Hindi

Then expand based on pilot requirements.

### UX

- Clear instructions
- Exercise demonstrations
- Countdown
- Progress indicator
- Large controls
- Error recovery
- Offline status
- Upload status
- Result explanations

### Accessibility

- Dynamic font sizing
- Contrast
- Screen-reader labels
- Touch target sizes
- Clear error messages

---

# 22. Sprint 20 — Security & Privacy

This platform handles:

- Athlete identity
- Phone numbers
- Videos
- Face/identity information
- Location
- Potential minor data

Therefore security is a release requirement.

### Required

- HTTPS/TLS
- Encrypted storage
- Private video bucket
- Signed URLs
- JWT/session security
- Password/OTP abuse protection
- Rate limiting
- Input validation
- Secure secrets
- Dependency scanning
- Audit logs
- Data retention rules
- Consent flow
- Minor/parental consent where applicable
- Role-based admin access

---

# 23. Sprint 21 — Device Compatibility

Minimum device matrix:

### Entry level

- 2–3 low-end Android devices

### Mid-range

- 2–3 current mid-range Android devices

Test:

- Camera
- FPS
- Pose accuracy
- Battery
- Heat
- Memory
- Storage
- Upload
- Offline mode
- Background sync
- App lifecycle

### Important

The application must not be considered complete only because it works on the development phone.

---

# 24. Sprint 22 — Accuracy Validation

Create a formal test dataset.

For each exercise:

```text
Video
Human ground truth
AI result
Absolute error
Validity
Device
Lighting
Distance
Camera angle
```

### Example

```text
Human reps: 20
AI reps: 19
Error: 1
```

Track:

- Rep-count accuracy
- False positive rate
- False negative rate
- Measurement error
- Form classification accuracy
- Cheat-detection accuracy

---

# 25. Sprint 23 — Backend Load & Reliability

### Test

- Concurrent uploads
- Multiple athletes
- Multiple sessions
- Queue backlog
- Worker failures
- Database load
- S3 upload load
- API rate limits

### Failure recovery

Test:

```text
API down
DB unavailable
Redis unavailable
Upload interrupted
Worker crashes
Mobile app killed
Network disappears
```

The system should recover without corrupting official results.

---

# 26. Sprint 24 — End-to-End Pilot

Run a complete real-world test.

## Athlete

```text
Install
 ↓
Register
 ↓
OTP
 ↓
Profile
 ↓
Face verification
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
```

## Backend

```text
Receive
 ↓
Store
 ↓
Process
 ↓
Verify
 ↓
Flag/Approve
```

## Admin

```text
Login
 ↓
Open Session
 ↓
View Submission
 ↓
Review
 ↓
Approve/Reject
 ↓
Audit
```

---

# 27. Final Release Acceptance Checklist

## Mobile

- [ ] Registration works
- [ ] OTP works
- [ ] Login works
- [ ] Profile works
- [ ] Face verification works
- [ ] Practice mode works
- [ ] Session mode works
- [ ] Gesture verification works
- [ ] Countdown works
- [ ] Squat works
- [ ] Push-up works
- [ ] Bicep curl works
- [ ] Lunge works
- [ ] Vertical jump works
- [ ] Form correction works
- [ ] Voice feedback works
- [ ] Results work
- [ ] History works
- [ ] Offline queue works
- [ ] Background sync works
- [ ] Camera can restart without app restart
- [ ] No black-screen camera issue
- [ ] No stale MediaPipe state
- [ ] Low-end device tested

## Backend

- [ ] Authentication API
- [ ] Athlete API
- [ ] Session API
- [ ] Submission API
- [ ] Upload API
- [ ] Server verification
- [ ] Cheat detection
- [ ] Result finalization
- [ ] Notification support
- [ ] Audit logging
- [ ] Error handling
- [ ] Monitoring
- [ ] Load testing

## Dashboard

- [ ] Admin login
- [ ] Dashboard
- [ ] Session creation
- [ ] Session editing
- [ ] Session enable/disable
- [ ] Submission list
- [ ] Submission details
- [ ] Video review
- [ ] AI result
- [ ] Server result
- [ ] Flag reasons
- [ ] Approve
- [ ] Reject
- [ ] Resubmission
- [ ] Athlete list
- [ ] Athlete history
- [ ] Leaderboards
- [ ] Analytics
- [ ] Audit trail

## Security

- [ ] HTTPS
- [ ] Secure authentication
- [ ] Role-based access
- [ ] Private video storage
- [ ] Signed URLs
- [ ] Encryption
- [ ] Rate limiting
- [ ] Input validation
- [ ] Audit logs
- [ ] Consent
- [ ] Data retention policy

## Testing

- [ ] Unit tests
- [ ] Integration tests
- [ ] API tests
- [ ] UI tests
- [ ] AI accuracy tests
- [ ] Device tests
- [ ] Offline tests
- [ ] Network failure tests
- [ ] Load tests
- [ ] Security tests
- [ ] Pilot test

---

# 28. Recommended Execution Order From Current State

Do **not** attempt to build everything simultaneously.

Follow this order:

```text
CURRENT STATE
     │
     ▼
Sprint 3
Rep Counting + Jump Measurement
     │
     ▼
Sprint 4
Form Correction + Voice Feedback
     │
     ▼
Sprint 5
Gesture + Camera Reliability
     │
     ▼
Sprint 6
Practice Mode
     │
     ▼
Sprint 7
Session Mode
     │
     ▼
Sprint 8
Authentication + Profile + Face Verification
     │
     ▼
Sprint 9
Offline Queue + Sync
     │
     ▼
Sprint 10
FastAPI Backend
     │
     ▼
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
History + Benchmarking
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
                 F4ALL v1.0
```

---

# 29. What Should NOT Be Rewritten

The following should be preserved if already working:

- Existing MediaPipe Pose integration
- Existing CameraX pipeline
- Existing exercise detection logic
- Existing squat implementation
- Existing vertical-jump work
- Existing gesture logic where correct
- Existing result calculation
- Existing Kotlin/Compose project structure

The strategy is:

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

Do not rewrite a working module simply to match a newer architecture unless there is a concrete integration/security/performance reason.

---

# 30. Final Definition of F4ALL Complete

F4ALL should be considered complete only when the following complete journey works:

```text
ATHLETE
  │
  ├── Register
  ├── OTP
  ├── Profile
  ├── Face Verification
  │
  ▼
HOME
  │
  ├── Practice Mode
  │      ├── Select Test
  │      ├── Instructions
  │      ├── Gesture
  │      ├── Countdown
  │      ├── AI Exercise
  │      └── Result/History
  │
  └── Session Mode
         ├── Active Session
         ├── Allowed Test
         ├── Gesture
         ├── Countdown
         ├── AI Exercise
         ├── Form Correction
         ├── Result
         └── Official Submission
                    │
                    ▼
                OFFLINE QUEUE
                    │
                    ▼
                BACKEND
                    │
          ┌─────────┼──────────┐
          ▼         ▼          ▼
        Store     Verify     Detect
        Video     Result     Cheat
          │         │          │
          └─────────┼──────────┘
                    ▼
             FINAL RESULT
                    │
                    ▼
             ADMIN DASHBOARD
                    │
          ┌─────────┼──────────┐
          ▼         ▼          ▼
       Sessions  Review     Analytics
                    │
             ┌──────┼──────┐
             ▼      ▼      ▼
          Approve Reject Resubmit
                    │
                    ▼
              AUDIT TRAIL
```

---

# 31. Final Project Milestones

| Milestone | Target |
|---|---|
| M1 | Core AI exercise engine complete |
| M2 | Form correction + voice feedback complete |
| M3 | Camera + gesture flow production-stable |
| M4 | Practice Mode complete |
| M5 | Session Mode complete |
| M6 | Authentication + face verification complete |
| M7 | Offline sync complete |
| M8 | Backend connected |
| M9 | Server verification complete |
| M10 | Anti-cheat complete |
| M11 | Admin dashboard complete |
| M12 | Submission review complete |
| M13 | Benchmark/history/leaderboards complete |
| M14 | Full test battery complete |
| M15 | Security + device validation complete |
| M16 | Pilot complete |
| M17 | F4ALL v1.0 release candidate |

---

# 32. Working Rule for Every Future Sprint

For every sprint, produce a short completion report containing:

```text
SPRINT:
GOAL:

IMPLEMENTED:
1.
2.
3.

FILES CHANGED:

TESTS:
- Unit:
- Integration:
- Device:
- Manual:

KNOWN ISSUES:

DEFINITION OF DONE:
[ ] Complete
[ ] Pending

GIT:
Branch:
Commit:
PR:

NEXT SPRINT:
```

This keeps the project from silently accumulating missing features.

---

# 33. Final Priority

If time becomes limited, implement in this order:

```text
P0 — MUST HAVE
────────────────────────
Authentication
Profile
Practice Mode
Session Mode
Gesture Verification
Squat
Push-up
Bicep Curl
Lunge
Vertical Jump
Form Correction
Voice Feedback
Offline Queue
Backend Upload
Server Verification
Anti-Cheat
Admin Session Management
Submission Review
Approve/Reject
Audit Trail

P1 — IMPORTANT
────────────────────────
History
Benchmarking
Leaderboards
Analytics
Notifications
Sit-ups
Multi-language
Accessibility
Additional tests

P2 — POST CORE RELEASE
────────────────────────
Shuttle Run
Endurance Run
Advanced ML cheat detection
iOS
Government e-KYC
Advanced scouting workflows
```

---

# 34. Source / Scope Note

The existing project README establishes the original SAI platform architecture and its 14-sprint direction, including Android/Kotlin, MediaPipe, offline processing, FastAPI, server-side verification, dashboard review, benchmarking, cheat detection, hardening and pilot phases.

This master plan additionally incorporates the F4ALL application features previously discussed during implementation that were not explicitly represented in that README, especially Practice Mode, Session Mode, gesture verification, face verification, expanded exercise support, athlete history, session-controlled submissions, and the detailed admin workflow.

Where the final official SAI assessment specification differs from the current F4ALL exercise list, the official specification must take precedence before production release.
