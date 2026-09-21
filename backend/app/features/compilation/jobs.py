"""Job `compilation_build` {month?} -> {compilation_id, month}. Needs no AI, so it never waits for the budget."""
from app.jobs.registry import JobContext, JobFailed, job_handler

from . import build


@job_handler("compilation_build", notify="ready")
def compilation_build(ctx: JobContext) -> dict:
    from datetime import datetime
    from zoneinfo import ZoneInfo

    month = str(ctx.payload.get("month") or "").strip()
    if not month:
        month = build.previous_month(datetime.now(ZoneInfo(ctx.settings.timezone)).date())
    if not build.valid_month(month):
        raise JobFailed("The month must look like 2026-08.")
    row = build.build_month(ctx.db, ctx.settings, month)
    return {"compilation_id": row.id, "month": month}
