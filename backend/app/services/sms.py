"""SMS delivery.

Same shape as `storage.py`, for the same reason: the real dependency does not
exist yet (no SAI gateway account, no provider credentials), and a backend that
cannot start without one cannot be developed or tested against.

So there is one interface and three implementations — a console sender for
development, an in-memory sender for tests, and an HTTP sender for a real
gateway. Selecting anything but a real gateway in production raises at startup
rather than at 2am when an athlete cannot log in.

**On provider choice:** Indian transactional SMS requires DLT registration of
both the sender header and the message template with TRAI. That is paperwork SAI
holds, not something this code can arrange, so `HttpSmsSender` is deliberately
generic — a URL, a method and a payload template — rather than coupled to one
vendor's SDK. Swapping providers should be configuration, not a rewrite.
"""

from __future__ import annotations

import logging
import urllib.error
import urllib.parse
import urllib.request
from abc import ABC, abstractmethod

from ..config import Settings

logger = logging.getLogger(__name__)


class SmsError(RuntimeError):
    pass


class SmsSender(ABC):
    @abstractmethod
    def send(self, phone: str, message: str) -> None:
        """Deliver `message` to `phone`, or raise [SmsError]."""


class ConsoleSmsSender(SmsSender):
    """Logs instead of sending. Development only."""

    def send(self, phone: str, message: str) -> None:
        logger.info("[SMS -> %s] %s", _redact(phone), message)


class InMemorySmsSender(SmsSender):
    """Captures messages so tests can assert on them."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    def send(self, phone: str, message: str) -> None:
        self.sent.append((phone, message))


class HttpSmsSender(SmsSender):
    """Posts to a configured gateway endpoint.

    The payload is a template with `{phone}` and `{message}` placeholders, so
    the same class covers the several incompatible request shapes Indian SMS
    providers use without a vendor SDK in the dependency tree.
    """

    def __init__(self, settings: Settings):
        if not settings.sms_api_url:
            raise SmsError("SMS_API_URL is not configured")
        self.settings = settings

    def send(self, phone: str, message: str) -> None:
        settings = self.settings

        body = settings.sms_payload_template.format(
            phone=urllib.parse.quote(phone),
            message=urllib.parse.quote(message),
            sender_id=urllib.parse.quote(settings.sms_sender_id),
            template_id=urllib.parse.quote(settings.sms_template_id),
        )

        request = urllib.request.Request(
            settings.sms_api_url,
            data=body.encode(),
            method="POST",
            headers={
                "Content-Type": settings.sms_content_type,
                **(
                    {"Authorization": f"Bearer {settings.sms_api_key}"}
                    if settings.sms_api_key
                    else {}
                ),
            },
        )

        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                if response.status >= 400:
                    raise SmsError(f"SMS gateway returned {response.status}")
        except urllib.error.URLError as exc:
            # The message never names the code, and the phone is redacted. An
            # exception string ends up in logs and error trackers, which are
            # read by more people than the athlete's own SMS inbox.
            raise SmsError(
                f"SMS delivery to {_redact(phone)} failed: {exc.reason}"
            ) from exc


def get_sms_sender(settings: Settings) -> SmsSender:
    if settings.sms_backend == "http":
        return HttpSmsSender(settings)

    if settings.is_production:
        # Fails closed. A production deployment quietly logging OTPs to stdout
        # instead of sending them is an outage that looks like uptime.
        raise SmsError(
            f"SMS backend '{settings.sms_backend}' cannot be used in "
            f"{settings.environment}. Configure SMS_BACKEND=http."
        )

    if settings.sms_backend == "memory":
        return InMemorySmsSender()

    return ConsoleSmsSender()


def _redact(phone: str) -> str:
    """Last four digits only.

    Full numbers in logs are a standing re-identification risk, and these are
    largely minors' numbers.
    """
    return f"...{phone[-4:]}" if len(phone) > 4 else "..."


__all__ = [
    "ConsoleSmsSender",
    "HttpSmsSender",
    "InMemorySmsSender",
    "SmsError",
    "SmsSender",
    "get_sms_sender",
]
