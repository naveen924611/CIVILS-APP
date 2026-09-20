"""The shared objects every feature needs (settings, database sessions, AI gateway, push, scheduler).

Built once at start-up and stored on `app.state.services`. Feature modules get it in `setup(services)`
and in route handlers through `Depends(get_services)`.
"""
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx
from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import Request
from sqlalchemy.orm import Session

from app.config import Settings

log = logging.getLogger(__name__)

PushFn = Callable[[str, str, dict[str, str]], int]


def make_push(session_factory: Callable[[], Session]) -> PushFn:
    """Silent FCM data message: the app builds its own notification. Never raises."""

    def push(title: str, body: str, data: dict[str, str]) -> int:
        try:
            from app.push.fcm import send_to_all

            with session_factory() as db:
                return send_to_all(db, title, body, data={k: str(v) for k, v in data.items()}, notify=False)
        except Exception as exc:  # FCM not configured, offline, ... never break the caller
            log.warning("push not sent: %s", exc)
            return 0

    return push


@dataclass
class Services:
    settings: Settings
    session_factory: Callable[[], Session]
    gateway: Any  # LlmGateway (tests pass a fake with the same methods)
    http: httpx.Client
    scheduler: BackgroundScheduler
    push: PushFn
    extras: dict[str, Any] = field(default_factory=dict)  # feature-specific singletons

    def kick_jobs(self) -> None:
        """Start working through queued jobs now (in a background thread)."""
        from app.jobs.runner import kick

        kick(self)


def get_services(request: Request) -> Services:
    return request.app.state.services
