"""Revision sheets, weekly report and last-month mode (M10).

Jobs (jobs.py): `revision_sheet` -> {sheet_id}; `weekly_report` -> {report_id}. Routes (api.py): /reports/... (PDFs).
Plan block for last-month mode (plan.py). Scheduled (only when the scheduler is enabled): Sunday 20:00 India time the
weekly report is queued (silent push {type: "weekly_report"} when done); 03:20 every night sheets whose notes changed
are rebuilt (a few per night).
"""
from app.services import Services

from . import jobs, plan  # noqa: F401  (importing registers the job handlers and the plan hook)
from .api import router  # noqa: F401


def setup(services: Services) -> None:
    if not services.settings.scheduler_enabled:
        return
    tz = services.settings.timezone
    services.scheduler.add_job(
        jobs.queue_weekly_report, "cron", day_of_week="sun", hour=20, minute=0, id="reports:weekly", replace_existing=True,
        args=[services], misfire_grace_time=6 * 3600, timezone=tz,
    )
    services.scheduler.add_job(
        jobs.nightly_sheets, "cron", hour=3, minute=20, id="reports:sheets", replace_existing=True,
        args=[services], misfire_grace_time=6 * 3600, timezone=tz,
    )
