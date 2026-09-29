"""The API contract: docs/openapi.yaml, the plan's API list, and the code agree.

`docs/openapi.yaml` is what the mobile app and dashboard are built against. It
is generated from the code (`python -m app.cli export-openapi`), and this test
fails when someone changes an endpoint without regenerating it — the moment a
hand-kept contract starts lying is the moment nobody can trust it.

The comparison is of the API's surface — operations, parameters, request and
response models, and each model's fields — rather than the raw text, so a
FastAPI upgrade that reformats the document does not fail the build, while a
changed or removed field does.
"""

from __future__ import annotations

import yaml

from app import cli

METHODS = {"get", "post", "put", "patch", "delete"}


def surface(document: dict) -> dict:
    operations = {}
    for path, item in document["paths"].items():
        for method, operation in item.items():
            if method not in METHODS:
                continue
            body = operation.get("requestBody", {}).get("content", {})
            operations[f"{method.upper()} {path}"] = {
                "parameters": sorted(
                    (p["name"], p["in"], bool(p.get("required")))
                    for p in operation.get("parameters", [])
                ),
                "body": sorted(
                    str(content.get("schema", {}).get("$ref", content.get("schema")))
                    for content in body.values()
                ),
                "responses": sorted(operation.get("responses", {})),
            }

    models = {
        name: {
            "fields": sorted(schema.get("properties", {})),
            "required": sorted(schema.get("required", [])),
        }
        for name, schema in document.get("components", {}).get("schemas", {}).items()
    }
    return {"operations": operations, "models": models}


def committed() -> dict:
    return yaml.safe_load(cli.OPENAPI_PATH.read_text(encoding="utf-8"))


def test_the_committed_contract_matches_the_code():
    code, document = surface(cli.openapi_document()), surface(committed())

    added = sorted(set(code["operations"]) - set(document["operations"]))
    removed = sorted(set(document["operations"]) - set(code["operations"]))
    assert not added and not removed, (
        f"Endpoints added {added} / removed {removed} without regenerating "
        "docs/openapi.yaml — run `python -m app.cli export-openapi`"
    )
    assert code == document, (
        "docs/openapi.yaml is out of date — run `python -m app.cli export-openapi`"
    )


# The Sprint 10 plan's API list, and where each is served. The plan's short
# paths are grouped under /api here, and a few are one endpoint rather than
# two (sign-in *is* OTP verification; registration needs a verified phone).
PLAN_APIS = {
    # Auth
    "POST /auth/register": "POST /api/athletes/register",
    "POST /auth/send-otp": "POST /api/auth/request-otp",
    "POST /auth/verify-otp": "POST /api/auth/verify-otp",
    "POST /auth/login": "POST /api/auth/verify-otp",
    # Athlete
    "GET /athlete/profile": "GET /api/athletes/me",
    "PUT /athlete/profile": "PATCH /api/athletes/me",
    "GET /athlete/history": "GET /api/athletes/me/history",
    "GET /athlete/personal-bests": "GET /api/athletes/me/personal-bests",
    # Sessions
    "GET /sessions/active": "GET /api/sessions/active",
    "GET /sessions/{id}": "GET /api/sessions/{session_id}",
    "POST /admin/sessions": "POST /api/dashboard/sessions",
    "PUT /admin/sessions/{id}": "PATCH /api/dashboard/sessions/{session_id}",
    "DELETE /admin/sessions/{id}": "DELETE /api/dashboard/sessions/{session_id}",
    # Tests
    "POST /tests/practice": "PUT /api/athletes/me/practice/{client_attempt_id}",
    "POST /tests/session": "POST /api/tests/submit",
    "GET /tests/{id}": "GET /api/results/{result_id}",
    # Upload
    "POST /uploads/initiate": "POST /api/videos/upload/init",
    "POST /uploads/chunk": "PUT /api/videos/upload/{upload_id}/chunks/{index}",
    "POST /uploads/complete": "POST /api/videos/upload/{upload_id}/complete",
    # Verification
    "POST /verification/process": "POST /api/verification/{result_id}/process",
    "GET /verification/{submission_id}": "GET /api/verification/{result_id}",
}


def test_every_api_in_the_plan_is_served():
    served = set(surface(cli.openapi_document())["operations"])
    missing = {plan: ours for plan, ours in PLAN_APIS.items() if ours not in served}
    assert not missing, f"Planned APIs with no endpoint: {missing}"
