from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import devices, health
from app.auth import router as auth_router
from app.config import get_settings
from app.db.base import Base
from app.db.session import get_engine


@asynccontextmanager
async def lifespan(_: FastAPI):
    # On the server (APP_ENV=prod) Alembic creates the tables; this keeps dev and tests simple.
    if get_settings().app_env != "prod":
        Base.metadata.create_all(get_engine())
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="Civils Companion API", version="0.1.0", lifespan=lifespan)
    app.include_router(health.router)
    app.include_router(auth_router.router)
    app.include_router(devices.router)
    return app


app = create_app()
