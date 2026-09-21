"""Storage (M7): usage by type, old-audio cleanup, export of the owner's data, nightly backups.

Routes (login required, added by the app):
  GET  /storage/usage                 bytes by type on the server, plus limit_bytes (setting storage.limit_gb, default 20)
  POST /storage/cleanup               {"audio_older_than_days": 60} deletes old audio and clears the references
  GET  /storage/export                zip of every synced table, notes as markdown, list of documents
  GET  /storage/backups               list of nightly backups (newest first)
  POST /storage/backups/run           make a backup now
"""
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session
from starlette.background import BackgroundTask

from app.api.kv import get_kv
from app.config import Settings, get_settings
from app.db.session import get_db
from app.services import Services

from .backups import list_backups, nightly_backup, run_backup
from .cleanup import cleanup_audio
from .export import build_export
from .ratelimit import rate_limit
from .usage import compute_usage

router = APIRouter(prefix="/storage", tags=["storage"])


class CleanupIn(BaseModel):
    audio_older_than_days: int = Field(default=60, ge=1, le=3650)


@router.get("/usage")
def usage(db: Session = Depends(get_db), settings: Settings = Depends(get_settings)):
    out = compute_usage(settings, db)
    limit_gb = get_kv(db, "storage.limit_gb", 20)  # the owner's own limit (Settings), default 20 GB
    limit_gb = limit_gb if isinstance(limit_gb, (int, float)) and not isinstance(limit_gb, bool) and limit_gb > 0 else 20
    out["limit_bytes"] = int(limit_gb * 1024**3)
    return out


@router.post("/cleanup", dependencies=[Depends(rate_limit(6, 60, "storage-cleanup"))])
def cleanup(body: CleanupIn | None = None, db: Session = Depends(get_db), settings: Settings = Depends(get_settings)):
    days = (body or CleanupIn()).audio_older_than_days
    return cleanup_audio(settings, db, days)


@router.get("/export", dependencies=[Depends(rate_limit(4, 300, "storage-export"))])
def export(db: Session = Depends(get_db)):
    fd, name = tempfile.mkstemp(prefix="civils-export-", suffix=".zip")
    os.close(fd)
    target = Path(name)
    try:
        build_export(db, target)
    except Exception:
        target.unlink(missing_ok=True)
        raise
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    return FileResponse(target, media_type="application/zip", filename=f"civils-companion-{stamp}.zip",
                        background=BackgroundTask(target.unlink, missing_ok=True))


@router.get("/backups")
def backups(settings: Settings = Depends(get_settings)):
    return {"keep": 7, "backups": list_backups(settings)}


@router.post("/backups/run", dependencies=[Depends(rate_limit(3, 300, "storage-backup"))])
def backup_now(settings: Settings = Depends(get_settings)):
    path = run_backup(settings)
    if path is None:
        raise HTTPException(409, "Backups need the SQLite database file")
    return {"name": path.name, "bytes": path.stat().st_size}


def setup(services: Services) -> None:
    """Nightly backup at 03:30 (server time zone). Only when the scheduler is on (tests turn it off)."""
    if services.settings.scheduler_enabled:
        services.scheduler.add_job(nightly_backup, "cron", hour=3, minute=30, id="storage:backup",
                                   replace_existing=True, coalesce=True, max_instances=1,
                                   misfire_grace_time=3600, args=[services])

