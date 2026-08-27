"""Tests for the device-vs-server comparison.

This is the mechanism that enforces the project's first invariant. Its failure
modes run in both directions and both are serious: too loose and a modified
client walks through; too tight and honest athletes get flagged and lose faith
in the system.
"""

from __future__ import annotations

import pytest

from app.config import Settings
from app.verification.analyzers import (
    AnalyzerResult,
    AttemptStatus,
    TestType,
)
from app.verification.discrepancy import FlagReason, Verdict, evaluate


@pytest.fixture
def settings() -> Settings:
    return Settings(
        environment="test",
        discrepancy_tolerance_reps=2.0,
        discrepancy_tolerance_cm=5.0,
    )


def server_result(
    score: float,
    test_type: TestType = TestType.SIT_UPS,
    confidence: float = 0.9,
    status: AttemptStatus = AttemptStatus.COMPLETE,
    invalid_reason: str | None = None,
) -> AnalyzerResult:
    return AnalyzerResult(
        test_type=test_type,
        score=score,
        unit=test_type.unit,
        status=status,
        confidence=confidence,
        frames_analyzed=300,
        frames_rejected=10,
        invalid_reason=invalid_reason,
    )


def test_matching_scores_verify(settings):
    outcome = evaluate(
        server_result=server_result(20), device_score=20, settings=settings
    )
    assert outcome.verdict is Verdict.VERIFIED
    assert outcome.reason is None


def test_small_disagreement_is_tolerated(settings):
    # The device and server run different pose models on differently-compressed
    # video. Demanding exact agreement would flag nearly every honest test.
    outcome = evaluate(
        server_result=server_result(20), device_score=21, settings=settings
    )
    assert outcome.verdict is Verdict.VERIFIED


def test_large_disagreement_is_flagged(settings):
    outcome = evaluate(
        server_result=server_result(20), device_score=35, settings=settings
    )
    assert outcome.verdict is Verdict.FLAGGED
    assert outcome.reason is FlagReason.SCORE_DISCREPANCY
    assert outcome.difference == 15


def test_severity_scales_with_the_size_of_the_gap(settings):
    """A reviewer triaging a queue needs to know what to open first."""
    modest = evaluate(
        server_result=server_result(20), device_score=23, settings=settings
    )
    extreme = evaluate(
        server_result=server_result(20), device_score=60, settings=settings
    )

    assert modest.severity == "medium"
    assert extreme.severity == "high"


def test_inflated_device_score_is_caught(settings):
    """The case the whole mechanism exists for: a client claiming more reps."""
    outcome = evaluate(
        server_result=server_result(12), device_score=45, settings=settings
    )
    assert outcome.verdict is Verdict.FLAGGED
    assert outcome.reason is FlagReason.SCORE_DISCREPANCY


def test_jump_uses_the_centimetre_tolerance(settings):
    within = evaluate(
        server_result=server_result(40.0, TestType.VERTICAL_JUMP),
        device_score=43.0,
        settings=settings,
    )
    beyond = evaluate(
        server_result=server_result(40.0, TestType.VERTICAL_JUMP),
        device_score=52.0,
        settings=settings,
    )

    assert within.verdict is Verdict.VERIFIED
    assert beyond.verdict is Verdict.FLAGGED


def test_server_failure_flags_rather_than_rejects(settings):
    """A video the server cannot read is not proof the athlete did anything wrong."""
    outcome = evaluate(
        server_result=server_result(
            0,
            status=AttemptStatus.INVALID,
            invalid_reason="No usable pose data in the recording",
        ),
        device_score=20,
        settings=settings,
    )

    assert outcome.verdict is Verdict.FLAGGED
    assert outcome.verdict is not Verdict.REJECTED
    assert outcome.reason is FlagReason.SERVER_COULD_NOT_SCORE
    assert outcome.severity == "high"


def test_missing_device_score_is_flagged(settings):
    outcome = evaluate(
        server_result=server_result(20), device_score=None, settings=settings
    )
    assert outcome.verdict is Verdict.FLAGGED
    assert outcome.reason is FlagReason.DEVICE_SCORE_MISSING


def test_agreeing_scores_from_poor_tracking_are_still_flagged(settings):
    """Agreement on barely-tracked video is weak evidence, not strong.

    Without this, a result derived from mostly-unusable frames reaches an
    official with nothing marking it as shaky.
    """
    outcome = evaluate(
        server_result=server_result(20, confidence=0.3),
        device_score=20,
        settings=settings,
    )

    assert outcome.verdict is Verdict.FLAGGED
    assert outcome.reason is FlagReason.LOW_TRACKING_QUALITY
    assert outcome.severity == "low"


def test_flag_detail_names_both_numbers(settings):
    """A reviewer must see what disagreed, not just that something did."""
    outcome = evaluate(
        server_result=server_result(12), device_score=30, settings=settings
    )

    assert "12" in outcome.detail
    assert "30" in outcome.detail
    assert "tolerance" in outcome.detail.lower()


def test_boundary_exactly_at_tolerance_is_accepted(settings):
    outcome = evaluate(
        server_result=server_result(20), device_score=22, settings=settings
    )
    assert outcome.verdict is Verdict.VERIFIED

    outcome = evaluate(
        server_result=server_result(20), device_score=22.01, settings=settings
    )
    assert outcome.verdict is Verdict.FLAGGED
