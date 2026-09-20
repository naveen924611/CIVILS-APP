"""How much space the server uses, by type (spec 6.8 Storage)."""
import json
import shutil
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models_v2 import Job
from app.files import data_path
from app.tts.piper import audio_dir


def folder_size(folder: Path) -> tuple[int, int]:
    """(bytes, files) of regular files below a folder. Missing folder = zero. Links are not followed."""
    total = files = 0
    if not folder.is_dir():
        return 0, 0
    for p in folder.rglob("*"):
        try:
            if p.is_symlink() or not p.is_file():
                continue
            total += p.stat().st_size
            files += 1
        except OSError:
            continue
    return total, files


def database_files(settings: Settings) -> list[Path]:
    """The SQLite file and its write-ahead files. Empty for other databases."""
    url = settings.database_url
    if not url.startswith("sqlite:///"):
        return []
    base = Path(url.replace("sqlite:///", "", 1))
    return [Path(f"{base}{suffix}") for suffix in ("", "-wal", "-shm")]


def database_bytes(settings: Settings) -> int:
    total = 0
    for p in database_files(settings):
        try:
            total += p.stat().st_size if p.is_file() else 0
        except OSError:
            continue
    return total


def waiting_jobs(db: Session) -> tuple[int, int]:
    """(count, bytes) of queued or running jobs (work the server has not finished yet)."""
    count = db.scalar(select(func.count()).select_from(Job).where(
        Job.deleted.is_(False), Job.status.in_(("queued", "running")))) or 0
    size = 0
    rows = db.scalars(select(Job.payload_json).where(
        Job.deleted.is_(False), Job.status.in_(("queued", "running"))).limit(500))
    for payload in rows:
        size += len(json.dumps(payload or {}, default=str))
    return int(count), size


def compute_usage(settings: Settings, db: Session) -> dict:
    audio_b, audio_n = folder_size(audio_dir(settings))
    docs_b, docs_n = folder_size(data_path(settings, "uploads"))
    backups_b, backups_n = folder_size(data_path(settings, "backups"))
    study_b = database_bytes(settings)
    jobs_n, jobs_b = waiting_jobs(db)
    base = Path(settings.data_dir)
    try:
        disk = shutil.disk_usage(base if base.exists() else base.parent if base.parent.exists() else Path("."))
        disk_total, disk_free = disk.total, disk.free
    except OSError:
        disk_total = disk_free = 0
    return {
        "audio_bytes": audio_b,
        "audio_files": audio_n,
        "documents_bytes": docs_b,
        "document_files": docs_n,
        "study_data_bytes": study_b,
        "waiting_bytes": jobs_b,
        "waiting_jobs": jobs_n,
        "backups_bytes": backups_b,
        "backup_files": backups_n,
        "total_bytes": audio_b + docs_b + study_b + backups_b,
        "disk_total_bytes": disk_total,
        "disk_free_bytes": disk_free,
    }
