"""Job handlers of the tests feature: `mock_test` and `test_generate` (make a test), `pyq_import` (read a past paper),
and the scheduled work (weekly mock the night before the mock day, nightly past-paper mapping)."""
import logging
from datetime import timedelta

from sqlalchemy import select

from app.api.kv import get_kv
from app.db.models_v2 import Job, Test
from app.jobs.registry import JobContext, job_handler
from app.services import Services

from . import pyq
from .common import day_name_to_index, today_ist
from .generate import find_weekly, generate_test

log = logging.getLogger(__name__)
KV_WEEKLY_DAY = "test.weekly_day"  # "mon" .. "sun", default "sun" (written by TestSettingsSection)


def _announce(ctx: JobContext, test: Test) -> None:
    """A test is ready: a silent push (the tablet builds the notification). type mock_ready -> opens Tests."""
    kind = "Weekly mock test" if test.kind == "weekly" else "Your test"
    ctx.services.push("Test ready", f"{kind} is ready: {len(test.mcq_ids or [])} questions, {test.duration_min} minutes.",
                      {"type": "mock_ready", "test_id": test.id})


def _make(ctx: JobContext, default_kind: str) -> dict:
    payload = dict(ctx.payload)
    payload.setdefault("kind", default_kind)
    test = generate_test(ctx.db, ctx.gateway, payload)
    ctx.db.flush()
    _announce(ctx, test)
    return {"test_id": test.id}


@job_handler("mock_test", feature="mock_test")
def mock_test(ctx: JobContext) -> dict:
    """payload {kind: "weekly", week_start?, date?, count?}; result {test_id}."""
    return _make(ctx, "weekly")


@job_handler("test_generate", feature="test_generate")
def test_generate(ctx: JobContext) -> dict:
    """payload {kind: "topic"|"past_paper"|"mistakes"|"weekly", topic_id?, exam?, year?, paper?, count?}; result {test_id}."""
    return _make(ctx, "topic")


@job_handler("pyq_import", feature="test_generate")
def pyq_import(ctx: JobContext) -> dict:
    """payload {document_id, exam, year, paper?}; result {added, skipped, no_answer, mapped, pages}."""
    p = ctx.payload
    try:
        year = int(p.get("year") or 0)
    except (TypeError, ValueError):
        year = 0
    result = pyq.import_pyqs(ctx.db, ctx.gateway, str(p.get("document_id") or ""), str(p.get("exam") or ""), year,
                             str(p.get("paper") or ""))
    ctx.db.flush()
    return result


# --------------------------------------------------------------------------- scheduled work


def queue_weekly_mock(services: Services) -> None:
    """Every night: when tomorrow is the owner's mock day (KV test.weekly_day, default Sunday), queue the weekly mock.
    The job runner retries when the AI is busy."""
    target = today_ist() + timedelta(days=1)
    with services.session_factory() as db:
        if target.weekday() != day_name_to_index(get_kv(db, KV_WEEKLY_DAY, "sun")):
            return
        if find_weekly(db, target) is not None:
            return
        waiting = db.scalars(select(Job).where(Job.type == "mock_test", Job.status.in_(("queued", "running")),
                                               Job.deleted.is_(False))).first()
        if waiting is not None:
            return
        db.add(Job(type="mock_test", payload_json={"kind": "weekly", "date": target.isoformat()}))
        db.commit()
    services.kick_jobs()


def nightly_pyq_mapping(services: Services) -> None:
    with services.session_factory() as db:
        try:
            log.info("past-paper mapping: %s", pyq.map_all(db))
        except Exception:
            log.exception("past-paper mapping failed")
            db.rollback()
