"""Nightly database backup (uses app/tools/backup.py) and the list of backups on the server."""
import logging
import re
from datetime import datetime, timezone
from pathlib import Path

from app.config import Settings
from app.files import data_path
from app.services import Services
from app.tools.backup import create_backup

log = logging.getLogger(__name__)

KEEP = 7
NAME = re.compile(r"^civils-backup-[\d-]+\.tar\.gz$")


def backups_dir(settings: Settings) -> Path:
    return data_path(settings, "backups")


def _sqlite_file(settings: Settings) -> Path | None:
    url = settings.database_url
    return Path(url.replace("sqlite:///", "", 1)) if url.startswith("sqlite:///") else None


def run_backup(settings: Settings, keep: int = KEEP) -> Path | None:
    """One backup into DATA_DIR/backups, keeping the newest `keep`. None when the database is not SQLite."""
    db_file = _sqlite_file(settings)
    if db_file is None or not db_file.is_file():
        log.warning("backup skipped: no SQLite database file")
        return None
    return create_backup(Path(settings.data_dir), db_file, backups_dir(settings), keep)


def nightly_backup(services: Services) -> None:
    try:
        path = run_backup(services.settings)
        if path:
            log.info("nightly backup written: %s", path.name)
    except Exception:  # never let a backup problem stop the scheduler
        log.exception("nightly backup failed")


def list_backups(settings: Settings) -> list[dict]:
    folder = backups_dir(settings)
    if not folder.is_dir():
        return []
    rows = []
    for p in folder.iterdir():
        if p.is_file() and NAME.match(p.name):
            st = p.stat()
            rows.append({"name": p.name, "bytes": st.st_size,
                         "created_at": datetime.fromtimestamp(st.st_mtime, timezone.utc).isoformat().replace("+00:00", "Z")})
    return sorted(rows, key=lambda r: r["name"], reverse=True)
