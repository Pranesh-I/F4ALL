"""Errors with a stable machine-readable code.

The body keeps FastAPI's ``detail`` string — the mobile app shows it and reads
nothing else (``F4allApi.detailOf``) — and adds ``code`` for clients that want
to branch on the reason without matching English text.
"""

from __future__ import annotations

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse


class ApiError(HTTPException):
    def __init__(self, status_code: int, code: str, detail: str) -> None:
        super().__init__(status_code=status_code, detail=detail)
        self.code = code


async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail, "code": exc.code},
        headers=exc.headers,
    )
