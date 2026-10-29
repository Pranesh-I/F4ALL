"""Fail when a credential is about to be committed (Sprint 20).

    python -m scripts.scan_secrets          # from backend/; scans the repository

Scans every file git would commit — tracked files plus untracked ones that are
not ignored — for things that look like live credentials: cloud access keys,
private keys, provider API tokens, and secret-named settings given a long
literal value. Exits 1 and names each finding (file, line, kind — never the
value itself) when anything is found.

Development defaults (``dev-secret-change-me``), test values and documentation
placeholders are recognised and allowed. A line that must keep a value that
looks like a secret, such as a published test vector, can say so with the
comment ``secret-scan: allow``.
"""

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    (
        "private key",
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    ),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[0-9A-Za-z]{36,}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[0-9A-Za-z-]{10,}\b")),
    ("Stripe key", re.compile(r"\b(?:sk|rk)_live_[0-9A-Za-z]{20,}\b")),
    (
        "secret-named setting",
        re.compile(
            r"""(?ix)
            \b[\w.]*(?:secret|password|passwd|api[_-]?key|access[_-]?key|token|private[_-]?key)
            [\w.]*\b ["']? \s* [:=] \s* ["']([^"'\s$<>{}]{16,})["']
            """
        ),
    ),
]

# Values that are placeholders, not secrets.
PLACEHOLDER = re.compile(
    r"(?i)change[-_]?me|example|placeholder|dummy|test|fake|sample|dev[-_]|"
    r"local|your[-_]|x{6,}|\*{4,}|redacted|not[-_]a[-_]secret"
)
# Words joined by hyphens or underscores ("someone-elses-secret") and URLs are
# names and test values; a generated secret is neither.
READABLE = re.compile(r"^(?:[a-z]+[-_])+[a-z]+$|://")
ALLOW_MARKER = "secret-scan: allow"
SKIPPED_SUFFIXES = {
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".ico",
    ".mp4",
    ".mov",
    ".tflite",
    ".task",
    ".jar",
    ".zip",
    ".pdf",
    ".woff",
    ".woff2",
    ".ttf",
    ".keystore",
    ".jks",
}
SKIPPED_NAMES = {"package-lock.json"}


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    kind: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: possible {self.kind}"


def scan_text(text: str, path: str = "<text>") -> list[Finding]:
    findings: list[Finding] = []
    for number, line in enumerate(text.splitlines(), start=1):
        if ALLOW_MARKER in line:
            continue
        for kind, pattern in PATTERNS:
            for match in pattern.finditer(line):
                value = match.group(match.lastindex or 0)
                if kind == "secret-named setting" and (
                    PLACEHOLDER.search(value) or READABLE.search(value)
                ):
                    continue
                findings.append(Finding(path, number, kind))
                break
    return findings


def files_to_scan(root: Path = REPO_ROOT) -> list[Path]:
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8", "replace")
    paths = []
    for name in filter(None, listed.split("\0")):
        path = root / name
        if path.suffix.lower() in SKIPPED_SUFFIXES or path.name in SKIPPED_NAMES:
            continue
        if path.is_file():
            paths.append(path)
    return paths


def scan_repository(root: Path = REPO_ROOT) -> list[Finding]:
    findings: list[Finding] = []
    for path in files_to_scan(root):
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue  # binary or unreadable: not source
        findings.extend(scan_text(text, path.relative_to(root).as_posix()))
    return findings


def main() -> int:
    findings = scan_repository()
    for finding in findings:
        print(finding)
    if findings:
        print(
            f"{len(findings)} possible secret(s). Move each to the environment, or"
            f" mark a deliberate test value with '{ALLOW_MARKER}'."
        )
        return 1
    print("No secrets found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
