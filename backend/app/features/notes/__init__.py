"""Notes (spec 6.6, 7.3): one note per topic, written from the owner's own material, versioned, never overwriting his edits.

The tablet reads the `notes` table through sync and edits `content_md` (that marks a note as owner-edited). AI work is queued as
`note_merge` jobs (see jobs.py). Routes (all behind login):
  POST /notes/ensure/{topic_id}             the note row of a topic (created empty when missing)
  POST /notes/generate                      {topic_id, mode?, text?, mcq_count?} -> {job_id}  (same as queueing a note_merge job)
  POST /notes/news/refresh                  match recent news to topics now -> {items, matched, notes}
  GET  /notes/{note_id}/versions            earlier versions of a note (newest first)
  GET  /notes/{note_id}/versions/{version}  the text of one earlier version
  POST /notes/{note_id}/restore/{version}   put an earlier version back (the current text is kept as a version)
"""
import logging

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Job, Note, NoteVersion, Topic
from app.db.session import get_db
from app.db.util import iso
from app.features.notes import news, service
from app.services import Services, get_services

log = logging.getLogger(__name__)
router = APIRouter(prefix="/notes", tags=["notes"])


class GenerateIn(BaseModel):
    topic_id: str
    mode: str = Field(default="generate", pattern="^(merge|generate|fix)$")
    text: str | None = None
    mcq_count: int | None = Field(default=None, ge=0, le=8)


def _note(db: Session, note_id: str) -> Note:
    note = db.get(Note, note_id)
    if note is None or note.deleted:
        raise HTTPException(404, "That note was not found.")
    return note


@router.post("/ensure/{topic_id}")
def ensure(topic_id: str, db: Session = Depends(get_db)):
    topic = db.get(Topic, topic_id)
    if topic is None or topic.deleted:
        raise HTTPException(404, "That topic was not found.")
    note = service.ensure_note(db, topic_id)
    db.commit()
    return note.to_dict()


@router.post("/generate")
def generate(body: GenerateIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    topic = db.get(Topic, body.topic_id)
    if topic is None or topic.deleted:
        raise HTTPException(404, "That topic was not found.")
    payload = {"topic_id": body.topic_id, "mode": body.mode, "text": body.text or "", "mcq_count": body.mcq_count}
    job = Job(type="note_merge", status="queued", payload_json=payload)
    db.add(job)
    db.commit()
    svc.kick_jobs()
    return {"job_id": job.id}


@router.post("/news/refresh")
def refresh_news(db: Session = Depends(get_db)):
    return news.refresh(db)


@router.get("/{note_id}/versions")
def versions(note_id: str, db: Session = Depends(get_db)):
    note = _note(db, note_id)
    rows = db.scalars(select(NoteVersion).where(NoteVersion.note_id == note.id).order_by(NoteVersion.version.desc()))
    return {"current": note.version, "versions": [
        {"version": v.version, "created_at": iso(v.created_at), "chars": len(v.content_md or "")} for v in rows
    ]}


def _version(db: Session, note: Note, version: int) -> NoteVersion:
    row = db.scalars(
        select(NoteVersion).where(NoteVersion.note_id == note.id, NoteVersion.version == version)
        .order_by(NoteVersion.created_at.desc())
    ).first()
    if row is None:
        raise HTTPException(404, "That earlier version was not found.")
    return row


@router.get("/{note_id}/versions/{version}")
def one_version(note_id: str, version: int, db: Session = Depends(get_db)):
    row = _version(db, _note(db, note_id), version)
    return {"version": row.version, "created_at": iso(row.created_at), "content_md": row.content_md}


@router.post("/{note_id}/restore/{version}")
def restore(note_id: str, version: int, db: Session = Depends(get_db)):
    note = _note(db, note_id)
    row = _version(db, note, version)
    service.set_content(db, note, row.content_md)
    note.owner_edited = True
    if (note.content_md or "").strip():
        note.status = "ready"
    db.commit()
    return note.to_dict()


def _nightly(services: Services) -> None:
    try:
        with services.session_factory() as db:
            news.refresh(db)
    except Exception:  # never let a scheduled run stop the server
        log.exception("in-the-news refresh failed")


def setup(services: Services) -> None:
    if services.settings.scheduler_enabled:
        services.scheduler.add_job(_nightly, "interval", hours=3, id="notes:news", replace_existing=True, args=[services])


from . import hooks, jobs  # noqa: E402,F401  (importing registers the job handler and the sync hooks)
