"""Explain-back feedback and answer writing (M9).

Jobs: `explain_feedback`, `answer_eval` (jobs.py). Routes: POST /answers/generate, POST /answers/{id}/images (api.py).
Weekly draft questions run only when the scheduler is enabled. `plan.py` adds the daily answer block.
"""
from app.services import Services

from . import jobs, plan  # noqa: F401  (importing registers the job handlers and the plan hook)
from .api import router  # noqa: F401


def setup(services: Services) -> None:
    if not services.settings.scheduler_enabled:
        return
    from .generation import weekly_drafts

    services.scheduler.add_job(
        weekly_drafts, "cron", day_of_week="sun", hour=6, minute=30, id="answers:weekly_drafts",
        replace_existing=True, args=[services], misfire_grace_time=6 * 3600,
    )
