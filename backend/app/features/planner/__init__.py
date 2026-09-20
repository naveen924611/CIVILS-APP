"""Planner (M5, spec 7.6): the daily plan from study hours, exam dates and priority, revision due, Telugu minutes, the
optional library day and the owner's adjustments. Rules live in `engine.py` (pure), database work in `service.py`.

Routes: GET /planner/plan?date=, GET /planner/week?start=, POST /planner/regenerate, POST /planner/complete, GET /planner/exams.
Other features add blocks with `app.features.plan_hooks.plan_postprocessor`.  A new plan is made every night (00:10 India time).
"""
import logging

from app.services import Services

from .api import router  # noqa: F401

log = logging.getLogger(__name__)


def nightly(services: Services) -> None:
    from . import service

    try:
        with services.session_factory() as db:
            rows = service.plan_range(db, services.settings, service.today_ist(), 7)
            db.commit()
            log.info("planner nightly: %s days planned", len(rows))
    except Exception:  # never let a nightly job crash the scheduler
        log.exception("planner nightly failed")


def setup(services: Services) -> None:
    if not services.settings.scheduler_enabled:
        return
    services.scheduler.add_job(
        nightly, "cron", hour=0, minute=10, id="planner:nightly", replace_existing=True, args=[services],
        misfire_grace_time=6 * 3600,
    )


