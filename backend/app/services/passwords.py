"""Password hashing for official accounts.

scrypt from the standard library rather than a third-party package: it is
memory-hard, it has been in `hashlib` since Python 3.6, and it keeps a
credential-handling dependency out of the tree.

Format: ``scrypt$n$r$p$salt_hex$hash_hex`` — parameters are stored with the hash
so they can be raised later without invalidating existing passwords.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets

N = 2**14
R = 8
P = 1
SALT_BYTES = 16
KEY_BYTES = 32

MIN_PASSWORD_LENGTH = 12


class WeakPassword(ValueError):
    pass


def validate_strength(password: str) -> None:
    """Length over composition rules.

    These accounts approve results that decide selection. Twelve characters is
    a floor, and length does far more for guessing resistance than demanding a
    symbol somewhere.
    """
    if len(password) < MIN_PASSWORD_LENGTH:
        raise WeakPassword(
            f"Password must be at least {MIN_PASSWORD_LENGTH} characters"
        )


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(SALT_BYTES)
    digest = hashlib.scrypt(
        password.encode(), salt=salt, n=N, r=R, p=P, dklen=KEY_BYTES
    )
    return f"scrypt${N}${R}${P}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str | None) -> bool:
    if not stored:
        return False

    try:
        scheme, n, r, p, salt_hex, hash_hex = stored.split("$")
        if scheme != "scrypt":
            return False
        expected = bytes.fromhex(hash_hex)
        actual = hashlib.scrypt(
            password.encode(),
            salt=bytes.fromhex(salt_hex),
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(expected),
        )
    except (ValueError, TypeError):
        return False

    return hmac.compare_digest(expected, actual)


# A real hash of a random string, verified against when the email is unknown.
# Without it, "no such official" returns instantly and "wrong password" takes
# the time of an scrypt, and the difference tells an attacker which emails are
# real accounts.
DUMMY_HASH = hash_password(secrets.token_urlsafe(24))
