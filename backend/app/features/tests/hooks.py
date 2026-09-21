"""Reactions to rows the tablet pushes: analysis of a finished test, and cards from the mistake book.
Hooks run inside the push request, so they never raise (a bug here must not lose the owner's answers)."""
import logging

from app.db.models_v2 import Attempt, Mistake, Test
from app.sync.hooks import on_push

from .mistakes import make_card_for_mistake
from .scoring import maybe_analyse

log = logging.getLogger(__name__)


@on_push("tests")
def test_pushed(db, row: Test, is_new: bool) -> None:
    try:
        maybe_analyse(db, row)
    except Exception:
        log.exception("test analysis failed")


@on_push("attempts")
def attempt_pushed(db, row: Attempt, is_new: bool) -> None:
    if not row.test_id:
        return
    try:
        test = db.get(Test, row.test_id)
        if test is not None:
            maybe_analyse(db, test)
    except Exception:
        log.exception("test analysis failed")


@on_push("mistakes")
def mistake_pushed(db, row: Mistake, is_new: bool) -> None:
    try:
        make_card_for_mistake(db, row)
    except Exception:
        log.exception("mistake card failed")
