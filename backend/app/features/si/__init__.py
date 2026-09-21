"""SI (Civil) goal: the AP SLPRB Sub-Inspector exam (a third goal beside UPSC and APPSC Group-I).

Routes (login required):
  GET /si/spec     the verified facts of the exam: data/exam-specs/slprb_si_2026.json (404 when the file is missing)

Plan hooks (plan.py): while an SI exam is live, every day gets a physical-training block ("phys-<date>", not counted in the
study hours) and, except on Sunday, a 20-question aptitude drill ("drill-<date>", test kind "aptitude").
The exam tag "SI", the whole-word exam-name matching and the drill generator live in features/syllabus, features/examnames.py
and features/tests/aptitude.py.
"""
import json
import logging
import os
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException

from app.config import Settings
from app.services import Services, get_services

from . import plan  # noqa: F401  (importing registers the plan hooks)

log = logging.getLogger(__name__)
router = APIRouter(prefix="/si", tags=["si"])
SPEC_FILE = "slprb_si_2026.json"


def specs_dir(settings: Settings) -> Path:
    """data/exam-specs next to feeds.yaml (the server container mounts ./data at /app/config); EXAM_SPECS_DIR overrides;
    falls back to the repo's data folder."""
    override = os.environ.get("EXAM_SPECS_DIR")
    if override:
        return Path(override)
    beside = Path(settings.feeds_file).resolve().parent / "exam-specs"
    if beside.is_dir():
        return beside
    return Path(__file__).resolve().parents[4] / "data" / "exam-specs"


@router.get("/spec")
def spec(svc: Services = Depends(get_services)):
    path = specs_dir(svc.settings) / SPEC_FILE
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise HTTPException(404, "The SI exam facts file is not on the server.") from None
    except (OSError, ValueError) as exc:
        log.warning("SI spec unreadable: %s", exc)
        raise HTTPException(404, "The SI exam facts file could not be read.") from None
