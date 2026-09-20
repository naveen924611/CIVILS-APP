"""Builds the search index (chunks + embeddings) for a document from its DocPage rows."""
import logging
from collections.abc import Callable

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.db.models_v2 import Chunk, DocPage, Document
from app.rag.chunker import chunk_pages

log = logging.getLogger(__name__)


def index_document(db: Session, gateway, document_id: str, topic_ids: list[str] | None = None) -> int:
    """Replaces the document's chunks. Returns how many were made. Embeddings are skipped (word search only)
    when the AI is unavailable; `reembed_missing` can add them later."""
    pages = [
        (p.page, p.text)
        for p in db.scalars(
            select(DocPage).where(DocPage.document_id == document_id, DocPage.deleted.is_(False)).order_by(DocPage.page)
        )
    ]
    pieces = chunk_pages(pages)
    db.execute(delete(Chunk).where(Chunk.document_id == document_id))
    vectors = gateway.embed_texts([t for _, _, t in pieces]) if pieces and gateway is not None else None
    for i, (page, idx, text) in enumerate(pieces):
        db.add(Chunk(document_id=document_id, page=page, idx=idx, text=text, topic_ids=topic_ids or [],
                     embedding=vectors[i] if vectors else None))
    doc = db.get(Document, document_id)
    if doc is not None:
        doc.pages = max(doc.pages, max((p for p, _ in pages), default=0))
    db.commit()
    invalidate_cache()
    return len(pieces)


def reembed_missing(db: Session, gateway, limit: int = 300) -> int:
    rows = list(db.scalars(select(Chunk).where(Chunk.embedding.is_(None)).limit(limit)))
    if not rows:
        return 0
    vectors = gateway.embed_texts([r.text for r in rows])
    if not vectors:
        return 0
    for r, v in zip(rows, vectors, strict=True):
        r.embedding = v
    db.commit()
    invalidate_cache()
    return len(rows)


_invalidators: list[Callable[[], None]] = []


def invalidate_cache() -> None:
    for fn in _invalidators:
        fn()
