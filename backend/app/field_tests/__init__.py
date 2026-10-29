"""Tests that are not scored from pose: the shuttle run and the endurance run (Sprint 18).

Separate from ``app/verification`` because the evidence is different — motion
sensors and GPS instead of video — but the result is the same ``TestResult``,
reviewed, benchmarked and ranked like any other.
"""

from .endurance import score_endurance
from .models import FieldResult, FieldTest, Fix, Invalid, Motion, Outcome
from .shuttle import score_shuttle

__all__ = [
    "FieldResult",
    "FieldTest",
    "Fix",
    "Invalid",
    "Motion",
    "Outcome",
    "score_endurance",
    "score_shuttle",
]
