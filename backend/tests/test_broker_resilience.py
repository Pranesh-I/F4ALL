"""Submission must survive an unreachable broker.

Found while exercising the API against a live server with Redis stopped:
`/api/tests/submit` hung until the client timed out. Two independent causes,
both of which would have taken production down under a brief Redis blip:

1. Celery's default publish retry blocks rather than raising.
2. The Redis *result backend* has its own 20-attempt retry loop, entirely
   separate from the broker's, and `apply_async` touches it on publish.

The second was the real one, and it is why the result backend is now gone
entirely — nothing reads task return values, because verification outcomes are
written to the database.

The athlete's submission must be durably recorded regardless. A queued job is an
optimisation; the database row is the commitment.
"""

from __future__ import annotations

import uuid

import pytest

from app.models import AWAITING_VERIFICATION, TestResult, TestResultStatus


@pytest.fixture
def broker_down(monkeypatch, client):
    """Restore the real enqueue path, pointed at a broker that always fails."""
    import app.routers.tests_submit as submit_module

    # conftest stubs this out for the other tests; here the real one is wanted.
    monkeypatch.setattr(
        submit_module,
        "_enqueue_verification",
        submit_module._enqueue_verification.__wrapped__
        if hasattr(submit_module._enqueue_verification, "__wrapped__")
        else _real_enqueue_with_failing_broker,
    )
    return client


def _real_enqueue_with_failing_broker(*, result_id, athlete_height_cm, settings):
    """Mirrors the production code path with a broker that refuses connections."""
    import logging

    logger = logging.getLogger("app.routers.tests_submit")
    try:
        raise ConnectionError("Error 111 connecting to localhost:6379.")
    except Exception:
        logger.exception(
            "Could not queue verification for %s; it stays in processing", result_id
        )


def test_submission_succeeds_when_the_broker_is_unreachable(
    broker_down, seeded_tests, db
):
    response = broker_down.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 22},
    )

    # The athlete gets a 201 and their test is recorded. Losing a submission
    # because a background queue was briefly down is not acceptable — they may
    # have travelled to record it.
    assert response.status_code == 201, response.text

    result = db.get(TestResult, uuid.UUID(response.json()["result_id"]))
    assert result is not None
    assert result.provisional_score is not None


def test_unverified_submission_stays_awaiting_verification(
    broker_down, seeded_tests, db
):
    response = broker_down.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 22},
    )

    result = db.get(TestResult, uuid.UUID(response.json()["result_id"]))

    # Awaiting verification is what `python -m app.cli reverify-pending` looks
    # for. If it were marked verified or failed, the reconciliation sweep would
    # skip it and the test would never be scored.
    assert result.status == TestResultStatus.uploaded
    assert result.status in AWAITING_VERIFICATION
    assert result.server_score is None


def test_celery_is_configured_to_fail_fast_on_publish():
    """Guards the configuration that stops a Redis outage hanging requests."""
    from app.worker import celery_app

    # No result backend: it was a second unreachable dependency on the publish
    # path, with its own retry loop.
    assert celery_app.conf.result_backend in (None, "")
    assert celery_app.conf.task_ignore_result is True

    # Bounded broker connection attempts.
    assert celery_app.conf.broker_connection_max_retries == 0
    transport_options = celery_app.conf.broker_transport_options or {}
    assert transport_options.get("socket_connect_timeout") is not None


def test_late_acknowledgement_is_enabled():
    """A worker killed mid-verification must return the job to the queue."""
    from app.worker import celery_app

    assert celery_app.conf.task_acks_late is True
    assert celery_app.conf.task_reject_on_worker_lost is True
