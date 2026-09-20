import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from app.api import audio, briefs, devices, health, sync
from app.auth import router as auth_router
from app.briefs.builder import BriefService
from app.briefs.scheduler import BriefScheduler
from app.config import get_settings
from app.db.base import Base
from app.db.models import Alert
from app.db.session import get_engine, get_session_factory
from app.llm.gateway import LlmGateway

log = logging.getLogger(__name__)


def _push_brief_ready(db, alert: Alert) -> None:
    """Silent data message: the app builds its own notification (with Play / Open buttons)."""
    from app.push.fcm import send_to_all

    data = {"type": alert.kind, **{k: str(v) for k, v in (alert.payload or {}).items()}}
    send_to_all(db, alert.title, alert.body, data=data, notify=False)


def build_services(app: FastAPI) -> None:
    settings = get_settings()
    factory = get_session_factory()
    client = httpx.Client(timeout=30, follow_redirects=True, headers={"User-Agent": "CivilsCompanion/0.2 (personal study app)"})
    gateway = LlmGateway(settings, factory)
    service = BriefService(settings, factory, gateway, client, notifier=_push_brief_ready)
    app.state.llm_gateway = gateway
    app.state.brief_service = service
    app.state.brief_scheduler = BriefScheduler(settings, factory, service)
    app.state.http_client = client


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    # On the server (APP_ENV=prod) Alembic creates the tables; this keeps dev and tests simple.
    if settings.app_env != "prod":
        Base.metadata.create_all(get_engine())
    build_services(app)
    if settings.scheduler_enabled:
        app.state.brief_scheduler.start()
    yield
    app.state.brief_scheduler.shutdown()
    app.state.http_client.close()


def create_app() -> FastAPI:
    app = FastAPI(title="Civils Companion API", version="0.2.0", lifespan=lifespan)
    app.include_router(health.router)
    app.include_router(auth_router.router)
    app.include_router(devices.router)
    app.include_router(briefs.router)
    app.include_router(sync.router)
    app.include_router(audio.router)
    return app


app = create_app()
