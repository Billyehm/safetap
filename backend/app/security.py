"""PIN hashing, bearer tokens, medical-profile encryption, Nigerian phone numbers."""

import base64
import hashlib
import json
import re
import secrets

from cryptography.fernet import Fernet

from . import config

# Fernet key derived from the app secret. Good enough for the MVP;
# production should use a proper key manager (AWS KMS / GCP KMS).
_fernet = Fernet(base64.urlsafe_b64encode(hashlib.sha256(config.SECRET_KEY.encode()).digest()))


def hash_pin(pin: str) -> str:
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt.encode(), 200_000).hex()
    return f"{salt}${digest}"


def verify_pin(pin: str, pin_hash: str) -> bool:
    try:
        salt, digest = pin_hash.split("$", 1)
    except ValueError:
        return False
    check = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt.encode(), 200_000).hex()
    return secrets.compare_digest(check, digest)


def make_token() -> str:
    return secrets.token_urlsafe(32)


def make_tracking_token() -> str:
    return secrets.token_urlsafe(24)


def encrypt_medical(profile: dict) -> str:
    return _fernet.encrypt(json.dumps(profile).encode()).decode()


def decrypt_medical(blob: str) -> dict:
    if not blob:
        return {}
    try:
        return json.loads(_fernet.decrypt(blob.encode()).decode())
    except Exception:
        return {}


def normalize_ng_phone(raw: str) -> str:
    """Accept +2348012345678, 2348012345678, 08012345678. Return +2348012345678."""
    digits = re.sub(r"\D", "", raw or "")
    if digits.startswith("234"):
        digits = digits[3:]
    digits = digits.lstrip("0")
    if len(digits) != 10 or not digits.isdigit():
        raise ValueError(f"Not a valid Nigerian phone number: {raw!r}")
    return "+234" + digits


def google_maps_link(lat: float, lng: float) -> str:
    return f"https://maps.google.com/?q={lat},{lng}"
