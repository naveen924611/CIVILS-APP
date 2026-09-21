"""Job handlers `revision_sheet` {topic_id | topic_ids} and `weekly_report` {week_start?}, plus the scheduled work
(Sunday 20:00 India time: weekly report; every night: sheets whose notes changed)."""
import logging
from datetime import timedelta

from sqlalchemy import select

from app.api.kv import get_kv
from app.db.models_v2 import Job
from app.features.tests.common import parse_day, today_ist
from app.jobs.registry import JobContext, JobFailed, job_handler
from app.services import Services

from . import sheets, weekly

log = logging.getLogger(__name__)
MAX_SHEETS_PER_JOB = 10
NIGHTLY_SHEETS = 10


@job_handler("revision_sheet", feature="revision_sheet")
def revision_sheet(ctx: JobContext) -> dict:
    """payload {topic_id} (or {topic_ids: [...]}); result {sheet_id} (and sheet_ids when several)."""
    ids = [str(t) for t in (ctx.payload.get("topic_ids") or []) if t][:MAX_SHEETS_PER_JOB]
    if ctx.payload.get("topic_id"):
        ids.insert(0, str(ctx.payload["topic_id"]))
    if not ids:
        raise JobFailed("Choose a topic for the revision sheet.")
    made: list[str] = []
    problem: JobFailed | None = None
    for tid in ids:
        try:
            made.append(sheets.build_sheet(ctx.db, ctx.settings, ctx.gateway, tid).id)
        except JobFailed as exc:
            problem = exc
    if not made and problem is not None:
        raise problem
    ctx.db.flush()
    return {"sheet_id": made[0], "sheet_ids": made}


@job_handler("weekly_report", feature="weekly_report")
def weekly_report(ctx: JobContext) -> dict:
    """payload {week_start?: "YYYY-MM-DD", scheduled?: bool}; result {report_id}. Sends the silent push
    {type: "weekly_report", week_start} (the tablet shows 'Weekly report ready' and opens Report) unless notify.weekly_report is off."""
    week = parse_day(ctx.payload.get("week_start"))
    row = weekly.build_report(ctx.db, ctx.settings, ctx.gateway, week)
    ctx.db.flush()
    if get_kv(ctx.db, "notify.weekly_report", True) is not False and ctx.payload.get("scheduled"):
        ctx.services.push("Weekly report ready", "Your week in numbers, weak spots and next week's plan are ready.",
                          {"type": "weekly_report", "week_start": row.week_start})
    return {"report_id": row.id}


# --------------------------------------------------------------------------- scheduled work


def queue_weekly_report(services: Services) -> None:
    with services.session_factory() as db:
        waiting = db.scalars(select(Job).where(Job.type == "weekly_report", Job.status.in_(("queued", "running")),
                                               Job.deleted.is_(False))).first()
        if waiting is not None:
            return
        week = today_ist() - timedelta(days=today_ist().weekday())
        db.add(Job(type="weekly_report", payload_json={"week_start": week.isoformat(), "scheduled": True}))
        db.commit()
    services.kick_jobs()


def nightly_sheets(services: Services) -> None:
    """Rebuilds sheets whose notes changed, and makes sheets for studied topics that have none (a few per night)."""
    guard = getattr(services.gateway, "guard", None)
    if guard is not None and hasattr(guard, "level") and guard.level() >= 3:
        return
    with services.session_factory() as db:
        for tid in sheets.stale_topics(db, NIGHTLY_SHEETS):
            try:
                sheets.build_sheet(db, services.settings, services.gateway, tid)
                db.commit()
            except JobFailed:
                db.rollback()
            except Exception:
                log.exception("nightly sheet failed")
                db.rollback()
