"""Tests, mistake book and past papers (M8).

Jobs (jobs.py): `mock_test`, `test_generate` -> {test_id}; `pyq_import`. Hooks (hooks.py): analysis of a finished test,
cards from the mistake book. Routes (api.py): /tests/... Plan block for the weekly mock (plan.py).
Scheduled (only when the scheduler is enabled): every night 22:00 India time the weekly mock is queued when tomorrow is
the mock day (KV `test.weekly_day`, default "sun"); 02:50 past-paper questions are mapped to topics.
"""
from app.services import Services

from . import hooks, jobs, plan  # noqa: F401  (importing registers the job handlers, sync hooks and the plan hook)
from .api import router  # noqa: F401


def setup(services: Services) -> None:
    if not services.settings.scheduler_enabled:
        return
    services.scheduler.add_job(
        jobs.queue_weekly_mock, "cron", hour=22, minute=0, id="tests:weekly_mock", replace_existing=True,
        args=[services], misfire_grace_time=6 * 3600, timezone=services.settings.timezone,
    )
    services.scheduler.add_job(
        jobs.nightly_pyq_mapping, "cron", hour=2, minute=50, id="tests:pyq_mapping", replace_existing=True,
        args=[services], misfire_grace_time=6 * 3600, timezone=services.settings.timezone,
    )
