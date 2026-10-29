"""Sprint 20: the secret scanner finds credentials and lets placeholders through.

The samples are assembled at run time so this file does not trip the scanner
it tests.
"""

from __future__ import annotations

import pytest

from scripts.scan_secrets import scan_repository, scan_text

LIVE = {
    "AWS access key": "aws_id = " + "AKIA" + "Q7RZ4MPLK2WX9TVB",
    "private key": "-----BEGIN " + "RSA PRIVATE KEY-----",
    "Google API key": "key: " + "AIza" + "SyD3kq9Zr1Lm0Pq7Xc2Vb8Nn4Tt6Yy5Uu1W",
    "GitHub token": "gh" + "p_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8",
    "secret-named setting": 'JWT_SECRET = "' + "q8Zt3LxV9mN2pR7wK4yB6c" + '"',
}


@pytest.mark.parametrize(("kind", "line"), LIVE.items())
def test_a_live_credential_is_found_and_named_without_its_value(kind, line):
    (finding,) = scan_text(f"first line\n{line}\n", "settings.py")
    assert (finding.path, finding.line, finding.kind) == ("settings.py", 2, kind)
    assert line.split()[-1].strip('"') not in str(finding)


@pytest.mark.parametrize(
    "line",
    [
        'jwt_secret: str = "dev-secret-change-me"',
        'Settings(jwt_secret="someone-elses-secret")',
        'TOKEN_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"',
        'password = "correct horse battery staple"',
        'api_key = "${API_KEY}"',
        "S3_SECRET_ACCESS_KEY=<from the secrets manager>",
        'token = "q8Zt3LxV9mN2pR7wK4yB6c"  # secret-scan: allow (published vector)',
        "secret = os.environ['JWT_SECRET']",
    ],
)
def test_placeholders_and_references_are_allowed(line):
    assert scan_text(line) == []


def test_the_repository_holds_no_secrets():
    findings = scan_repository()
    assert findings == [], "\n".join(map(str, findings))
