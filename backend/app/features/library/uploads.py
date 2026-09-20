"""Saving what the owner sends: PDFs, photos of pages, and one-by-one camera scans."""
import logging
from datetime import datetime
from pathlib import Path
from typing import BinaryIO
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import DocPage, Document
from app.features.library import extract, processing
from app.files import safe_name, uploads_dir
from app.services import Services

log = logging.getLogger(__name__)

CHUNK = 1024 * 1024


class UploadError(Exception):
    """A plain-language reason the file was refused (shown to the owner)."""


def title_from_name(name: str, default: str) -> str:
    stem = Path(safe_name(name, default)).stem.replace("_", " ").strip()
    return (stem or default)[:200]


def save_stream(services: Services, stream: BinaryIO, dest_name: str) -> int:
    """Copies an upload to DATA_DIR/uploads/<dest_name>. Returns its size. Refuses files above max_upload_mb."""
    limit = services.settings.max_upload_mb * 1024 * 1024
    path = uploads_dir(services.settings) / dest_name
    size = 0
    with open(path, "wb") as out:
        while True:
            block = stream.read(CHUNK)
            if not block:
                break
            size += len(block)
            if size > limit:
                out.close()
                path.unlink(missing_ok=True)
                raise UploadError(f"This file is bigger than {services.settings.max_upload_mb} MB. Please send a smaller one.")
            out.write(block)
    return size


def new_pdf(services: Services, db: Session, stream: BinaryIO, filename: str) -> Document:
    """Saves a PDF and creates its document row (text is read afterwards by `processing.process_document`)."""
    doc = Document(title=title_from_name(filename, "PDF"), type="pdf", processing_status="uploaded", status_detail="Reading the file")
    db.add(doc)
    db.flush()
    name = f"{doc.id}.pdf"
    try:
        doc.size_bytes = save_stream(services, stream, name)
        path = uploads_dir(services.settings) / name
        with open(path, "rb") as fh:
            if fh.read(5) != b"%PDF-":
                raise UploadError("This does not look like a PDF file.")
        doc.pages = extract.page_count(path)
    except (UploadError, extract.ExtractError) as exc:
        (uploads_dir(services.settings) / name).unlink(missing_ok=True)
        db.rollback()
        raise UploadError(str(exc)) from exc
    doc.file_path = name
    db.commit()
    return doc


def _next_page(db: Session, document_id: str) -> int:
    pages = [p.page for p in db.scalars(select(DocPage).where(DocPage.document_id == document_id, DocPage.deleted.is_(False)))]
    return max(pages, default=0) + 1


def new_image_document(db: Session, title: str, kind: str) -> Document:
    doc = Document(title=title[:200], type=kind, processing_status="processing", status_detail="Reading the picture", file_path="")
    db.add(doc)
    db.commit()
    return doc


def add_image_page(services: Services, db: Session, doc: Document, data: bytes, text: str = "", source: str = "text") -> int:
    """Adds one picture as the next page. `text` is what the tablet already read on the device ("" = not read)."""
    jpeg, _w, _h = extract.normalize_image(data)
    page = _next_page(db, doc.id)
    folder = processing.pages_dir(services, doc.id)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"p{page:04d}.jpg").write_bytes(jpeg)
    text = extract.reflow(text) if text.strip() else ""
    db.add(DocPage(document_id=doc.id, page=page, text=text, source=source if text else "text"))
    doc.pages = page
    doc.size_bytes = (doc.size_bytes or 0) + len(jpeg)
    db.commit()
    return page


def finish_image_document(services: Services, db: Session, doc: Document) -> None:
    """After adding pictures: pages without text are queued for AI reading, the rest is indexed."""
    blanks = processing.blank_pages(db, doc.id)
    processing.refresh_language(db, doc)
    if blanks:
        if processing.text_page_count(db, doc.id):
            processing.index_now(services, db, doc.id)
        processing.set_status(db, doc, "processing", f"Converting page {blanks[0]} of {doc.pages}")
        processing.queue_ocr(db, services, doc.id, blanks[0])
    else:
        processing.finalize(services, db, doc)


def scan_title() -> str:
    return "Scan " + datetime.now(ZoneInfo("Asia/Kolkata")).strftime("%d %b %Y, %H:%M")
