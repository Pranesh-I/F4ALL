"""FastAPI application factory."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import get_settings
from .logging_config import RequestIdMiddleware, configure_logging
from .routers import (
    athletes,
    auth,
    dashboard,
    dashboard_auth,
    health,
    media,
    tests_submit,
    videos,
)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()

    # Staging directories must exist before the first chunk arrives, not on
    # first use — a mkdir race across workers loses uploads.
    settings.upload_staging_path.mkdir(parents=True, exist_ok=True)
    if settings.storage_backend == "local":
        settings.storage_local_path.mkdir(parents=True, exist_ok=True)

    logger.info(
        "F4ALL backend starting: env=%s storage=%s",
        settings.environment,
        settings.storage_backend,
    )

    if settings.unauthenticated_allowed:
        logger.warning(
            "Unauthenticated access is ENABLED. This is a development "
            "affordance and is ignored in production."
        )

    yield

    logger.info("F4ALL backend shutting down")


def create_app() -> FastAPI:
    settings = get_settings()

    configure_logging(debug=settings.debug, json_output=settings.is_production)

    app = FastAPI(
        title="F4ALL Sports Talent Assessment API",
        description=(
            "Backend for the SAI talent assessment platform. The mobile app "
            "produces a provisional on-device score; this service independently "
            "re-verifies every submission and its score is the one that counts."
        ),
        version="1.0.0",
        lifespan=lifespan,
    )

    app.add_middleware(RequestIdMiddleware)

    # The official dashboard runs on its own origin. Bearer tokens, not
    # cookies, so credentials are not enabled and CSRF does not apply.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_methods=["GET", "POST", "PATCH", "PUT", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
        expose_headers=["X-Request-ID", "Retry-After", "X-Total-Count"],
    )

    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(athletes.router)
    app.include_router(videos.router)
    app.include_router(tests_submit.router)
    app.include_router(dashboard_auth.router)
    app.include_router(dashboard.router)
    app.include_router(media.router)

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception):
        # Never leak a stack trace to a client. The request id in the response
        # is what ties a user's report back to the logged trace.
        logger.exception("Unhandled error on %s %s", request.method, request.url.path)
        return JSONResponse(
            status_code=500,
            content={"detail": "Internal server error"},
        )

    return app


app = create_app()
