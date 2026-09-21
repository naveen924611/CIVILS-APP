"""Monthly current-affairs compilation (M12).

On the 1st of each month at 04:30 India time (scheduler on) the month before is compiled: briefs' news, notes, mistakes
-> markdown (synced) and a PDF (GET /compilation/{id}/pdf). Job: `compilation_build` (jobs.py).
"""
import logging
from datetime import datetime
from zoneinfo import ZoneInfo

from app.services import Services

from . import jobs  # noqa: F401  (importing registers the job handler)
from .api import router  # noqa: F401

log = logging.getLogger(__name__)


def run_monthly(services: Services) -> str | None:
    """Builds last month's compilation (skipped when nothing was collected). Returns the month or None."""
    from . import build

    month = build.previous_month(datetime.now(ZoneInfo(services.settings.timezone)).date())
    try:
        with services.session_factory() as db:
            row = build.build_month(db, services.settings, month, allow_empty=False)
    except Exception as exc:
        log.warning("monthly compilation failed: %s", exc)
        return None
    if row is None:
        return None
    services.push("Monthly digest ready", row.title, {"kind": "compilation", "id": row.id})
    return month


def setup(services: Services) -> None:
    if not services.settings.scheduler_enabled:
        return
    services.scheduler.add_job(
        run_monthly, "cron", day=1, hour=4, minute=30, id="compilation:monthly", replace_existing=True,
        args=[services], misfire_grace_time=24 * 3600,
    )
