import logging
from contextlib import asynccontextmanager

import httpx
from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import Depends, FastAPI

from app.api import audio, briefs, devices, health, kv, sync
from app.auth import router as auth_router
from app.auth.deps import current_user
from app.briefs.builder import BriefService
from app.briefs.scheduler import BriefScheduler
from app.config import get_settings
from app.db.base import Base
from app.db.models import Alert
from app.db.session import get_engine, get_session_factory
from app.features import load_features
from app.llm.gateway import LlmGateway
from app.services import Services, make_push

log = logging.getLogger(__name__)


def _push_brief_ready(db, alert: Alert) -> None:
    """Silent data message: the app builds its own notification (with Play / Open buttons)."""
    from app.push.fcm import send_to_all

    data = {"type": alert.kind, **{k: str(v) for k, v in (alert.payload or {}).items()}}
    send_to_all(db, alert.title, alert.body, data=data, notify=False)


def build_services(app: FastAPI) -> Services:
    settings = get_settings()
    factory = get_session_factory()
    client = httpx.Client(timeout=30, follow_redirects=True, headers={"User-Agent": "CivilsCompanion/0.3 (personal study app)"})
    gateway = LlmGateway(settings, factory)
    scheduler = BackgroundScheduler(timezone=settings.timezone)
    services = Services(settings=settings, session_factory=factory, gateway=gateway, http=client,
                        scheduler=scheduler, push=make_push(factory))
    service = BriefService(settings, factory, gateway, client, notifier=_push_brief_ready)
    app.state.services = services
    app.state.llm_gateway = gateway
    app.state.brief_service = service
    app.state.brief_scheduler = BriefScheduler(settings, factory, service, scheduler)
    app.state.http_client = client
    return services


def _register_common_jobs(services: Services) -> None:
    """Retry queued jobs every few minutes (AI was busy, budget deferred them, server restarted)."""
    from app.jobs.runner import run_pending

    services.scheduler.add_job(run_pending, "interval", minutes=5, args=[services], id="jobs:retry",
                               replace_existing=True, coalesce=True, max_instances=1, misfire_grace_time=300)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    # On the server (APP_ENV=prod) Alembic creates the tables; this keeps dev and tests simple.
    if settings.app_env != "prod":
        Base.metadata.create_all(get_engine())
    services = build_services(app)
    for mod in load_features():
        setup = getattr(mod, "setup", None)
        if callable(setup):
            try:
                setup(services)
            except Exception:
                log.exception("feature %s failed to start", mod.__name__)
    if settings.scheduler_enabled:
        _register_common_jobs(services)
        app.state.brief_scheduler.start()
    yield
    app.state.brief_scheduler.shutdown()
    if services.scheduler.running:
        services.scheduler.shutdown(wait=False)
    app.state.http_client.close()


def create_app() -> FastAPI:
    app = FastAPI(title="Civils Companion API", version="0.3.0", lifespan=lifespan)
    app.include_router(health.router)
    app.include_router(auth_router.router)
    app.include_router(devices.router)
    app.include_router(briefs.router)
    app.include_router(sync.router)
    app.include_router(audio.router)
    app.include_router(kv.router)
    for mod in load_features():
        router = getattr(mod, "router", None)
        if router is not None:
            app.include_router(router, dependencies=[Depends(current_user)])
    return app


app = create_app()
