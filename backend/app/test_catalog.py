"""Every test the server can assess, in battery order, with its unit.

Pose tests are scored from video (``app/verification``); field tests from
sensor and GPS traces (``app/field_tests``). Sessions, practice and the
dashboard's catalogue all read this one list, so none of them can offer a test
the server cannot score.
"""

from __future__ import annotations

from .field_tests.models import FieldTest
from .verification.analyzers import TestType

ASSESSABLE: dict[str, str] = {
    **{test.value: test.unit for test in TestType},
    **{test.value: test.unit for test in FieldTest},
}

FIELD_CODES = frozenset(test.value for test in FieldTest)


def unit_of(code: str) -> str | None:
    return ASSESSABLE.get(code)
