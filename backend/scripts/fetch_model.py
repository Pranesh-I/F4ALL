"""Download the model files the verification pipeline needs.

Idempotent: skips anything already present and non-trivial.

The face models are optional. Without them the identity check reports itself as
"not run" rather than failing verification — a missing model on the server is
not evidence about the athlete, and the pipeline is built so a reviewer can tell
the difference between "checked and matched" and "never checked".
"""

from __future__ import annotations

import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

BASE = "https://storage.googleapis.com/mediapipe-models"

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


@dataclass(frozen=True)
class Model:
    filename: str
    url: str

    # Smallest size that could plausibly be the real file. A truncated download
    # otherwise fails much later, at inference time, with an opaque error.
    min_bytes: int

    # Pose is required to verify anything at all. The face models are not: the
    # identity check degrades to "not run", which is reported honestly.
    required: bool
    purpose: str


MODELS = (
    Model(
        filename="pose_landmarker_full.task",
        url=f"{BASE}/pose_landmarker/pose_landmarker_full/float16/latest/"
        "pose_landmarker_full.task",
        min_bytes=1_000_000,
        required=True,
        purpose="server-side re-scoring",
    ),
    Model(
        filename="blaze_face_short_range.tflite",
        url=f"{BASE}/face_detector/blaze_face_short_range/float16/latest/"
        "blaze_face_short_range.tflite",
        min_bytes=100_000,
        required=False,
        purpose="face detection for the identity check",
    ),
    Model(
        filename="mobilenet_v3_small.tflite",
        url=f"{BASE}/image_embedder/mobilenet_v3_small/float32/latest/"
        "mobilenet_v3_small.tflite",
        min_bytes=1_000_000,
        required=False,
        purpose="face embedding for the identity check",
    ),
)


def fetch(model: Model) -> bool:
    destination = MODELS_DIR / model.filename

    if destination.exists() and destination.stat().st_size > model.min_bytes:
        print(f"Already present: {model.filename} ({destination.stat().st_size} bytes)")
        return True

    print(f"Downloading {model.filename} ({model.purpose})")

    # Downloaded to a temporary name first so an interrupted fetch cannot leave
    # a truncated model that fails at inference time with a confusing error.
    temporary = destination.with_suffix(destination.suffix + ".partial")

    try:
        urllib.request.urlretrieve(model.url, temporary)
    except OSError as exc:
        temporary.unlink(missing_ok=True)
        print(f"  failed: {exc}", file=sys.stderr)
        return False

    size = temporary.stat().st_size
    if size < model.min_bytes:
        temporary.unlink(missing_ok=True)
        print(f"  looks truncated ({size} bytes)", file=sys.stderr)
        return False

    temporary.replace(destination)
    print(f"  saved {destination} ({size} bytes)")
    return True


def main() -> int:
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    failed_required = []
    failed_optional = []

    for model in MODELS:
        if fetch(model):
            continue
        (failed_required if model.required else failed_optional).append(model)

    if failed_optional:
        names = ", ".join(model.filename for model in failed_optional)
        print(file=sys.stderr)
        print(
            f"Optional models not fetched ({names}). The identity "
            "check will report itself as not run.",
            file=sys.stderr,
        )

    if failed_required:
        names = ", ".join(model.filename for model in failed_required)
        print(file=sys.stderr)
        print(f"Required models missing: {names}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
