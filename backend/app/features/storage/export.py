"""Export everything the owner has made into one zip ("Export my data", spec 6.8).

Inside the zip:
  README.txt               what is in here
  tables/<name>.json       every synced table (rows that are not deleted)
  notes/<subject> - <topic>.md   one markdown file per topic note
  documents.json           list of documents (titles and details, not the files themselves)
  settings.json            the owner's saved settings
"""
import json
import re
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Setting
from app.db.models_v2 import Document, Note, Topic
from app.sync.registry import sync_tables

BATCH = 500
README = (
    "Civils Companion - your data\n"
    "tables/      every table as JSON (topics, cards, reviews, highlights, focus sessions, ...)\n"
    "notes/       your topic notes as markdown files\n"
    "documents.json  the list of your documents (the PDFs and photos themselves are not in this file)\n"
    "settings.json   your saved settings\n"
)


def _dump(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, default=str).encode("utf-8")


def safe_file_name(text: str, default: str = "note") -> str:
    text = re.sub(r"[^\w .,()&'-]+", "_", text, flags=re.UNICODE).strip(" ._")
    return (text or default)[:80]


def _write_table(zf: zipfile.ZipFile, db: Session, name: str, model) -> int:
    """Streams one table as a JSON array, in batches, so a big table never sits in memory."""
    stmt = select(model)
    if hasattr(model, "deleted"):
        stmt = stmt.where(model.deleted.is_(False))
    count = 0
    with zf.open(f"tables/{name}.json", "w", force_zip64=True) as out:
        out.write(b"[")
        for row in db.scalars(stmt.execution_options(yield_per=BATCH)):
            if count:
                out.write(b",\n")
            out.write(_dump(row.to_dict()))
            count += 1
        out.write(b"]")
    return count


def _topic_path(topics: dict[str, Topic], topic_id: str) -> tuple[str, str]:
    """(subject or paper name, topic title) for a file name."""
    topic = topics.get(topic_id)
    if topic is None:
        return "Other", topic_id[:8]
    parent = topics.get(topic.parent_id) if topic.parent_id else None
    return (parent.title if parent else topic.paper or "Other"), topic.title


def _write_notes(zf: zipfile.ZipFile, db: Session) -> int:
    topics = {t.id: t for t in db.scalars(select(Topic))}
    used: set[str] = set()
    count = 0
    for note in db.scalars(select(Note).where(Note.deleted.is_(False))):
        if not (note.content_md or "").strip():
            continue
        subject, title = _topic_path(topics, note.topic_id)
        base = f"notes/{safe_file_name(subject, 'Other')} - {safe_file_name(title)}"
        name, n = f"{base}.md", 2
        while name in used:
            name, n = f"{base} ({n}).md", n + 1
        used.add(name)
        zf.writestr(name, f"# {title}\n\n{note.content_md}\n")
        count += 1
    return count


def _document_list(db: Session) -> list[dict]:
    rows = db.scalars(select(Document).where(Document.deleted.is_(False)).order_by(Document.title))
    return [
        {"id": d.id, "title": d.title, "type": d.type, "pages": d.pages, "size_bytes": d.size_bytes,
         "language": d.language, "source_url": d.source_url, "file": Path(d.file_path).name if d.file_path else ""}
        for d in rows
    ]


def build_export(db: Session, target: Path) -> dict:
    """Writes the zip to `target`. Returns counts per part."""
    counts: dict[str, int] = {}
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("README.txt", README + f"Made on {datetime.now(timezone.utc).date().isoformat()}\n")
        for name, model in sync_tables().items():
            counts[name] = _write_table(zf, db, name, model)
        counts["notes_markdown"] = _write_notes(zf, db)
        docs = _document_list(db)
        zf.writestr("documents.json", _dump(docs))
        counts["documents"] = len(docs)
        settings = {r.key: r.value_json for r in db.scalars(select(Setting))}
        zf.writestr("settings.json", _dump(settings))
    return counts
