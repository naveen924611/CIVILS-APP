"""Revision (M5, spec 6.5, 6.15, 7.5): cards from notes, FSRS-6, the day's queue with rules, Sunday review.

The tablet grades cards offline with its own copy of FSRS (android srs/Fsrs.kt) and syncs cards and reviews; the server:
  * makes cards from notes and highlights (`cardgen.py`, POST /revision/generate, and every night),
  * recomputes topic strength and status from the cards every night (`service.update_strength`),
  * serves the queue / grading routes (`api.py`) for tests and other features.
Routes: GET /revision/queue, GET /revision/sunday-review, POST /revision/generate|review|snooze|recompute,
PUT and DELETE /revision/order.  `revision_sheet` jobs belong to the reports feature (V4), not to this package.
"""
import logging

from app.services import Services

from .api import router  # noqa: F401

log = logging.getLogger(__name__)


def nightly(services: Services) -> None:
    """02:30 India time: make cards for new notes, then refresh topic strength and status."""
    from .cardgen import generate_cards
    from .service import update_strength

    try:
        with services.session_factory() as db:
            made = generate_cards(db)
            stats = update_strength(db)
            db.commit()
            log.info("revision nightly: %s %s", made, stats)
    except Exception:  # never let a nightly job crash the scheduler
        log.exception("revision nightly failed")


def setup(services: Services) -> None:
    if not services.settings.scheduler_enabled:
        return
    services.scheduler.add_job(
        nightly, "cron", hour=2, minute=30, id="revision:nightly", replace_existing=True, args=[services],
        misfire_grace_time=6 * 3600,
    )
