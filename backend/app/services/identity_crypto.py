"""Application-level encryption for the registration face photo.

Storage encryption (S3's AES256) protects against someone walking off with a
disk. It does not protect against anyone who can read the bucket — a leaked
credential, a misconfigured policy, a backup copied somewhere it should not be.
A child's face photo held by a government platform warrants the second layer,
so the photo is encrypted here, before storage ever sees it, with a key the
bucket does not hold.

Format: ``MAGIC | nonce (12 bytes) | AES-256-GCM ciphertext+tag``. The storage
key is bound in as associated data, so a blob copied to another athlete's key
fails to decrypt rather than showing one athlete's face on another's profile.

Photos stored before Sprint 8 have keys without the ``.enc`` suffix and are
read as they are; `python -m app.cli encrypt-photos` converts them.
"""

from __future__ import annotations

import base64
import hashlib
import logging
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from ..config import Settings

logger = logging.getLogger(__name__)

MAGIC = b"F4ID1"
NONCE_BYTES = 12
ENCRYPTED_SUFFIX = ".enc"


class IdentityDecryptError(RuntimeError):
    """The blob is not one this key encrypted for this storage key."""


def _key(settings: Settings) -> bytes:
    configured = settings.identity_encryption_key
    if configured:
        key = base64.urlsafe_b64decode(configured.encode("ascii"))
        if len(key) != 32:
            raise ValueError("identity_encryption_key must be 32 bytes, base64url")
        return key

    if settings.is_production:
        raise RuntimeError("identity_encryption_key must be set in production")

    # Development and tests only: derived from the JWT secret so a fresh
    # checkout works without another setting. Production refuses this above.
    return hashlib.sha256(b"f4all-identity|" + settings.jwt_secret.encode()).digest()


def is_encrypted_key(storage_key: str) -> bool:
    return storage_key.endswith(ENCRYPTED_SUFFIX)


def encrypt(settings: Settings, storage_key: str, plaintext: bytes) -> bytes:
    nonce = os.urandom(NONCE_BYTES)
    sealed = AESGCM(_key(settings)).encrypt(nonce, plaintext, storage_key.encode())
    return MAGIC + nonce + sealed


def decrypt(settings: Settings, storage_key: str, blob: bytes) -> bytes:
    if not blob.startswith(MAGIC):
        raise IdentityDecryptError("Not an encrypted identity photo")
    nonce = blob[len(MAGIC) : len(MAGIC) + NONCE_BYTES]
    sealed = blob[len(MAGIC) + NONCE_BYTES :]
    try:
        return AESGCM(_key(settings)).decrypt(nonce, sealed, storage_key.encode())
    except InvalidTag as exc:
        raise IdentityDecryptError("Identity photo failed to decrypt") from exc


def new_photo_key(athlete_id) -> str:
    return f"reference-faces/{athlete_id}/{os.urandom(16).hex()}.jpg{ENCRYPTED_SUFFIX}"


def store_photo(storage, settings: Settings, athlete_id, jpeg: bytes) -> str:
    """Encrypt and store a registration photo; returns its storage key."""
    key = new_photo_key(athlete_id)
    storage.store_bytes(
        key, encrypt(settings, key, jpeg), content_type="application/octet-stream"
    )
    return key


def load_photo(storage, settings: Settings, storage_key: str) -> bytes:
    """The photo's JPEG bytes, decrypting when it was stored encrypted."""
    import tempfile
    from pathlib import Path

    with tempfile.TemporaryDirectory(prefix="f4all-id-") as temporary:
        local = Path(temporary) / "photo"
        storage.fetch_to(storage_key, local)
        blob = local.read_bytes()

    if is_encrypted_key(storage_key):
        return decrypt(settings, storage_key, blob)
    return blob
