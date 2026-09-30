"""The tunable limits of the integrity checks, in one place.

Each default is the constant in its check module, where the measurement that
justifies it is documented; this module only gathers them so the pipeline can
take overrides from settings (``INTEGRITY_*`` environment variables) without
every check reading configuration itself. Tune them against pilot data, not
intuition — every flag's evidence records the measured value and the limit.
"""

from __future__ import annotations

from dataclasses import dataclass, fields, replace

from . import duplicates, frames, movement, subject, timing


@dataclass(frozen=True)
class IntegrityLimits:
    multi_person_fraction: float = subject.MULTI_PERSON_FRACTION
    min_subject_fraction: float = subject.MIN_SUBJECT_FRACTION
    confident_subject_fraction: float = subject.CONFIDENT_SUBJECT_FRACTION
    duplicate_frame_ratio: float = frames.DUPLICATE_FRAME_RATIO
    fast_rep_fraction: float = movement.FAST_REP_FRACTION
    max_hip_speed: float = movement.MAX_HIP_SPEED
    jump_time_scale_limit: float = timing.JUMP_TIME_SCALE_LIMIT
    near_duplicate_mae: float = duplicates.NEAR_DUPLICATE_MAE

    @classmethod
    def from_settings(cls, settings) -> IntegrityLimits:
        """Defaults, with any ``integrity_<name>`` setting that is not None."""
        overrides = {
            field.name: value
            for field in fields(cls)
            if (value := getattr(settings, f"integrity_{field.name}", None))
            is not None
        }
        return replace(cls(), **overrides)

    def as_dict(self) -> dict:
        return {field.name: getattr(self, field.name) for field in fields(self)}
