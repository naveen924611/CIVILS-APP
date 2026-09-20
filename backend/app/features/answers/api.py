"""Routes for answer writing: question generation and photo upload.

POST /answers/generate              {topic_id?, kind: "mains"|"short"|"essay"}  -> the new draft row
POST /answers/{id}/images?replace=  multipart, field "files" (one or more pictures) -> {answer_id, image_paths, count}
"""
import hashlib
import io
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.db.models_v2 import AnswerSubmission
from app.db.session import get_db
from app.files import data_path, uploads_dir
from app.services import Services, get_services

from . import generation

router = APIRouter(prefix="/answers", tags=["answers"])
_EXT = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}
MAX_PAGES = 12


class GenerateIn(BaseModel):
    topic_id: str | None = None
    kind: str = "mains"


@router.post("/generate")
def generate(body: GenerateIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    row = generation.create_draft(db, svc.gateway, body.topic_id, body.kind)
    if row is None:
        raise HTTPException(503, "The AI is busy right now. Please try again later.")
    return row.to_dict()


def _picture_ext(raw: bytes) -> str:
    try:
        from PIL import Image

        with Image.open(io.BytesIO(raw)) as im:
            im.verify()
            return _EXT.get(im.format or "", "")
    except Exception:
        return ""


@router.post("/{answer_id}/images")
async def upload_images(
    answer_id: str,
    files: list[UploadFile] = File(...),
    replace: bool = False,
    db: Session = Depends(get_db),
    svc: Services = Depends(get_services),
):
    row = db.get(AnswerSubmission, answer_id)
    if row is None or row.deleted:
        raise HTTPException(404, "No such answer yet. It will be found after the next sync.")
    if not files or len(files) > MAX_PAGES:
        raise HTTPException(400, f"Send between 1 and {MAX_PAGES} pictures.")
    limit = svc.settings.max_upload_mb * 1024 * 1024
    folder = uploads_dir(svc.settings) / "answers" / answer_id
    folder.mkdir(parents=True, exist_ok=True)
    paths: list[str] = [] if replace else list(row.image_paths or [])
    if replace:
        for old in list(row.image_paths or []):
            try:
                data_path(svc.settings, "uploads", old).unlink(missing_ok=True)
            except (OSError, ValueError):
                pass
    for f in files:
        raw = await f.read(limit + 1)
        if len(raw) > limit:
            raise HTTPException(413, "That picture is too large.")
        ext = _picture_ext(raw)
        if not ext:
            raise HTTPException(400, "One of the files is not a picture (JPEG, PNG or WebP).")
        name = hashlib.sha256(raw).hexdigest()[:16] + ext
        (folder / name).write_bytes(raw)
        rel = str(Path("answers") / answer_id / name)
        if rel not in paths:  # the same photo sent twice (a retry) is stored once
            paths.append(rel)
    row.image_paths = paths
    db.commit()
    return {"answer_id": answer_id, "image_paths": paths, "count": len(paths)}
