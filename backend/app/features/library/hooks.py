"""Reacting to rows the tablet pushes: text read on the device gets indexed; a deleted document loses its files."""
import logging
import threading

from sqlalchemy import delete, select

from app.db.models_v2 import Chunk, Document
from app.features.library import processing
from app.rag.index import invalidate_cache
from app.services import Services
from app.sync.hooks import on_push

log = logging.getLogger(__name__)

_dirty: set[str] = set()
_dirty_lock = threading.Lock()


def mark_dirty(document_id: str) -> None:
    with _dirty_lock:
        _dirty.add(document_id)


def take_dirty() -> list[str]:
    with _dirty_lock:
        ids = sorted(_dirty)
        _dirty.clear()
    return ids


@on_push("doc_pages")
def page_pushed(db, row, is_new) -> None:
    if getattr(row, "document_id", None) and (row.text or "").strip():
        mark_dirty(row.document_id)


@on_push("documents")
def document_pushed(db, row, is_new) -> None:
    """The owner removed a document on the tablet: its chunks and files are removed too."""
    if not row.deleted:
        return
    db.execute(delete(Chunk).where(Chunk.document_id == row.id))
    invalidate_cache()
    services = processing.get_services()
    if services is not None:
        processing.remove_files(services, row)


def reindex_document(services: Services, document_id: str) -> None:
    """Rebuilds the search index of one document after its text changed (in the background)."""
    with services.session_factory() as db:
        doc = db.get(Document, document_id)
        if doc is None or doc.deleted:
            return
        if doc.processing_status in ("needs_ocr", "processed"):
            processing.finalize(services, db, doc)
        else:
            processing.refresh_language(db, doc)
            processing.index_now(services, db, doc.id)
            db.commit()


def reindex_dirty(services: Services) -> int:
    """Scheduled every few minutes: documents whose text was pushed from the tablet."""
    ids = take_dirty()
    for document_id in ids:
        try:
            reindex_document(services, document_id)
        except Exception:
            log.exception("reindex of %s failed", document_id)
    return len(ids)


def documents_needing_index(services: Services) -> list[str]:
    """Documents with pages but no chunks (for example an indexing crash): used once at start-up."""
    with services.session_factory() as db:
        have = set(db.scalars(select(Chunk.document_id).distinct()))
        docs = db.scalars(select(Document).where(Document.deleted.is_(False), Document.processing_status == "processed"))
        return [d.id for d in docs if d.id not in have]
