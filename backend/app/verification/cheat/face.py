"""Identity check: does the face in the test video match the registration photo?

## Read this before changing anything here

This is the most consequential and least reliable check in the system, and those
two facts together demand caution.

**It never rejects.** A mismatch produces `manual_review`, always. The decision
that an athlete submitted someone else's test is made by a human looking at two
photographs, not by a cosine distance.

**Accuracy is unvalidated.** This uses a general-purpose image embedder over a
face crop — the "simple face embedding similarity, not a full biometric system"
the brief calls for. Generic embedders are known to perform unevenly across skin
tones, ages and lighting, and this platform's users are largely rural Indian
teenagers photographed on cheap cameras in variable light. The population most
likely to be wrongly flagged is the population the platform exists to serve.

**Therefore the threshold is set to catch only gross mismatches**, and the
system is designed so that being flagged costs a reviewer's attention, never an
athlete's place. Sprint 13-14 must tune this against real pilot data and measure
the false-positive rate by cohort before anyone leans on it harder.

**It also handles children's biometric-adjacent data.** Embeddings are computed
in memory and discarded; only a similarity score reaches the database. No face
crop is stored.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

from .findings import (
    CheatCheck,
    CheatFinding,
    CheatReport,
    FaceOutcome,
    FaceVerdict,
    Severity,
)

logger = logging.getLogger(__name__)

FACE_DETECTOR_MODEL = "blaze_face_short_range.tflite"
FACE_EMBEDDER_MODEL = "mobilenet_v3_small.tflite"

# Cosine similarity below which the faces are reported as not obviously the same
# person. Deliberately permissive: the cost of a missed swap is one video a
# reviewer would likely catch anyway, while the cost of a false accusation falls
# on a teenager who did nothing wrong.
SIMILARITY_THRESHOLD = 0.55

# Below this, the crops are so dissimilar that it is worth saying so more
# firmly — still manual review, but ranked higher in the queue.
STRONG_MISMATCH_THRESHOLD = 0.35

# How many frames across the video to sample for a face. Sampling beats taking
# the first hit: the athlete may be mid-sit-up and facing away early on.
FACE_SAMPLE_COUNT = 12

MIN_FACE_CONFIDENCE = 0.5


class FaceCheckUnavailable(RuntimeError):
    """Raised when the check cannot run — missing models, no reference photo."""


@dataclass(frozen=True)
class FaceComparison:
    similarity: float
    frames_with_face: int
    frames_sampled: int

    @property
    def matches(self) -> bool:
        return self.similarity >= SIMILARITY_THRESHOLD


def models_available(models_dir: Path) -> bool:
    directory = Path(models_dir)
    return (directory / FACE_DETECTOR_MODEL).exists() and (
        directory / FACE_EMBEDDER_MODEL
    ).exists()


def _load_tasks(models_dir: Path):
    try:
        from mediapipe.tasks import python as mp_python
        from mediapipe.tasks.python import vision
    except ImportError as exc:  # pragma: no cover - depends on install
        raise FaceCheckUnavailable("mediapipe is not installed") from exc

    directory = Path(models_dir)
    detector_path = directory / FACE_DETECTOR_MODEL
    embedder_path = directory / FACE_EMBEDDER_MODEL

    if not detector_path.exists() or not embedder_path.exists():
        raise FaceCheckUnavailable(
            "Face models not present. See backend/models/README.md."
        )

    detector = vision.FaceDetector.create_from_options(
        vision.FaceDetectorOptions(
            base_options=mp_python.BaseOptions(model_asset_path=str(detector_path)),
            min_detection_confidence=MIN_FACE_CONFIDENCE,
        )
    )

    embedder = vision.ImageEmbedder.create_from_options(
        vision.ImageEmbedderOptions(
            base_options=mp_python.BaseOptions(model_asset_path=str(embedder_path)),
            l2_normalize=True,
            quantize=False,
        )
    )

    return detector, embedder, vision


def _largest_face_crop(image_bgr, detector):
    """Crop the most prominent face, or None.

    Largest rather than highest-confidence: the athlete performing the test is
    nearer the camera than anyone in the background.
    """
    import cv2
    import mediapipe as mp

    rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

    detection = detector.detect(mp_image)
    if not detection.detections:
        return None

    best = max(
        detection.detections,
        key=lambda d: d.bounding_box.width * d.bounding_box.height,
    )
    box = best.bounding_box

    height, width = image_bgr.shape[:2]

    # A little context around the face helps the generic embedder; a tight crop
    # of only features is not what it was trained on.
    margin_x = int(box.width * 0.15)
    margin_y = int(box.height * 0.15)

    x1 = max(0, box.origin_x - margin_x)
    y1 = max(0, box.origin_y - margin_y)
    x2 = min(width, box.origin_x + box.width + margin_x)
    y2 = min(height, box.origin_y + box.height + margin_y)

    if x2 <= x1 or y2 <= y1:
        return None

    return image_bgr[y1:y2, x1:x2]


def _embed(crop_bgr, embedder):
    import cv2
    import mediapipe as mp

    rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
    result = embedder.embed(mp_image)

    if not result.embeddings:
        return None
    return result.embeddings[0]


def compare_faces(
    video_path: Path,
    reference_image_path: Path,
    models_dir: Path,
    *,
    sample_count: int = FACE_SAMPLE_COUNT,
) -> FaceComparison:
    """Compare faces sampled from the video against a registration photo.

    Raises [FaceCheckUnavailable] when the check cannot be performed — which is
    reported as "not checked", never as "checked and passed".
    """
    try:
        import cv2
    except ImportError as exc:  # pragma: no cover
        raise FaceCheckUnavailable("opencv is not installed") from exc

    if not Path(reference_image_path).exists():
        raise FaceCheckUnavailable("No registration photo on file")

    detector, embedder, vision = _load_tasks(models_dir)

    try:
        reference_image = cv2.imread(str(reference_image_path))
        if reference_image is None:
            raise FaceCheckUnavailable("Registration photo could not be read")

        reference_crop = _largest_face_crop(reference_image, detector)
        if reference_crop is None:
            raise FaceCheckUnavailable("No face found in the registration photo")

        reference_embedding = _embed(reference_crop, embedder)
        if reference_embedding is None:
            raise FaceCheckUnavailable("Could not embed the registration photo")

        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise FaceCheckUnavailable(f"Could not open video {video_path}")

        try:
            total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
            if total <= 0:
                raise FaceCheckUnavailable("Video reports no frames")

            step = max(1, total // sample_count)
            best_similarity = 0.0
            found = 0
            sampled = 0

            for index in range(0, total, step):
                capture.set(cv2.CAP_PROP_POS_FRAMES, index)
                ok, frame = capture.read()
                if not ok:
                    continue

                sampled += 1
                crop = _largest_face_crop(frame, detector)
                if crop is None:
                    continue

                embedding = _embed(crop, embedder)
                if embedding is None:
                    continue

                found += 1
                similarity = vision.ImageEmbedder.cosine_similarity(
                    reference_embedding, embedding
                )
                # Best match across the video, not the mean. The athlete spends
                # much of a sit-up test facing away from the camera, and
                # averaging in those frames would penalise a correct match.
                best_similarity = max(best_similarity, similarity)

            if found == 0:
                raise FaceCheckUnavailable("No face found anywhere in the recording")

            return FaceComparison(
                similarity=best_similarity,
                frames_with_face=found,
                frames_sampled=sampled,
            )
        finally:
            capture.release()
    finally:
        # Embeddings and crops live only inside this call. Nothing derived from
        # a child's face is written anywhere but a similarity score.
        detector.close()
        embedder.close()


def check_face(
    video_path: Path,
    reference_image_path: Path | None,
    models_dir: Path,
    *,
    report: CheatReport | None = None,
) -> CheatReport:
    report = report or CheatReport()

    if reference_image_path is None:
        report.skip(
            CheatCheck.FACE_MISMATCH,
            "No registration photo on file (Sprint 7 captures it)",
        )
        return report

    try:
        comparison = compare_faces(video_path, reference_image_path, models_dir)
    except FaceCheckUnavailable as exc:
        # Explicitly "we did not look", never silently "we looked and it was
        # fine". A reviewer must be able to tell the difference.
        report.skip(CheatCheck.FACE_MISMATCH, str(exc))
        return report

    if comparison.matches:
        # Recorded even though nothing is flagged. `face_verifications` is the
        # record that the comparison happened, and a table written only on
        # mismatch cannot tell "matched" from "never ran".
        report.face = FaceOutcome(
            verdict=FaceVerdict.PASS,
            similarity=comparison.similarity,
            frames_with_face=comparison.frames_with_face,
            frames_sampled=comparison.frames_sampled,
        )
        return report

    strong = comparison.similarity < STRONG_MISMATCH_THRESHOLD

    # MANUAL_REVIEW, never FAIL — see FaceVerdict. The threshold decides who
    # looks next, not whether the athlete cheated.
    report.face = FaceOutcome(
        verdict=FaceVerdict.MANUAL_REVIEW,
        similarity=comparison.similarity,
        frames_with_face=comparison.frames_with_face,
        frames_sampled=comparison.frames_sampled,
    )

    report.add(
        CheatFinding(
            check=CheatCheck.FACE_MISMATCH,
            # Never HIGH, even for a strong mismatch. High severity implies
            # confidence this check has not earned, and severity drives how a
            # reviewer weights what they are looking at.
            severity=Severity.MEDIUM if strong else Severity.LOW,
            detail=(
                f"The face in the recording does not clearly match the "
                f"registration photo (similarity {comparison.similarity:.2f}, "
                f"expected above {SIMILARITY_THRESHOLD:.2f}). "
                "This check is approximate — please compare the photos yourself."
            ),
            evidence={
                "similarity": comparison.similarity,
                "threshold": SIMILARITY_THRESHOLD,
                "frames_with_face": float(comparison.frames_with_face),
                "frames_sampled": float(comparison.frames_sampled),
            },
        )
    )

    return report
