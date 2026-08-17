# Sprint 1 — Capture UI + Camera Pipeline

## Status

Functionally complete on the available OPPO test device.

Low-end/old Android device validation is pending.

---

## Goal

Build a reliable Android capture pipeline capable of recording test videos and extracting raw frames for later processing.

---

## Completed

### Android Project
- Android project created using Kotlin and Jetpack Compose.
- Project integrated into the existing repository under `mobile/`.
- Gradle synchronization and build completed successfully.

### Navigation
- Jetpack Compose navigation skeleton implemented.
- Home screen provides test selection.
- Capture screen opens for selected tests.
- Back navigation verified.

### Camera
- CameraX integrated.
- Camera permission handling implemented.
- Camera preview implemented.
- Camera lifecycle binding implemented.

### Capture UI
- Test name displayed on capture screen.
- Test-specific instructions displayed.
- Record button implemented.
- Stop button implemented.
- Recording indicator implemented.
- 3-2-1 countdown implemented.

### Video Recording
- Video recorded using CameraX VideoCapture.
- Videos saved to application-local storage.
- Each recording receives a unique timestamp-based filename.

Example:

videos/
└── test_<timestamp>.mp4

### Frame Extraction
- MediaMetadataRetriever-based frame extraction implemented.
- Frames extracted at 500 ms intervals.
- Frames saved as JPEG images.
- Frame extraction runs on a background IO dispatcher.

Example:

frames/
└── test_<timestamp>/
    ├── frame_00000.jpg
    ├── frame_00001.jpg
    ├── frame_00002.jpg
    └── ...

### Frame Storage Fix
- Frame filenames are scoped inside a directory belonging to each video.
- Previous recordings are not overwritten by later recordings.

### Testing Completed
- Vertical Jump flow tested successfully.
- Sit-ups flow tested successfully.
- Multiple recordings tested successfully.
- Back navigation tested successfully.
- Extracted frames verified using Android Studio Device Explorer.
- Recorded frames visually verified to contain the correct video content.
- Current OPPO device testing completed successfully.

---

## Pending

### Low-End Device Validation

A genuinely low-end/old Android device still needs to be tested.

The following flow must be validated on that device:

1. Open application.
2. Select a test.
3. Open capture screen.
4. Verify camera preview.
5. Start recording.
6. Verify 3-2-1 countdown.
7. Record video.
8. Stop recording.
9. Verify video is saved.
10. Verify background frame extraction.
11. Verify extracted frames.
12. Verify no crash or camera failure.

This item remains pending until an appropriate device is available.

---

## Current Capture Pipeline

Home
  ↓
Test Selection
  ↓
Capture Screen
  ↓
CameraX Preview
  ↓
Instructions
  ↓
3-2-1 Countdown
  ↓
Video Recording
  ↓
Local MP4 Storage
  ↓
Background Frame Extraction
  ↓
Per-Video Frame Directory
  ↓
JPEG Frames

---

## Sprint 1 Definition of Done

The capture pipeline has been successfully demonstrated on the available OPPO device.

Final Sprint 1 completion requires validation on:
- One mid-range Android device
- One genuinely low-end/old Android device

Low-end device validation is currently pending.