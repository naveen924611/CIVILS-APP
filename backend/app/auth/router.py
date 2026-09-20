import hmac
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import security
from app.auth.deps import current_user
from app.auth.ratelimit import limiter
from app.config import Settings, get_settings
from app.db.models import RefreshToken
from app.db.session import get_db

router = APIRouter(prefix="/auth", tags=["auth"])


class LoginIn(BaseModel):
    username: str
    password: str


class RefreshIn(BaseModel):
    refresh_token: str


class TokensOut(BaseModel):
    access_token: str
    refresh_token: str
    expires_in: int
    token_type: str = "bearer"


def _issue(settings: Settings, db: Session, user: str) -> TokensOut:
    access, ttl = security.create_access_token(settings, user)
    refresh = security.new_refresh_token()
    db.add(
        RefreshToken(
            token_hash=security.hash_refresh_token(refresh),
            expires_at=datetime.now(timezone.utc) + timedelta(days=settings.refresh_token_days),
        )
    )
    db.commit()
    return TokensOut(access_token=access, refresh_token=refresh, expires_in=ttl)


@router.post("/login", response_model=TokensOut)
def login(
    body: LoginIn,
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Session = Depends(get_db),
):
    ip = request.client.host if request.client else "unknown"
    if limiter.blocked(ip, settings.login_max_failures, settings.login_window_seconds):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many attempts. Try later.")

    # Always run both checks so timing does not reveal which one failed.
    user_ok = hmac.compare_digest(body.username, settings.owner_username)
    pw_ok = security.verify_password(body.password, settings.owner_password_hash)
    if not (user_ok and pw_ok):
        limiter.record_failure(ip, settings.login_window_seconds)
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Wrong username or password")

    limiter.reset(ip)
    return _issue(settings, db, settings.owner_username)


@router.post("/refresh", response_model=TokensOut)
def refresh(
    body: RefreshIn,
    settings: Settings = Depends(get_settings),
    db: Session = Depends(get_db),
):
    row = db.scalar(
        select(RefreshToken).where(
            RefreshToken.token_hash == security.hash_refresh_token(body.refresh_token)
        )
    )
    now = datetime.now(timezone.utc)
    expires = row.expires_at if row else None
    if expires is not None and expires.tzinfo is None:  # SQLite drops tz info
        expires = expires.replace(tzinfo=timezone.utc)
    if row is None or row.revoked or expires < now:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Please log in again")
    row.revoked = True  # rotation: each refresh token works once
    db.commit()
    return _issue(settings, db, settings.owner_username)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(body: RefreshIn, _: str = Depends(current_user), db: Session = Depends(get_db)):
    row = db.scalar(
        select(RefreshToken).where(
            RefreshToken.token_hash == security.hash_refresh_token(body.refresh_token)
        )
    )
    if row:
        row.revoked = True
        db.commit()


@router.get("/me")
def me(user: str = Depends(current_user)):
    return {"username": user}
