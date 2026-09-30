"""Sprint 12: anti-cheat and authenticity.

Three layers:

* the controlled dataset (``anticheat_dataset.py``) — every tampering case
  detected, honest controls clean, every false flag accounted for;
* unit tests for each new check and for the aggregation, identity, limits and
  flag-evidence plumbing;
* integration through the worker and database to the verification API.

All inputs are synthetic or rendered. This validates that the checks detect
what they are built to detect; it does not measure real-world accuracy.
"""

from __future__ import annotations

import uuid
from dataclasses import replace
from datetime import date
from pathlib import Path

import pytest
from sqlalchemy import select

import app.tasks as tasks
from app.models import Athlete, TestResultStatus, Video
from app.verification import discrepancy, finalization
from app.verification.analyzers import (
    AnalyzerEvent,
    AnalyzerResult,
    AttemptStatus,
    TestType,
    analyze_sequence,
    build_analyzer,
)
from app.verification.cheat import duplicates, frames, movement, subject, timing
from app.verification.cheat.findings import (
    CheatCheck,
    CheatReport,
    FaceOutcome,
    FaceVerdict,
)
from app.verification.cheat.identity import IdentityOutcome, identity_summary
from app.verification.cheat.limits import IntegrityLimits
from app.verification.cheat.metadata import VideoMetadata
from app.verification.extractor import model_path
from app.verification.pose import PoseFrame, PosePoint, decode_sequence
from tests import anticheat_dataset as dataset
from tests.test_server_verification import (  # noqa: F401 - fixtures
    all_tests,
    athlete_headers,
    fixture_analysis,
    flags_of,
    mp4_bytes,
    official_headers,
    reload,
    settings,
    submit,
    upload,
    worker,
)

pytest.importorskip("cv2", reason="opencv not installed")

FIXTURES = Path(__file__).parent / "fixtures" / "parity"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


def pose(sequence: str) -> list[PoseFrame]:
    return decode_sequence((FIXTURES / sequence).read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# Controlled dataset
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def outcomes(tmp_path_factory):
    directory = tmp_path_factory.mktemp("anticheat")
    return {o.case.name: o for o in dataset.run_all(directory)}


@pytest.mark.parametrize("name", [case.name for case in dataset.cases()])
def test_dataset_case_is_detected_and_every_false_flag_accounted_for(outcomes, name):
    outcome = outcomes[name]

    assert outcome.detected, f"{name}: missed {outcome.missed}"
    # A new false flag fails; the understood ones stay recorded in the table.
    assert outcome.false_flags == outcome.case.known_false_flags, name
    for flag in outcome.flags:
        assert flag.reason and flag.detail, "every flag says why"
        assert isinstance(flag.evidence, dict) and flag.evidence, "and shows how"


def test_honest_controls_verify_and_tampering_is_flagged(outcomes):
    for name, outcome in outcomes.items():
        honest = not outcome.case.expected
        assert outcome.verdict == ("verified" if honest else "flagged"), name


def test_dataset_table_reports_every_case(outcomes):
    rendered = dataset.table(list(outcomes.values()))
    for name in outcomes:
        assert f"| {name} |" in rendered


def test_loop_and_cut_evidence_point_at_the_frames(outcomes):
    [loop] = outcomes["looped"].flags
    assert loop.evidence["affected_frame_range"] == {
        "source": [30, 89],
        "repeat": [90, 149],
    }
    [cut] = outcomes["cut_video"].flags
    assert cut.evidence["frame_index"] == 75
    assert cut.evidence["difference"] > cut.evidence["threshold"]


# ---------------------------------------------------------------------------
# Single-person validation
# ---------------------------------------------------------------------------


def _outcome(report: CheatReport) -> str:
    return report.summaries["person"]["outcome"]


def test_person_outcomes():
    frames_ = pose("squats_5_clean.csv")
    n = len(frames_)

    valid = subject.check_subject(frames_, [1] * n)
    assert _outcome(valid) == "VALID_PERSON" and valid.is_clean
    assert valid.summaries["person"]["frames_sampled"] == n
    assert valid.summaries["person"]["mean_torso_confidence"] > 0.5

    multi = subject.check_subject(frames_, [2] * (n // 2) + [1] * (n - n // 2))
    assert _outcome(multi) == "MULTIPLE_PEOPLE"
    assert multi.summaries["person"]["multiple_people_fraction"] == pytest.approx(
        0.5, abs=0.01
    )

    empty = [PosePoint(0, 0, 0, 0)] * 33
    gone = [replace(f, points=empty) if i % 4 else f for i, f in enumerate(frames_)]
    none = subject.check_subject(gone, [1] * n)
    assert _outcome(none) == "NO_PERSON"
    assert any(f.check is CheatCheck.NO_SUBJECT for f in none.findings)

    uncertain = subject.check_subject(frames_, None)
    assert _outcome(uncertain) == "UNCERTAIN"
    assert "multiple_people" in uncertain.skipped


def test_one_bad_frame_does_not_flag_the_submission():
    frames_ = pose("squats_5_clean.csv")
    counts = [1] * len(frames_)
    counts[40] = 3
    report = subject.check_subject(frames_, counts)
    assert report.is_clean
    assert report.summaries["person"]["frames_with_multiple_people"] == 1


def test_person_thresholds_are_configurable():
    frames_ = pose("squats_5_clean.csv")
    counts = [2 if i % 10 == 0 else 1 for i in range(len(frames_))]  # 10%
    assert subject.check_subject(frames_, counts).is_clean
    stricter = subject.check_subject(frames_, counts, multi_person_fraction=0.05)
    assert any(f.check is CheatCheck.MULTIPLE_PEOPLE for f in stricter.findings)


# ---------------------------------------------------------------------------
# Frames: duplicates and timestamps
# ---------------------------------------------------------------------------


def test_repeated_frames_amid_motion_are_counted_but_stillness_is_not():
    still = [None] + [1.0] * 30  # an athlete standing still: no motion context
    assert frames.find_duplicated_frames(still).indices == []

    stutter = [None]
    for index in range(60):
        stutter.append(0.5 if index % 3 == 0 else 40.0)
    found = frames.find_duplicated_frames(stutter)
    assert len(found.indices) == 20
    assert found.ratio == pytest.approx(20 / 60)


def test_duplicate_ratio_below_threshold_is_recorded_not_flagged():
    changes = [None] + [0.5 if i % 20 == 0 else 40.0 for i in range(200)]
    report = frames.check_frames([], [], list(range(0, 6700, 33)), frame_changes=changes)
    assert not any(f.check is CheatCheck.DUPLICATE_FRAMES for f in report.findings)
    assert report.summaries["frames"]["duplicate_ratio"] == pytest.approx(0.05)


def test_timestamp_gaps_and_reversals():
    steady = list(range(0, 3300, 33))
    assert frames.find_timestamp_anomalies(steady)[0] == []

    dropped_frame = steady[:50] + [t + 66 for t in steady[50:]]  # a phone hiccup
    assert frames.find_timestamp_anomalies(dropped_frame)[0] == []

    removed = steady[:50] + [t + 900 for t in steady[50:]]
    backwards = [*steady[:50], steady[49] - 10, *steady[51:]]
    [gap] = frames.find_timestamp_anomalies(removed)[0]
    assert (gap.index, gap.kind) == (50, "gap")
    kinds = {a.kind for a in frames.find_timestamp_anomalies(backwards)[0]}
    assert "backwards" in kinds


# ---------------------------------------------------------------------------
# Playback speed and impossible movement
# ---------------------------------------------------------------------------


def _jump_result(height_cm: float, flight_ms: int) -> AnalyzerResult:
    return AnalyzerResult(
        test_type=TestType.VERTICAL_JUMP,
        score=height_cm,
        unit="cm",
        status=AttemptStatus.COMPLETE,
        confidence=0.9,
        frames_analyzed=100,
        frames_rejected=0,
        jumps=[(height_cm, flight_ms)],
    )


def test_jump_physics_matches_gravity_for_a_real_speed_jump():
    # A 40 cm jump is ~0.54 s above the scorer's timing thresholds.
    assert timing.jump_time_scale(40.0, 537) == pytest.approx(1.0, abs=0.05)
    assert timing.jump_time_scale(40.0, 1074) == pytest.approx(2.0, abs=0.1)


def test_jump_physics_flags_slowed_footage_only_beyond_the_limit():
    honest = timing.check_timing([], None, _jump_result(40.0, 560))
    slowed = timing.check_timing([], None, _jump_result(40.0, 1100))
    small = timing.check_timing([], None, _jump_result(15.0, 1100))

    assert honest.is_clean
    [finding] = slowed.findings
    assert finding.check is CheatCheck.PLAYBACK_SPEED_SUSPICIOUS
    assert finding.evidence["signal"] == "jump_physics"
    assert finding.evidence["time_scale"] > 1.6
    assert small.is_clean  # below the height where the model is reliable
    assert small.summaries["timing"]["jump_time_scale"] is None


def test_declared_and_measured_frame_rate_must_agree():
    meta = VideoMetadata(10.0, 480, 854, 30.0, 2_000_000)
    at_30 = list(range(0, 10_000, 33))
    at_60 = list(range(0, 5_000, 17))
    assert timing.check_timing(at_30, meta, None).is_clean
    [finding] = timing.check_timing(at_60, meta, None).findings
    assert finding.evidence["signal"] == "frame_timing"


def test_a_few_fast_reps_are_jitter_but_most_are_impossible():
    def result(fast: int, counted: int) -> AnalyzerResult:
        events = [AnalyzerEvent(i, "rep_counted") for i in range(counted)]
        events += [AnalyzerEvent(100 + i, "rep_rejected_too_fast") for i in range(fast)]
        return AnalyzerResult(TestType.SQUATS, counted, "reps", AttemptStatus.COMPLETE,
                              0.9, 100, 0, events=events)

    assert movement.check_movement([], result(2, 10)).is_clean
    assert movement.check_movement([], result(3, 20)).is_clean  # 13%
    [finding] = movement.check_movement([], result(4, 4)).findings
    assert finding.check is CheatCheck.IMPOSSIBLE_MOVEMENT
    assert finding.evidence["min_rep_duration_ms"] == 400


def test_tracking_glitch_that_snaps_back_is_not_a_teleport():
    frames_ = pose("squats_5_clean.csv")
    glitch = dataset.shift_from(frames_, 90, 0.5)
    glitch = glitch[:91] + frames_[91:]  # one frame out, then back
    report = movement.check_movement(glitch, None, aspect_ratio=480 / 854)
    assert report.is_clean

    spliced = dataset.shift_from(frames_, 90, 0.5)
    [finding] = movement.check_movement(spliced, None, aspect_ratio=480 / 854).findings
    assert finding.evidence["signal"] == "landmark_jump"
    assert finding.evidence["speed_torso_lengths_per_s"] > movement.MAX_HIP_SPEED


def test_all_six_exercises_pass_the_movement_checks_on_clean_fixtures():
    cases = {
        "SIT_UPS": "situps_5_clean.csv",
        "SQUATS": "squats_5_clean.csv",
        "PUSH_UPS": "pushups_4_clean.csv",
        "BICEP_CURLS": "curls_4_right_arm.csv",
        "LUNGES": "lunges_4_clean.csv",
    }
    for code, sequence in cases.items():
        frames_ = pose(sequence)
        result = analyze_sequence(build_analyzer(TestType(code), None), frames_)
        report = movement.check_movement(frames_, result, aspect_ratio=480 / 640)
        assert report.is_clean, f"{code}: {report.summary()}"


# ---------------------------------------------------------------------------
# Identity
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("pre_test", "face", "expected"),
    [
        ("match", None, IdentityOutcome.IDENTITY_MATCH),
        (None, FaceOutcome(FaceVerdict.PASS, 0.8), IdentityOutcome.IDENTITY_MATCH),
        ("no_match", None, IdentityOutcome.IDENTITY_MISMATCH),
        ("match", FaceOutcome(FaceVerdict.MANUAL_REVIEW, 0.2),
         IdentityOutcome.IDENTITY_MISMATCH),
        (None, None, IdentityOutcome.IDENTITY_UNCERTAIN),
        ("no_face", None, IdentityOutcome.IDENTITY_UNCERTAIN),
        ("unavailable", None, IdentityOutcome.IDENTITY_UNCERTAIN),
    ],
)
def test_identity_outcome(pre_test, face, expected):
    from app.models import IdentityCheckOutcome

    summary = identity_summary(
        official=True,
        pre_test=IdentityCheckOutcome(pre_test) if pre_test else None,
        face=face,
        face_skipped=None if face else "No registration photo on file",
    )
    assert summary["outcome"] == expected.value
    # Only outcomes and a similarity number — no image, no embedding.
    assert set(summary) == {
        "outcome", "official", "pre_test_check", "video_face_check",
        "video_face_similarity", "video_face_not_run",
    }


# ---------------------------------------------------------------------------
# Duplicate submissions
# ---------------------------------------------------------------------------


def _candidate(**changes) -> duplicates.Candidate:
    base = duplicates.Candidate(
        result_id=uuid.uuid4(), athlete_id=uuid.UUID(int=1), session_id=None,
        test_code="SQUATS", status="verified", checksum_sha256="aa" * 32,
        fingerprint=None,
    )
    return replace(base, **changes)


def test_identical_video_from_another_athlete_is_high_severity():
    report = duplicates.check_duplicates(
        athlete_id=uuid.UUID(int=2), session_id=None, checksum_sha256="aa" * 32,
        video_fingerprint=None, candidates=[_candidate()],
    )
    [finding] = report.findings
    assert finding.check is CheatCheck.DUPLICATE_SUBMISSION
    assert finding.severity.value == "high"
    assert finding.evidence["signal"] == "identical"


def test_identical_video_from_the_same_athlete_is_medium():
    report = duplicates.check_duplicates(
        athlete_id=uuid.UUID(int=1), session_id=None, checksum_sha256="aa" * 32,
        video_fingerprint=None, candidates=[_candidate()],
    )
    assert report.findings[0].severity.value == "medium"


def test_no_candidates_means_nothing_to_compare():
    # Practice attempts never reach the server as videos, so they are never
    # candidates; an athlete's first official submission has none either.
    report = duplicates.check_duplicates(
        athlete_id=uuid.UUID(int=1), session_id=None, checksum_sha256="bb" * 32,
        video_fingerprint=None, candidates=[],
    )
    assert report.is_clean
    assert report.summaries["duplicates"]["compared_with"] == 0


def test_fingerprint_needs_enough_frames():
    assert duplicates.fingerprint([bytes(256)] * 10) is None
    assert len(duplicates.fingerprint([bytes(256)] * 40)["signatures"]) == 16


# ---------------------------------------------------------------------------
# Limits, flags and finalization
# ---------------------------------------------------------------------------


def test_limits_come_from_settings_only_when_set(settings):
    assert IntegrityLimits.from_settings(settings) == IntegrityLimits()
    settings.integrity_multi_person_fraction = 0.3
    assert IntegrityLimits.from_settings(settings).multi_person_fraction == 0.3


def test_score_mismatch_flag_carries_both_numbers_and_the_tolerance(settings):
    frames_ = pose("squats_5_clean.csv")
    result = analyze_sequence(build_analyzer(TestType.SQUATS, None), frames_)
    comparison = discrepancy.evaluate(
        server_result=result, device_score=12, settings=settings
    )
    decision = finalization.decide(server_result=result, comparison=comparison)

    [flag] = decision.flags
    assert flag.reason == "score_discrepancy"
    assert flag.evidence == {
        "signal": "mobile_server_comparison",
        "mobile_value": 12,
        "server_value": 5.0,
        "difference": 7.0,
        "tolerance": 2.0,
        "unit": "reps",
        "server_confidence": pytest.approx(result.confidence, abs=0.001),
    }


def test_the_verdict_is_named_by_the_most_severe_flag():
    # Regression: found in the live run — a LOW duplicate_frames flag, checked
    # first, named the verdict of a looped (HIGH) video.
    flags = [
        finalization.FlagSpec("duplicate_frames", "d", "low"),
        finalization.FlagSpec("looped_frames", "l", "high"),
        finalization.FlagSpec("abrupt_cut", "c", "high"),
    ]
    assert finalization.primary_reason(flags) == "looped_frames"
    assert finalization.primary_reason(flags[:1]) == "duplicate_frames"


def test_many_flags_on_one_submission_are_all_kept(outcomes):
    # The frame-doubled case raises two different kinds of flag; neither
    # replaces the other.
    reasons = [flag.reason for flag in outcomes["duplicated_frames"].flags]
    assert "duplicate_frames" in reasons and "looped_frames" in reasons


# ---------------------------------------------------------------------------
# Integration: worker -> database -> verification API
# ---------------------------------------------------------------------------


def use_analysis(monkeypatch, analysis):
    monkeypatch.setattr(tasks, "analyze_video", lambda path, models_dir: analysis)


def test_verified_result_records_what_every_check_measured(
    client, db, all_tests, worker, mp4_bytes, monkeypatch, settings, athlete
):
    use_analysis(monkeypatch, fixture_analysis("squats_5_clean.csv"))
    headers = athlete_headers(athlete, settings)
    video_id = upload(client, mp4_bytes, "SQUATS", headers=headers)
    result_id = submit(client, "SQUATS", 5, video_id, headers=headers)

    assert worker(result_id)["status"] == "verified"

    integrity = reload(db, result_id).server_result["integrity"]
    assert integrity["findings"] == []
    assert integrity["summaries"]["person"]["outcome"] == "VALID_PERSON"
    assert integrity["summaries"]["identity"]["outcome"] == "IDENTITY_UNCERTAIN"
    assert integrity["summaries"]["movement"]["too_fast_reps"] == 0
    assert integrity["summaries"]["duplicates"]["compared_with"] == 0
    assert integrity["limits"] == IntegrityLimits().as_dict()

    official = client.get(
        f"/api/verification/{result_id}", headers=official_headers(db, settings)
    ).json()
    assert official["integrity"]["summaries"]["person"]["outcome"] == "VALID_PERSON"
    mine = client.get(f"/api/verification/{result_id}", headers=headers).json()
    assert mine["integrity"] is None  # thresholds and measurements: officials only


def test_several_flags_are_stored_with_evidence_and_shown_to_officials_only(
    client, db, all_tests, worker, mp4_bytes, monkeypatch, settings, athlete
):
    analysis = fixture_analysis("squats_5_clean.csv")
    analysis.pose_counts = [2 if i % 5 < 2 else 1 for i in range(len(analysis.frames))]
    use_analysis(monkeypatch, analysis)
    headers = athlete_headers(athlete, settings)
    video_id = upload(client, mp4_bytes, "SQUATS", headers=headers)
    result_id = submit(client, "SQUATS", 12, video_id, headers=headers)

    assert worker(result_id)["status"] == "flagged"

    stored = {flag.reason: flag for flag in flags_of(db, result_id)}
    assert set(stored) == {"score_discrepancy", "multiple_people"}
    assert stored["score_discrepancy"].evidence["mobile_value"] == 12
    assert stored["multiple_people"].evidence["multi_person_fraction"] == 0.4

    body = client.get(
        f"/api/verification/{result_id}", headers=official_headers(db, settings)
    ).json()
    shown = {flag["reason"]: flag for flag in body["flags"]}
    assert shown["multiple_people"]["status"] == "open"
    assert shown["multiple_people"]["flag_id"]
    assert shown["multiple_people"]["evidence"]["frames_with_multiple_people"] == 70

    # The athlete's own views never carry evidence.
    assert client.get(
        f"/api/verification/{result_id}", headers=headers
    ).json()["flags"] is None
    results = client.get(f"/api/results/{result_id}", headers=headers).json()
    assert all(flag["evidence"] is None for flag in results["flags"])


def test_review_records_who_resolved_each_flag(
    client, db, all_tests, worker, mp4_bytes, monkeypatch, settings
):
    use_analysis(monkeypatch, fixture_analysis("squats_5_clean.csv"))
    result_id = submit(client, "SQUATS", 12, upload(client, mp4_bytes, "SQUATS"))
    worker(result_id)
    headers = official_headers(db, settings)

    client.post(
        f"/api/dashboard/reviews/{result_id}/action",
        json={
            "action": "rejected",
            "reason": "score_discrepancy",
            "notes": "The count does not match the video",
        },
        headers=headers,
    )

    [flag] = client.get(f"/api/verification/{result_id}", headers=headers).json()[
        "flags"
    ]
    assert flag["status"] == "confirmed"
    assert flag["reviewed_by"] is not None
    assert flag["resolved_at"] is not None


def test_the_same_video_from_two_athletes_is_flagged_as_a_duplicate(
    client, db, all_tests, worker, mp4_bytes, monkeypatch, settings, athlete
):
    use_analysis(monkeypatch, fixture_analysis("squats_5_clean.csv"))
    other = Athlete(id=uuid.uuid4(), name="Second Athlete", dob=date(2006, 1, 1),
                    gender="male", region="Tamil Nadu", phone="9000000002",
                    height_cm=170)
    db.add(other)
    db.commit()

    first_headers = athlete_headers(athlete, settings)
    first = submit(client, "SQUATS", 5,
                   upload(client, mp4_bytes, "SQUATS", headers=first_headers),
                   headers=first_headers)
    assert worker(first)["status"] == "verified"

    second_headers = athlete_headers(other, settings)
    second = submit(client, "SQUATS", 5,
                    upload(client, mp4_bytes, "SQUATS", headers=second_headers),
                    headers=second_headers)
    assert worker(second)["status"] == "flagged"

    [flag] = flags_of(db, second)
    assert flag.reason == "duplicate_submission"
    assert flag.severity.value == "high"
    assert flag.evidence["signal"] == "identical"
    assert flag.evidence["other_result_id"] == first
    assert flag.evidence["other_athlete"] is True
    # The first submission is not retroactively changed.
    assert reload(db, first).status is TestResultStatus.verified


@pytest.mark.skipif(
    not model_path(MODELS_DIR).exists(),
    reason="Pose model not fetched (python -m scripts.fetch_model)",
)
def test_real_extraction_flags_a_looped_upload_with_the_frames_to_look_at(
    client, db, all_tests, worker, tmp_path
):
    from tests.test_tampered_videos import honest_frames, write_video

    base = honest_frames(150)
    data = write_video(tmp_path / "looped.mp4", base[:90] + base[30:90] + base[90:])
    data = data.read_bytes()
    result_id = submit(client, "SIT_UPS", 5, upload(client, data, "SIT_UPS"))

    worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.flagged
    loops = [f for f in flags_of(db, result_id) if f.reason == "looped_frames"]
    assert loops and loops[0].evidence["affected_frame_range"]["repeat"][0] == 90
    frames_summary = result.server_result["integrity"]["summaries"]["frames"]
    assert frames_summary["loop_detected"] is True
    assert frames_summary["duplicate_ratio"] is not None  # real per-frame change ran
    # Fingerprint kept for recognising this footage if it is submitted again.
    video = db.execute(
        select(Video).where(Video.test_result_id == result.id)
    ).scalar_one()
    assert video.fingerprint and len(video.fingerprint["signatures"]) == 16


def test_a_crashed_integrity_check_is_flagged_not_passed_as_clean(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    use_analysis(monkeypatch, fixture_analysis("squats_5_clean.csv"))

    def broken(*args, **kwargs):
        raise RuntimeError("movement check crashed")

    monkeypatch.setattr(movement, "check_movement", broken)
    result_id = submit(client, "SQUATS", 5, upload(client, mp4_bytes, "SQUATS"))

    # The other checks still ran, and the verdict is not "clean".
    assert worker(result_id)["status"] == "flagged"
    result = reload(db, result_id)
    assert result.verification_reason == finalization.REASON_INTEGRITY_CHECK_FAILED
    integrity = result.server_result["integrity"]
    assert "movement check crashed" in integrity["errors"]["movement"]
    assert integrity["summaries"]["person"]["outcome"] == "VALID_PERSON"
    [flag] = flags_of(db, result_id)
    assert flag.severity.value == "low"
    assert flag.evidence["checks"]["movement"].startswith("RuntimeError")
