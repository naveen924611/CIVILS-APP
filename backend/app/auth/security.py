import hashlib
import secrets
from datetime import datetime, timedelta, timezone

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

from app.config import Settings

_hasher = PasswordHasher()
ALGO = "HS256"


def verify_password(password: str, stored_hash: str) -> bool:
    if not stored_hash:
        return False
    try:
        return _hasher.verify(stored_hash, password)
    except (VerificationError, InvalidHashError):
        return False


def create_access_token(settings: Settings, subject: str) -> tuple[str, int]:
    expires = timedelta(minutes=settings.access_token_minutes)
    now = datetime.now(timezone.utc)
    token = jwt.encode(
        {"sub": subject, "type": "access", "iat": now, "exp": now + expires},
        settings.jwt_secret,
        algorithm=ALGO,
    )
    return token, int(expires.total_seconds())


def decode_access_token(settings: Settings, token: str) -> str | None:
    try:
        data = jwt.decode(token, settings.jwt_secret, algorithms=[ALGO])
    except jwt.PyJWTError:
        return None
    if data.get("type") != "access":
        return None
    return data.get("sub")


def new_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()
