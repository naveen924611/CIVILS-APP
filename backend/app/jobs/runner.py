"""Works through queued jobs. Safe to call often: only one run happens at a time."""
import logging
import threading
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app.db.models_v2 import Job
from app.db.util import as_utc
from app.jobs.registry import HANDLERS, NOTIFY_TEXT, AiUnavailable, JobContext, JobFailed
from app.services import Services

log = logging.getLogger(__name__)
_lock = threading.Lock()
_STUCK_AFTER = timedelta(minutes=10)


def kick(services: Services) -> None:
    threading.Thread(target=run_pending, args=(services,), daemon=True, name="jobs").start()


def run_pending(services: Services, limit: int = 25) -> int:
    """Runs queued jobs, then sends ONE push saying how many results are ready. Returns jobs finished."""
    if not _lock.acquire(blocking=False):
        return 0
    try:
        return _run(services, limit)
    finally:
        _lock.release()


def _run(services: Services, limit: int) -> int:
    settings = services.settings
    now = datetime.now(timezone.utc)
    finished: dict[str, int] = {}
    with services.session_factory() as db:
        # a job stuck in "running" (server restarted mid-way) goes back to the queue
        for job in db.scalars(select(Job).where(Job.status == "running", Job.deleted.is_(False))):
            if as_utc(job.updated_at) < now - _STUCK_AFTER:
                job.status = "queued"
        db.commit()
        ids = list(db.scalars(
            select(Job.id).where(Job.status == "queued", Job.deleted.is_(False)).order_by(Job.created_at).limit(limit)
        ))
    for job_id in ids:
        with services.session_factory() as db:
            job = db.get(Job, job_id)
            if job is None or job.status != "queued":
                continue
            info = HANDLERS.get(job.type)
            if info is None:
                job.status, job.error = "failed", f"Unknown job type '{job.type}'"
                db.commit()
                continue
            state = dict(job.result_json or {})
            if state.get("_retry_after", 0) > now.timestamp():
                continue
            guard = getattr(services.gateway, "guard", None)
            if guard is not None and hasattr(guard, "allow") and not guard.allow(info.feature):
                continue  # deferrable work waits until tomorrow's budget
            job.status = "running"
            db.commit()
            try:
                result = info.fn(JobContext(db=db, job=job, payload=dict(job.payload_json or {}), services=services))
                db.refresh(job)
                job.status, job.result_json, job.error = "done", result, ""
                if info.notify in NOTIFY_TEXT:
                    finished[info.notify] = finished.get(info.notify, 0) + 1
                else:
                    job.notified = True
            except AiUnavailable as exc:
                db.rollback()
                job = db.get(Job, job_id)
                attempts = int(state.get("_attempts", 0)) + 1
                if attempts >= settings.job_max_attempts:
                    job.status, job.error = "failed", "The AI was not available. Please try again later."
                    job.result_json = None
                else:
                    job.status, job.error = "queued", str(exc)[:200]
                    job.result_json = {
                        "_attempts": attempts,
                        "_retry_after": (now + timedelta(minutes=settings.job_retry_minutes * attempts)).timestamp(),
                    }
            except JobFailed as exc:
                db.rollback()
                job = db.get(Job, job_id)
                job.status, job.error, job.result_json = "failed", str(exc)[:290], None
            except Exception as exc:  # a bug in one handler must not stop the others
                log.exception("job %s (%s) crashed", job_id, job.type if job else "?")
                db.rollback()
                job = db.get(Job, job_id)
                job.status, job.error, job.result_json = "failed", f"Unexpected error: {type(exc).__name__}", None
            db.commit()
    total = sum(finished.values())
    if total:
        _notify(services, finished)
    return total


def _notify(services: Services, finished: dict[str, int]) -> None:
    parts = []
    for kind, n in finished.items():
        one, many = NOTIFY_TEXT[kind]
        parts.append(f"{n} {one if n == 1 else many}")
    body = " and ".join(parts) + " ready"
    sent = services.push("Civils Companion", body[0].upper() + body[1:], {"type": "jobs_done", "count": str(sum(finished.values()))})
    log.info("jobs finished (%s), push sent to %s device(s)", body, sent)
    with services.session_factory() as db:
        for job in db.scalars(select(Job).where(Job.status == "done", Job.notified.is_(False))):
            job.notified = True
        db.commit()
