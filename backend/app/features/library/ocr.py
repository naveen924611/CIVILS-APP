"""OCR of scanned pages and photos with the AI gateway's vision (English and Telugu). Job type `ocr_page`."""
import logging
import re
import threading
import time

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import DocPage, Document, Job
from app.features.library import extract, processing
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler
from app.llm import promptlib

log = logging.getLogger(__name__)

OCR_BATCH = 6  # pages per job run: short, so questions waiting in the queue are not held up
OCR_PAUSE = 2.0  # seconds between two AI calls (free-tier friendly)
CHAIN_DELAY = 15.0  # seconds before the next batch of the same document starts
NO_TEXT = "NO_TEXT"


def clean_ocr(text: str) -> str:
    text = text.strip()
    fence = re.match(r"^```[a-z]*\s*(.*?)\s*```$", text, re.S)
    if fence:
        text = fence.group(1).strip()
    if text.upper().startswith(NO_TEXT):
        return ""
    return extract.reflow(text)


def transcribe(gateway, image: bytes) -> str | None:
    """Text on one page picture. "" = nothing readable. None = the AI could not be reached."""
    system, user = promptlib.load("library_ocr")
    out = gateway.generate_text(
        feature="ocr", system=system, user=user, images=[("image/jpeg", image)], max_output_tokens=4096
    )
    if out is None:
        return None
    return clean_ocr(out)


def image_for_page(services, doc: Document, page: int) -> bytes | None:
    saved = processing.pages_dir(services, doc.id) / f"p{page:04d}.jpg"
    if saved.is_file():
        return saved.read_bytes()
    path = processing.file_of(services, doc)
    return extract.page_image(path, page) if path.is_file() else None


def _upsert(db: Session, document_id: str, page: int, text: str, source: str) -> None:
    row = db.scalar(select(DocPage).where(DocPage.document_id == document_id, DocPage.page == page, DocPage.deleted.is_(False)))
    if row is None:
        db.add(DocPage(document_id=document_id, page=page, text=text, source=source))
    else:
        row.text, row.source = text, source


def _blank_after(db: Session, document_id: str, last: int) -> list[int]:
    return [p for p in processing.blank_pages(db, document_id) if p > last]


def _budget_full(gateway) -> bool:
    guard = getattr(gateway, "guard", None)
    return bool(guard is not None and hasattr(guard, "level") and guard.level() >= 3)


def _last_attempt(ctx: JobContext) -> bool:
    tried = int((ctx.job.result_json or {}).get("_attempts", 0)) + 1
    return tried >= ctx.settings.job_max_attempts


# Budget name "note_merge" makes the job runner keep this job waiting (without using up its attempts)
# when the day's AI budget is nearly used, exactly like other deferrable work. The AI calls themselves
# are counted under the feature "ocr".
@job_handler("ocr_page", feature="note_merge")
def ocr_page(ctx: JobContext) -> dict:
    """payload {document_id, page, only_this_page?: bool, force?: bool, language?: "te"}.

    Reads page `page` and the following pages that still have no text, up to OCR_BATCH per run, then queues the
    next batch. `only_this_page` reads just that page (the tablet asks this for a Telugu page); `force` reads it
    even when it already has some text (a wrong on-device result)."""
    db, services = ctx.db, ctx.services
    doc = db.get(Document, str(ctx.payload.get("document_id", "")))
    if doc is None or doc.deleted:
        raise JobFailed("That document was removed.")
    start = max(1, int(ctx.payload.get("page") or 1))
    only = bool(ctx.payload.get("only_this_page"))
    force = bool(ctx.payload.get("force"))
    telugu_hint = ctx.payload.get("language") == "te"
    if only:
        todo = [start]
    else:
        blanks = processing.blank_pages(db, doc.id)
        todo = [p for p in blanks if p >= start]
    batch = todo[:OCR_BATCH]
    done_pages = 0
    chars = 0
    last = start - 1
    for i, page in enumerate(batch):
        if _budget_full(ctx.gateway):
            break
        existing = db.scalar(select(DocPage).where(DocPage.document_id == doc.id, DocPage.page == page, DocPage.deleted.is_(False)))
        if existing is not None and existing.text.strip() and not force:
            last = page
            continue  # the tablet or an earlier run already did this page: nothing is done twice
        processing.set_status(db, doc, "processing", f"Converting page {page} of {doc.pages or page}")
        image = image_for_page(ctx.services, doc, page)
        if image is None:
            last = page  # no picture on this page (blank or unreadable): skip it
            continue
        if i:
            time.sleep(float(services.extras.get("ocr_pause", OCR_PAUSE)))
        text = transcribe(ctx.gateway, image)
        if text is None:
            if _last_attempt(ctx):
                processing.set_status(db, doc, "needs_ocr", "Needs OCR")
            else:
                processing.set_status(db, doc, "waiting", processing.waiting_detail(doc, telugu_hint))
            raise AiUnavailable("The AI could not be reached to read this page.")
        if text:
            _upsert(db, doc.id, page, text, "gemini")
            chars += len(text)
            done_pages += 1
        last = page
        db.commit()
    if only:
        remaining: list[int] = []
    else:
        remaining = _blank_after(db, doc.id, last)
    if remaining:
        processing.set_status(db, doc, "processing", f"Converting page {remaining[0]} of {doc.pages}")
        job = Job(type="ocr_page", payload_json={"document_id": doc.id, "page": remaining[0]}, status="queued")
        db.add(job)
        db.commit()
        if services.settings.scheduler_enabled:
            timer = threading.Timer(CHAIN_DELAY, services.kick_jobs)
            timer.daemon = True
            timer.start()
    else:
        processing.finalize(services, db, doc)
    return {"document_id": doc.id, "page": start, "chars": chars, "pages_done": done_pages, "remaining": len(remaining)}
