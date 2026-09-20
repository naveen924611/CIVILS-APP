"""Turns an uploaded file into searchable text: extract text per page, queue OCR for scanned pages, index.

Statuses (Document.processing_status) and what the owner sees (Document.status_detail):
    uploaded / processing   "Reading the file", "Converting page 3 of 12"
    downloading             "Downloading" (recommended material)
    waiting                 "Waiting for internet (Telugu page)"  (AI not reachable, retried automatically)
    needs_ocr               "Needs OCR"  (no text could be found or read)
    processed               "Processed · searchable"
    failed                  short plain reason
"""
import logging
import shutil
import threading
from collections.abc import Callable
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import DocPage, Document, Job
from app.features.library import extract
from app.files import uploads_dir
from app.rag.index import index_document
from app.services import Services

log = logging.getLogger(__name__)

_services: Services | None = None


def set_services(services: Services | None) -> None:
    global _services
    _services = services


def get_services() -> Services | None:
    return _services


def spawn(fn: Callable, *args) -> None:
    """Runs slow work in the background. Tests replace this with a direct call."""
    threading.Thread(target=fn, args=args, daemon=True, name="library").start()


def file_of(services: Services, doc: Document) -> Path:
    return uploads_dir(services.settings) / doc.file_path


def pages_dir(services: Services, document_id: str) -> Path:
    return uploads_dir(services.settings) / f"{document_id}_pages"


def set_status(db: Session, doc: Document, status: str, detail: str = "") -> None:
    doc.processing_status = status
    doc.status_detail = detail[:300]
    db.commit()


def waiting_detail(doc: Document, telugu: bool = False) -> str:
    if telugu or doc.language in ("te", "mixed"):
        return "Waiting for internet (Telugu page)"
    return "Waiting for internet"


def kick(services: Services) -> None:
    """Starts the job runner (only when the app runs its own scheduler; tests call the runner themselves)."""
    if services.settings.scheduler_enabled:
        services.kick_jobs()


def queue_ocr(db: Session, services: Services, document_id: str, page: int, **extra) -> str | None:
    """One `ocr_page` job per document at a time. Returns the job id (None when one is already waiting)."""
    for job in db.scalars(select(Job).where(Job.type == "ocr_page", Job.status.in_(("queued", "running")), Job.deleted.is_(False))):
        if (job.payload_json or {}).get("document_id") == document_id:
            return None
    job = Job(type="ocr_page", payload_json={"document_id": document_id, "page": page, **extra}, status="queued")
    db.add(job)
    db.commit()
    kick(services)
    return job.id


def blank_pages(db: Session, document_id: str) -> list[int]:
    rows = db.scalars(select(DocPage).where(DocPage.document_id == document_id, DocPage.deleted.is_(False)).order_by(DocPage.page))
    return [r.page for r in rows if not r.text.strip()]


def text_page_count(db: Session, document_id: str) -> int:
    rows = db.scalars(select(DocPage).where(DocPage.document_id == document_id, DocPage.deleted.is_(False)))
    return sum(1 for r in rows if r.text.strip())


def refresh_language(db: Session, doc: Document) -> None:
    texts = [
        r.text for r in db.scalars(select(DocPage).where(DocPage.document_id == doc.id, DocPage.deleted.is_(False)))
        if r.text.strip()
    ]
    te = sum(1 for t in texts if extract.is_telugu(t))
    doc.language = "en" if te == 0 else ("te" if te * 2 > len(texts) else "mixed")


def index_now(services: Services, db: Session, document_id: str) -> int:
    try:
        return index_document(db, services.gateway, document_id)
    except Exception:  # indexing problems must never lose the document
        log.exception("indexing %s failed", document_id)
        db.rollback()
        return 0


def finalize(services: Services, db: Session, doc: Document) -> None:
    """All pages that could be read are in: index, and tell the owner."""
    refresh_language(db, doc)
    have = text_page_count(db, doc.id)
    if have == 0:
        set_status(db, doc, "needs_ocr", "Needs OCR")
        return
    index_now(services, db, doc.id)
    set_status(db, doc, "processed", "Processed · searchable")


def process_document(services: Services, document_id: str) -> None:
    """Extracts text from the saved file. Safe to run again (pages that already have text are kept)."""
    try:
        with services.session_factory() as db:
            _process(services, db, document_id)
    except Exception:
        log.exception("processing %s crashed", document_id)
        with services.session_factory() as db:
            doc = db.get(Document, document_id)
            if doc is not None and doc.processing_status in ("uploaded", "processing"):
                set_status(db, doc, "failed", "Something went wrong reading this file. Try uploading it again.")


def _process(services: Services, db: Session, document_id: str) -> None:
    doc = db.get(Document, document_id)
    if doc is None or doc.deleted:
        return
    path = file_of(services, doc)
    if not path.is_file():
        set_status(db, doc, "failed", "The file is missing on the server. Please upload it again.")
        return
    set_status(db, doc, "processing", "Reading the file")

    def progress(done: int, total: int) -> None:
        doc.status_detail = f"Converting page {done} of {total}"
        db.commit()

    try:
        pages = extract.extract_pages(path, progress)
    except extract.ExtractError as exc:
        set_status(db, doc, "failed", str(exc))
        return
    existing = {p.page: p for p in db.scalars(select(DocPage).where(DocPage.document_id == doc.id, DocPage.deleted.is_(False)))}
    todo_ocr: list[int] = []
    for pt in pages:
        row = existing.get(pt.page)
        if row is None:
            db.add(DocPage(document_id=doc.id, page=pt.page, text=pt.text, source="text"))
            current = pt.text
        elif not row.text.strip() and pt.text:
            row.text, row.source = pt.text, "text"
            current = pt.text
        else:
            current = row.text
        if not current.strip() and pt.needs_ocr:
            todo_ocr.append(pt.page)
    doc.pages = len(pages)
    refresh_language_from_pages(doc, [pt.text for pt in pages])
    db.commit()
    if todo_ocr:
        if text_page_count(db, doc.id):
            index_now(services, db, doc.id)  # the readable pages are searchable while the scans are converted
        set_status(db, doc, "processing", f"Converting page {todo_ocr[0]} of {doc.pages}")
        queue_ocr(db, services, doc.id, todo_ocr[0])
    else:
        finalize(services, db, doc)


def refresh_language_from_pages(doc: Document, texts: list[str]) -> None:
    texts = [t for t in texts if t.strip()]
    te = sum(1 for t in texts if extract.is_telugu(t))
    doc.language = "en" if te == 0 else ("te" if te * 2 > len(texts) else "mixed")


def remove_files(services: Services, doc: Document) -> None:
    try:
        if doc.file_path:
            file_of(services, doc).unlink(missing_ok=True)
        shutil.rmtree(pages_dir(services, doc.id), ignore_errors=True)
    except OSError:
        log.warning("could not remove files of %s", doc.id)


def resume_unfinished(services: Services) -> int:
    """After a restart: carry on with files that were being read; a broken download is marked so it can be retried."""
    n = 0
    with services.session_factory() as db:
        for doc in list(db.scalars(select(Document).where(Document.deleted.is_(False), Document.processing_status.in_(("uploaded", "processing", "downloading"))))):
            if doc.processing_status == "downloading":
                set_status(db, doc, "failed", "The download was interrupted. Tap Download to try again.")
            elif doc.file_path:
                spawn(process_document, services, doc.id)
                n += 1
    return n
