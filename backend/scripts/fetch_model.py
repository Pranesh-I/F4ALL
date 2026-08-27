"""Download the server-side pose model.

Idempotent: skips the download when the file is already present and non-trivial.
"""

from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_full/float16/latest/pose_landmarker_full.task"
)

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"
DESTINATION = MODELS_DIR / "pose_landmarker_full.task"

MIN_PLAUSIBLE_BYTES = 1_000_000


def main() -> int:
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    if DESTINATION.exists() and DESTINATION.stat().st_size > MIN_PLAUSIBLE_BYTES:
        print(f"Already present: {DESTINATION} ({DESTINATION.stat().st_size} bytes)")
        return 0

    print(f"Downloading {MODEL_URL}")

    # Downloaded to a temporary name first so an interrupted fetch cannot leave
    # a truncated model that fails at inference time with a confusing error.
    temporary = DESTINATION.with_suffix(".partial")
    urllib.request.urlretrieve(MODEL_URL, temporary)

    size = temporary.stat().st_size
    if size < MIN_PLAUSIBLE_BYTES:
        temporary.unlink(missing_ok=True)
        print(f"Download looks truncated ({size} bytes)", file=sys.stderr)
        return 1

    temporary.replace(DESTINATION)
    print(f"Saved {DESTINATION} ({size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
