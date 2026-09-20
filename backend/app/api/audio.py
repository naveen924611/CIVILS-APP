from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.auth.deps import current_user
from app.config import Settings, get_settings
from app.db.models import NewsItem
from app.db.session import get_db
from app.tts.piper import audio_dir

router = APIRouter(tags=["audio"])


@router.get("/audio/{item_id}.mp3")
def get_audio(
    item_id: str,
    _: str = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    """Streams one item's audio. Range requests work, so the player can seek."""
    item = db.get(NewsItem, item_id)
    if item is None or not item.audio_path:
        raise HTTPException(404, "No audio for this item")
    path = audio_dir(settings) / Path(item.audio_path).name  # name only: never a path from the database
    if not path.is_file():
        raise HTTPException(404, "Audio file is missing")
    return FileResponse(path, media_type="audio/mpeg")
