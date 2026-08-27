"""Pydantic request/response models.

Field names match docs/openapi.yaml exactly — the mobile client in Sprint 4 is
already coded against that contract, so a rename here is a wire break there.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Generic
# ---------------------------------------------------------------------------


class MessageResponse(BaseModel):
    message: str


class HealthResponse(BaseModel):
    status: str
    environment: str
    database: str
    storage: str


class ReadinessResponse(BaseModel):
    ready: bool
    database: bool
    broker: bool
    detail: str | None = None


# ---------------------------------------------------------------------------
# Auth (Sprint 7 implements the real flow; these keep the contract honest)
# ---------------------------------------------------------------------------


class RequestOtpRequest(BaseModel):
    phone: str = Field(min_length=6, max_length=20)


class VerifyOtpRequest(BaseModel):
    phone: str = Field(min_length=6, max_length=20)
    otp: str = Field(min_length=4, max_length=8)


class AuthResponse(BaseModel):
    access_token: str
    athlete_id: uuid.UUID


# ---------------------------------------------------------------------------
# Video upload
# ---------------------------------------------------------------------------


class UploadInitRequest(BaseModel):
    test_type: str
    file_size_bytes: int = Field(gt=0)
    checksum_sha256: str = Field(min_length=64, max_length=64)
    chunk_size_bytes: int | None = None
    content_type: str = "video/mp4"


class UploadSessionResponse(BaseModel):
    upload_id: str
    chunk_size_bytes: int
    received_chunks: list[int]
    expires_at: datetime | None = None


class UploadChunkResponse(BaseModel):
    received_chunks_count: int
    next_expected_index: int | None = None


class UploadCompleteResponse(BaseModel):
    video_id: uuid.UUID
    checksum_verified: bool


# ---------------------------------------------------------------------------
# Test submission
# ---------------------------------------------------------------------------


class SubmitTestRequest(BaseModel):
    test_id: str
    provisional_score: float
    video_id: uuid.UUID | None = None

    # Vertical jump cannot be re-scored server-side without the athlete's
    # standing height. Until Sprint 7 stores it on the profile, the client
    # sends it with the submission; the server prefers the profile value when
    # one exists, because a client-supplied height is a client-supplied input.
    athlete_height_cm: float | None = None


class SubmitTestResponse(BaseModel):
    result_id: uuid.UUID
    status: str


# ---------------------------------------------------------------------------
# Results
# ---------------------------------------------------------------------------


class FlagResponse(BaseModel):
    reason: str
    detail: str | None = None
    severity: str
    source: str
    created_at: datetime


class TestResultResponse(BaseModel):
    result_id: uuid.UUID
    test_type: str
    status: str
    provisional_score: float | None = None
    server_score: float | None = None
    final_score: float | None = None
    unit: str
    verified_at: datetime | None = None
    flags: list[FlagResponse] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Dashboard (Sprint 8 builds the UI; these endpoints exist to serve it)
# ---------------------------------------------------------------------------


class ReviewItemResponse(BaseModel):
    result_id: uuid.UUID
    athlete_name: str
    region: str
    test_type: str
    status: str
    provisional_score: float | None
    server_score: float | None
    flag_count: int
    created_at: datetime


class ReviewDetailResponse(BaseModel):
    result_id: uuid.UUID
    athlete_name: str
    region: str
    test_type: str
    status: str
    provisional_score: float | None
    server_score: float | None
    unit: str
    video_url: str | None
    flags: list[FlagResponse]
    created_at: datetime
    verified_at: datetime | None


class ReviewActionRequest(BaseModel):
    action: str
    notes: str | None = None


class ReviewActionResponse(BaseModel):
    result_id: uuid.UUID
    action: str
    status: str
    message: str
