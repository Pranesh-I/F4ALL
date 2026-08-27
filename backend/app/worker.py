"""Celery application."""

from __future__ import annotations

from celery import Celery

from .config import get_settings

settings = get_settings()

# No result backend, deliberately.
#
# Nothing ever reads a task's return value: the verification outcome is written
# to PostgreSQL, which is the system of record. Configuring Redis as a result
# backend added a second thing that must be reachable at publish time — and its
# own 20-attempt retry loop, independent of the broker's, which hung every
# submission request when Redis was down.
celery_app = Celery(
    "f4all",
    broker=settings.redis_url,
    include=["app.tasks"],
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    task_ignore_result=True,
    timezone="UTC",
    enable_utc=True,
    # Pose extraction over a 60-second video is minutes of work, not seconds.
    task_time_limit=15 * 60,
    task_soft_time_limit=13 * 60,
    # Acknowledge only after the task finishes, so a worker killed mid-job
    # returns the submission to the queue instead of losing it. An athlete's
    # test disappearing because a container restarted is not acceptable.
    task_acks_late=True,
    worker_prefetch_multiplier=1,
    task_reject_on_worker_lost=True,
    # Fail fast when the broker is unreachable rather than blocking the caller.
    # The API publishes to this broker inside a request; without these bounds
    # Celery retries the connection for a long time and the athlete's
    # submission request hangs instead of returning.
    broker_transport_options={
        "socket_timeout": 2,
        "socket_connect_timeout": 2,
        "retry_on_timeout": False,
    },
    broker_connection_retry_on_startup=False,
    broker_connection_max_retries=0,
)
