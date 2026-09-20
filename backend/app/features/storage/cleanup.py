"""Delete old audio files and forget the references to them, so nothing points at a missing file."""
import logging
import time
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import NewsItem
from app.db.models_v2 import Sheet, WeeklyReport
from app.tts.piper import audio_dir

log = logging.getLogger(__name__)


def delete_old_audio(settings: Settings, days: int, now: float | None = None) -> tuple[set[str], int]:
    """Removes audio files not changed for `days` days. Returns (names removed, bytes freed)."""
    folder = audio_dir(settings)
    if not folder.is_dir():
        return set(), 0
    cutoff = (time.time() if now is None else now) - days * 86400
    removed: set[str] = set()
    freed = 0
    for p in folder.rglob("*"):
        try:
            if p.is_symlink() or not p.is_file():
                continue
            st = p.stat()
            if st.st_mtime >= cutoff:
                continue
            p.unlink()
            removed.add(p.name)
            freed += st.st_size
        except OSError as exc:  # a file in use or already gone: leave it, carry on
            log.warning("could not remove %s: %s", p.name, exc)
    return removed, freed


def clear_audio_references(db: Session, settings: Settings, names: set[str]) -> int:
    """Sets audio_path to None (and the length where there is one) for rows whose file was removed."""
    if not names:
        return 0
    folder = audio_dir(settings)
    cleared = 0
    for model in (NewsItem, Sheet, WeeklyReport):
        rows = db.scalars(select(model).where(model.audio_path.is_not(None)))
        for row in rows:
            name = Path(row.audio_path).name
            if name in names and not (folder / name).exists():
                row.audio_path = None
                if hasattr(row, "audio_seconds"):
                    row.audio_seconds = None if model is NewsItem else 0
                cleared += 1
    db.commit()
    return cleared


def cleanup_audio(settings: Settings, db: Session, days: int) -> dict:
    names, freed = delete_old_audio(settings, days)
    cleared = clear_audio_references(db, settings, names)
    return {"deleted_files": len(names), "freed_bytes": freed, "cleared_references": cleared, "older_than_days": days}
