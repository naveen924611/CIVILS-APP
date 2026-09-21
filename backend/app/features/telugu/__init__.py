"""Telugu paper practice (M12, spec 6.22).

Items come from data/telugu/*.yaml (seeded into `telugu_items` at start-up). The tablet chooses the daily set itself
(offline) with the rules in practice.py; GET /telugu/today gives the same set. Job: `telugu_feedback` (jobs.py).
Time reserved for it comes from the KV key `study.telugu_minutes` (the planner already adds a Telugu block).
"""
import logging

from app.services import Services

from . import jobs  # noqa: F401  (importing registers the job handler)
from .api import router  # noqa: F401

log = logging.getLogger(__name__)


def setup(services: Services) -> None:
    from . import seed

    try:
        with services.session_factory() as db:
            counts = seed.seed(db, seed.seed_dir(services.settings))
        log.info("telugu items: %s", counts)
    except Exception as exc:  # a broken seed file must never stop the server
        log.warning("telugu seed failed: %s", exc)
