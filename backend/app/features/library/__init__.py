"""Library (M3): documents the owner uploads or downloads, text extraction, OCR jobs and recommended material.

Routes are in `api.py` (prefix /library). `ocr.py` handles the job type `ocr_page`. Text is cut into chunks and
indexed for the tutor and notes by `processing.py` (through app.rag). Recommended material comes from
data/sources.yaml (`materials.py`).
"""
import logging

from app.services import Services

from . import hooks, materials, ocr, processing  # noqa: F401  (importing registers the job handler and sync hooks)
from .api import router

log = logging.getLogger(__name__)

__all__ = ["router", "setup"]


def setup(services: Services) -> None:
    processing.set_services(services)
    try:
        materials.seed(services)
    except Exception:
        log.exception("could not load the recommended material list")
    if services.settings.scheduler_enabled:
        for document_id in hooks.documents_needing_index(services):
            hooks.mark_dirty(document_id)
        services.scheduler.add_job(
            hooks.reindex_dirty, "interval", minutes=3, args=[services], id="library:reindex",
            replace_existing=True, coalesce=True, max_instances=1,
        )
        processing.resume_unfinished(services)
