"""Feature modules (M3 onwards). Each is a package `app/features/<name>/` that may define:

    router  - a FastAPI APIRouter (routes are added to the app, all behind login)
    setup(services) - called once at start-up (register scheduled jobs, warm caches, ...)

Importing a module also registers its job handlers (`@job_handler`) and sync hooks (`@on_push`).
A name in FEATURES with no package yet is simply skipped.
"""
import importlib
import logging
from types import ModuleType

log = logging.getLogger(__name__)

FEATURES = [
    "library",      # M3  documents, upload, text extraction, OCR jobs, recommended materials, reading position
    "syllabus",     # M4  syllabus import and approval, topic tree
    "notes",        # M4  notes per topic, merge job, highlights, capture
    "revision",     # M5  cards, FSRS, revision rules and order
    "planner",      # M5  daily plan, catch-up, today
    "tutor",        # M6  Ask: tutor_question jobs, answers with sources
    "tests",        # M8  MCQs, mock tests, PYQs, mistake book
    "answers",      # M9  explain-back and answer-writing feedback
    "reports",      # M10 revision sheets, weekly report, last-month mode
    "videos",       # M11 YouTube search and video notes
    "telugu",       # M12 Telugu module
    "compilation",  # M12 monthly compilation
    "storage",      # M7  storage screen, export, nightly backup
]

_loaded: list[ModuleType] | None = None


def load_features() -> list[ModuleType]:
    global _loaded
    if _loaded is not None:
        return _loaded
    mods: list[ModuleType] = []
    for name in FEATURES:
        full = f"app.features.{name}"
        try:
            mods.append(importlib.import_module(full))
        except ModuleNotFoundError as exc:
            if exc.name != full:  # a missing dependency INSIDE the feature is a real error
                raise
            log.debug("feature %s not built yet", name)
    _loaded = mods
    return mods
