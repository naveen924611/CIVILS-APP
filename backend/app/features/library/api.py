"""Library HTTP routes (login is required by the app for every feature router).

POST /library/upload                    multipart `files` (PDFs or pictures) [+ `title`]  -> {documents:[...]}
POST /library/scans                     multipart `image` [+ `document_id`, `text`, `title`, `language`] -> {document_id, page}
GET  /library/documents/{id}/file       the original PDF
GET  /library/documents/{id}/pages/{n}/image   the page picture (JPEG)
POST /library/documents/{id}/retry      read the file again / queue the missing pages for OCR
POST /library/materials/{key}/download  download a recommended PDF
GET  /library/search?q=&document_id=    search the owner's own material
"""
import logging

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import DocPage, Document
from app.db.session import get_db
from app.features.library import extract, hooks, materials, processing, uploads
from app.rag.search import search
from app.services import Services, get_services

log = logging.getLogger(__name__)
router = APIRouter(prefix="/library", tags=["library"])


def brief(doc: Document) -> dict:
    return {
        "id": doc.id, "title": doc.title, "type": doc.type, "pages": doc.pages,
        "processing_status": doc.processing_status, "status_detail": doc.status_detail,
    }


def _document(db: Session, document_id: str) -> Document:
    doc = db.get(Document, document_id)
    if doc is None or doc.deleted:
        raise HTTPException(404, "That document was not found.")
    return doc


def _looks_like_pdf(f: UploadFile) -> bool:
    name = (f.filename or "").lower()
    return name.endswith(".pdf") or f.content_type == "application/pdf"


def _is_picture(f: UploadFile) -> bool:
    name = (f.filename or "").lower()
    return (f.content_type or "").startswith("image/") or name.endswith((".jpg", ".jpeg", ".png", ".webp", ".heic"))


@router.post("/upload")
def upload(
    files: list[UploadFile] = File(...),
    title: str = Form(""),
    db: Session = Depends(get_db),
    svc: Services = Depends(get_services),
):
    pdfs = [f for f in files if _looks_like_pdf(f)]
    pictures = [f for f in files if not _looks_like_pdf(f) and _is_picture(f)]
    if not pdfs and not pictures:
        raise HTTPException(400, "Please choose a PDF or pictures (JPG or PNG).")
    made: list[Document] = []
    problems: list[str] = []
    for f in pdfs:
        try:
            doc = uploads.new_pdf(svc, db, f.file, f.filename or "PDF")
        except uploads.UploadError as exc:
            problems.append(f"{f.filename}: {exc}")
            continue
        if title.strip() and len(pdfs) == 1 and not pictures:
            doc.title = title.strip()[:300]
            db.commit()
        made.append(doc)
        processing.spawn(processing.process_document, svc, doc.id)
    if pictures:
        doc = uploads.new_image_document(db, title.strip() or uploads.scan_title().replace("Scan", "Photos"), "image")
        added = 0
        for f in pictures:
            try:
                uploads.add_image_page(svc, db, doc, f.file.read())
                added += 1
            except extract.ExtractError as exc:
                problems.append(f"{f.filename}: {exc}")
        if added == 0:
            doc.deleted = True
            db.commit()
        else:
            uploads.finish_image_document(svc, db, doc)
            made.append(doc)
    if not made:
        raise HTTPException(400, " ".join(problems) or "Nothing could be added.")
    return {"documents": [brief(d) for d in made], "problems": problems}


@router.post("/scans")
def add_scan(
    image: UploadFile = File(...),
    document_id: str = Form(""),
    text: str = Form(""),
    title: str = Form(""),
    language: str = Form("en"),
    db: Session = Depends(get_db),
    svc: Services = Depends(get_services),
):
    """One camera photo. Joins the scan document `document_id` (or starts a new one). `text` is what the tablet
    already read on the device; without it the server reads the page with the AI."""
    if document_id:
        doc = _document(db, document_id)
        if doc.type not in ("scan", "image"):
            raise HTTPException(400, "Pages can only be added to a scan.")
    else:
        doc = uploads.new_image_document(db, title.strip() or uploads.scan_title(), "scan")
    try:
        page = uploads.add_image_page(svc, db, doc, image.file.read(), text=text, source="device_ocr")
    except extract.ExtractError as exc:
        raise HTTPException(400, str(exc)) from exc
    if text.strip():
        processing.refresh_language(db, doc)
        if not processing.blank_pages(db, doc.id):
            processing.set_status(db, doc, "processed", "Processed · searchable")
        hooks.mark_dirty(doc.id)
        processing.spawn(hooks.reindex_document, svc, doc.id)
    else:
        uploads.finish_image_document(svc, db, doc)
    return {"document_id": doc.id, "page": page, "language": language}


@router.get("/documents/{document_id}/file")
def document_file(document_id: str, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    doc = _document(db, document_id)
    path = processing.file_of(svc, doc) if doc.file_path else None
    if path is None or not path.is_file():
        raise HTTPException(404, "There is no PDF for this document.")
    return FileResponse(path, media_type="application/pdf", filename=f"{doc.id}.pdf")


@router.get("/documents/{document_id}/pages/{page}/image")
def page_image(document_id: str, page: int, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    doc = _document(db, document_id)
    saved = processing.pages_dir(svc, doc.id) / f"p{page:04d}.jpg"
    if saved.is_file():
        return FileResponse(saved, media_type="image/jpeg")
    path = processing.file_of(svc, doc) if doc.file_path else None
    data = extract.page_image(path, page) if path is not None and path.is_file() else None
    if data is None:
        raise HTTPException(404, "This page has no picture.")
    return Response(content=data, media_type="image/jpeg")


@router.post("/documents/{document_id}/retry")
def retry(document_id: str, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    doc = _document(db, document_id)
    if doc.processing_status in ("processed", "downloading"):
        return brief(doc)
    if doc.type == "recommended" and not doc.file_path:
        processing.set_status(db, doc, "downloading", "Downloading")
        processing.spawn(materials.fetch_material, svc, doc.id)
    elif doc.file_path:
        processing.set_status(db, doc, "processing", "Reading the file")
        processing.spawn(processing.process_document, svc, doc.id)
    else:
        blanks = processing.blank_pages(db, doc.id)
        if not blanks:
            processing.finalize(svc, db, doc)
        else:
            processing.set_status(db, doc, "processing", f"Converting page {blanks[0]} of {doc.pages}")
            processing.queue_ocr(db, svc, doc.id, blanks[0])
    db.refresh(doc)
    return brief(doc)


@router.post("/materials/{key}/download")
def download_material(key: str, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    try:
        doc = materials.start_download(svc, db, key)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    return brief(doc)


@router.get("/search")
def find(q: str, document_id: str = "", k: int = 8, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    hits = search(db, svc.gateway, q, document_ids=[document_id] if document_id else None, k=max(1, min(k, 20)))
    return {"hits": [
        {"document_id": h.document_id, "title": h.document_title, "page": h.page, "text": h.text[:600]} for h in hits
    ]}


@router.get("/documents/{document_id}/text")
def document_text(document_id: str, db: Session = Depends(get_db)):
    """All page texts (a fallback for the tablet when sync has not brought the pages yet)."""
    doc = _document(db, document_id)
    rows = db.scalars(select(DocPage).where(DocPage.document_id == doc.id, DocPage.deleted.is_(False)).order_by(DocPage.page))
    return {"id": doc.id, "title": doc.title, "pages": [{"page": r.page, "text": r.text, "source": r.source} for r in rows]}

