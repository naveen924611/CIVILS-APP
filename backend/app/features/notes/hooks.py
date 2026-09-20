"""Reactions to rows the tablet pushes (they run inside the push request, so they stay small)."""
import uuid
from datetime import datetime, timezone

from sqlalchemy import inspect as sa_inspect
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Card
from app.db.models_v2 import Note, NoteVersion, Topic
from app.features.notes import service
from app.features.notes.text import make_cloze
from app.sync.hooks import on_push


@on_push("topics")
def topic_pushed(db: Session, row: Topic, is_new: bool) -> None:
    """A topic the owner added on the tablet gets its (empty) note, because the tablet cannot create note rows."""
    if is_new and not row.deleted and (row.level or 0) >= 1:
        service.ensure_note(db, row.id)


@on_push("notes")
def note_pushed(db: Session, row: Note, is_new: bool) -> None:
    """The owner saved a note: keep the old text as a version, mark it owner-edited, and make it 'ready'."""
    if is_new and not row.topic_id:
        db.expunge(row)  # a note needs a topic; the tablet never creates notes (see ensure_note)
        return
    history = sa_inspect(row).attrs.content_md.history
    old = history.deleted[0] if history.deleted else None
    if old is not None and old != (row.content_md or ""):
        if old.strip():
            db.add(NoteVersion(note_id=row.id, version=row.version or 1, content_md=old))
            row.version = (row.version or 1) + 1
        row.owner_edited = True
    if (row.content_md or "").strip() and row.status in ("no_material", "failed", "generating"):
        row.status = "ready"


@on_push("highlights")
def highlight_pushed(db: Session, row, is_new: bool) -> None:
    """A 'must remember' or 'card' highlight tied to a topic becomes a flashcard (once)."""
    if row.deleted or row.kind not in ("must", "card") or not row.topic_id or not (row.text or "").strip():
        return
    if db.scalar(select(Card.id).where(Card.source_type == "highlight", Card.source_id == row.id)) is not None:
        return
    topic = db.get(Topic, row.topic_id)
    if topic is None:
        return
    front, back = make_cloze(row.text, topic.title)
    db.add(Card(id=str(uuid.uuid4()), front=front, back=back, topic_id=topic.id, source_type="highlight", source_id=row.id,
                group=service.subject_name(db, topic), fsrs_state_json=None, due_at=datetime.now(timezone.utc)))
