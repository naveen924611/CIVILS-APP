"""Plan blocks for the SI (Civil) goal. Both hooks act only while an SI exam is live: a non-deleted Exam whose name has the
whole word "si" or "slprb" (see examnames.is_si_exam) and whose date is not in the past (an exam with no date counts as
live; when there are several SI exams, one that is still to come is enough).

1. Physical block (`phys-<date>`): the physical selection (PMT, then PET: 1600 m plus 100 m or long jump) is trained
   outside the app, so this is only a reminder with a schedule. Kind "other", 06:00, 45 minutes (Sunday 20), ref "goals"
   (the tablet's Goals screen). It is NOT counted in the study hours: the planner leaves blocks whose id starts with
   "phys-" out of the day's total when it fits the plan to the hours (see plan_hooks.counts_toward_hours). Switch it off with
   the KV key `si.physical_plan` = false (default on).
2. Aptitude drill (`drill-<date>`), every day except Sunday: a "practice" block, 19:30, 25 minutes, ref `test/<id>` of the
   day's drill (test kind "aptitude": 20 questions mixing four areas, made on the spot by features/tests/aptitude.py; no AI).
   The test is found or created for that India day, so planning again never makes a second one. This one IS counted in
   the study hours (it takes time from other practice or study, like any block of another feature).

A hook never raises: if the drill cannot be made, its block is skipped and the error is logged.
"""
import logging
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.kv import get_kv
from app.db.models_v2 import Exam
from app.db.util import as_utc
from app.features.examnames import is_si_exam
from app.features.plan_hooks import plan_postprocessor
from app.features.tests import aptitude
from app.features.tests.common import IST, parse_day, today_ist
from app.features.tests.generate import APTITUDE_QUESTIONS, generate_aptitude_test

log = logging.getLogger(__name__)
KV_PHYSICAL = "si.physical_plan"
PHYSICAL_START = "06:00"
DRILL_START = "19:30"
DRILL_MINUTES = 25

# weekday (Monday = 0) -> (title, minutes, detail)
PHYSICAL = {
    0: ("Run: 1600 m pace work", 45, "Warm-up 10 min, 4 x 400 m at target pace with 2 min walk, cool-down and stretching."),
    2: ("Run: 1600 m pace work", 45, "Warm-up 10 min, 4 x 400 m at target pace with 2 min walk, cool-down and stretching."),
    4: ("Run: 1600 m pace work", 45, "Warm-up 10 min, 4 x 400 m at target pace with 2 min walk, cool-down and stretching."),
    1: ("Sprint and long jump drills", 45, "100 m starts, 6 x 30 m accelerations, long-jump take-offs."),
    3: ("Sprint and long jump drills", 45, "100 m starts, 6 x 30 m accelerations, long-jump take-offs."),
    5: ("PET simulation: 1600 m + 100 m or long jump", 45, "Do it on a track, then log the times in Goals."),
    6: ("Mobility and rest", 20, "Easy stretching and mobility work; let your legs recover."),
}


def si_live(db: Session, today: date) -> bool:
    """True when a non-deleted SI exam exists that is not in the past (no date counts as live)."""
    for exam in db.scalars(select(Exam).where(Exam.deleted.is_(False))):
        if not is_si_exam(exam.name):
            continue
        when = as_utc(exam.date) if exam.date is not None else None
        if when is None or when.astimezone(IST).date() >= today:
            return True
    return False


def _day(day: str, today: date) -> date | None:
    d = parse_day(day)
    if d is None or d < today:  # nothing is added to days that are over
        return None
    return d


@plan_postprocessor
def add_physical_block(db: Session, day: str, blocks: list[dict], ctx: dict) -> list[dict]:
    try:
        today = today_ist()
        d = _day(day, today)
        block_id = f"phys-{day}"
        if d is None or any(b.get("id") == block_id for b in blocks):
            return blocks
        if get_kv(db, KV_PHYSICAL, True) is False or not si_live(db, today):
            return blocks
        title, minutes, detail = PHYSICAL[d.weekday()]
        blocks.append({"id": block_id, "kind": "other", "start": PHYSICAL_START, "minutes": minutes, "title": title, "detail": detail,
                       "topic_id": None, "ref": "goals"})
    except Exception:
        log.exception("SI physical block skipped")
    return blocks


@plan_postprocessor
def add_aptitude_drill(db: Session, day: str, blocks: list[dict], ctx: dict) -> list[dict]:
    try:
        today = today_ist()
        d = _day(day, today)
        block_id = f"drill-{day}"
        if d is None or d.weekday() == 6 or any(b.get("id") == block_id for b in blocks):
            return blocks
        if not si_live(db, today):
            return blocks
        test = generate_aptitude_test(db, d, None, APTITUDE_QUESTIONS)
        focus = aptitude.LABELS[aptitude.area_for_date(d)]
        others = ", ".join(aptitude.LABELS[a] for a in aptitude.areas_for_date(d)[1:])
        blocks.append({"id": block_id, "kind": "practice", "start": DRILL_START, "minutes": DRILL_MINUTES,
                       "title": f"Aptitude drill ({APTITUDE_QUESTIONS} questions)",
                       "detail": f"Focus today: {focus}. Also mixed in: {others}.", "topic_id": None, "ref": f"test/{test.id}"})
    except Exception:
        log.exception("SI aptitude drill skipped")
    return blocks

