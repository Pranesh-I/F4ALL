"""Push delivery, behind one interface.

Same shape as ``services/sms.py``: the real dependency (a Firebase project
SAI owns) does not exist yet, so there is a console provider for development,
an in-memory one for tests, "inbox" for running without pushes, and an FCM
HTTP v1 provider for production.

Each send reports one of three outcomes, because each needs different
handling: delivered; the token is dead (disable it, never retry it); or a
failure worth retrying later (the provider is down, rate-limited, or our
access token expired).
"""

from __future__ import annotations

import enum
import json
import logging
import time
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import jwt

from ..config import Settings

logger = logging.getLogger(__name__)


class Outcome(enum.Enum):
    DELIVERED = "delivered"
    INVALID_TOKEN = "invalid_token"
    RETRY = "retry"


@dataclass(frozen=True)
class SendResult:
    outcome: Outcome
    detail: str = ""


@dataclass(frozen=True)
class Push:
    token: str
    title: str
    body: str
    # Flat string values only: FCM data payloads carry nothing else.
    data: dict[str, str]


class PushProvider(ABC):
    @abstractmethod
    def send(self, push: Push) -> SendResult: ...


class ConsolePushProvider(PushProvider):
    """Logs instead of sending. Development only."""

    def send(self, push: Push) -> SendResult:
        logger.info("[PUSH -> %s…] %s — %s", push.token[:8], push.title, push.body)
        return SendResult(Outcome.DELIVERED)


class InMemoryPushProvider(PushProvider):
    """Records pushes, and answers per token as a test tells it to."""

    def __init__(self, answers: dict[str, Outcome] | None = None) -> None:
        self.sent: list[Push] = []
        self.answers = answers or {}

    def send(self, push: Push) -> SendResult:
        self.sent.append(push)
        return SendResult(self.answers.get(push.token, Outcome.DELIVERED))


# A transport is "POST this JSON with these headers, give me (status, body)",
# so the FCM provider is tested without a network or a Firebase project.
Transport = Callable[[str, dict[str, str], bytes], tuple[int, bytes]]

TOKEN_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
# FCM's own words for a token that will never work again. INVALID_ARGUMENT is
# also what a malformed message gets, so it only counts when FCM says the
# token is what was invalid — a bug in our payload must not disable every
# athlete's phone.
DEAD_TOKEN_CODES = {"UNREGISTERED", "SENDER_ID_MISMATCH"}


class FcmPushProvider(PushProvider):
    """Firebase Cloud Messaging, HTTP v1 API, authenticated as a service account.

    The OAuth access token is fetched with a self-signed JWT (RFC 7523) and
    kept until shortly before it expires; a 401 drops it so the next send
    fetches a fresh one.
    """

    def __init__(self, settings: Settings, transport: Transport | None = None) -> None:
        if not (settings.fcm_project_id and settings.fcm_service_account_file):
            raise ValueError("FCM needs FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_FILE")
        account = json.loads(
            Path(settings.fcm_service_account_file).read_text(encoding="utf-8")
        )
        self.client_email = account["client_email"]
        self.private_key = account["private_key"]
        self.token_uri = account.get("token_uri", "https://oauth2.googleapis.com/token")
        self.url = (
            f"{settings.fcm_api_base.rstrip('/')}/v1/projects/"
            f"{settings.fcm_project_id}/messages:send"
        )
        self.transport = transport or _urllib_transport
        self._access_token: str | None = None
        self._expires_at = 0.0

    def send(self, push: Push) -> SendResult:
        try:
            token = self._token()
        except Exception as exc:  # any auth failure is retryable
            return SendResult(Outcome.RETRY, f"auth: {exc}"[:300])

        message = {
            "message": {
                "token": push.token,
                "notification": {"title": push.title, "body": push.body},
                "data": push.data,
                "android": {"priority": "high"},
            }
        }
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        try:
            status, body = self.transport(self.url, headers, json.dumps(message).encode())
        except OSError as exc:
            return SendResult(Outcome.RETRY, f"network: {exc}"[:300])

        if 200 <= status < 300:
            return SendResult(Outcome.DELIVERED)
        code, reason = _fcm_error(body)
        dead = code in DEAD_TOKEN_CODES or (
            code == "INVALID_ARGUMENT" and "registration token" in reason.lower()
        )
        # 403 is how FCM answers SENDER_ID_MISMATCH: a token from another project.
        if status in (400, 403, 404) and dead:
            return SendResult(Outcome.INVALID_TOKEN, code or "")
        if status == 401:
            self._access_token = None
        return SendResult(Outcome.RETRY, f"{status} {code or ''}".strip())

    def _token(self) -> str:
        if self._access_token and time.time() < self._expires_at - 60:
            return self._access_token
        now = int(time.time())
        assertion = jwt.encode(
            {
                "iss": self.client_email,
                "scope": TOKEN_SCOPE,
                "aud": self.token_uri,
                "iat": now,
                "exp": now + 3600,
            },
            self.private_key,
            algorithm="RS256",
        )
        form = (
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
            f"&assertion={assertion}"
        ).encode()
        status, body = self.transport(
            self.token_uri, {"Content-Type": "application/x-www-form-urlencoded"}, form
        )
        if status != 200:
            raise RuntimeError(f"token endpoint answered {status}")
        answer = json.loads(body)
        self._access_token = answer["access_token"]
        self._expires_at = time.time() + int(answer.get("expires_in", 3600))
        return self._access_token


def provider_for(settings: Settings) -> PushProvider | None:
    """The configured provider; None means inbox only, no pushes."""
    name = settings.notification_provider
    if name == "fcm":
        return FcmPushProvider(settings)
    if name == "inbox":
        return None
    return ConsolePushProvider()


def _fcm_error(body: bytes) -> tuple[str | None, str]:
    """The FCM error code (e.g. "UNREGISTERED") and message from an error body."""
    try:
        error = json.loads(body).get("error", {})
    except (ValueError, AttributeError):
        return None, ""
    message = str(error.get("message", ""))
    for detail in error.get("details", []):
        if isinstance(detail, dict) and detail.get("errorCode"):
            return detail["errorCode"], message
    return error.get("status"), message


def _urllib_transport(
    url: str, headers: dict[str, str], body: bytes
) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
