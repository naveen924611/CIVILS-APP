"""Lets any feature add blocks to a day's plan without knowing about the planner.

The planner (features/planner) builds the base blocks for a date, then runs every registered post-processor in order
, each may add, change or remove blocks:

    from app.features.plan_hooks import plan_postprocessor

    @plan_postprocessor
    def add_weekly_mock(db, date: str, blocks: list[dict], ctx: dict) -> list[dict]:
        if date_is_sunday(date):
            blocks.append({"id": "mock-2026-09-27", "kind": "mock", "start": "10:00", "minutes": 30,
                           "title": "Weekly mock test", "detail": "25 questions", "topic_id": None,
                           "ref": "test/<test id>"})
        return blocks

Block fields: id (unique in the day), kind (brief|study|revision|practice|telugu|mock|answer|review|library|sheet|other),
start ("HH:MM", India time), minutes, title, detail, topic_id (or None), ref (a tablet route from ui/nav/Routes.kt that the
Start button opens, or None). `ctx` has: {"exam_days": {exam_name: days_left}, "last_month": bool, "settings": Settings}.
"""
from collections.abc import Callable
from typing import Any

from sqlalchemy.orm import Session

PlanPost = Callable[[Session, str, list[dict], dict[str, Any]], list[dict]]
PLAN_POSTPROCESSORS: list[PlanPost] = []


def plan_postprocessor(fn: PlanPost) -> PlanPost:
    PLAN_POSTPROCESSORS.append(fn)
    return fn


def run_postprocessors(db: Session, date: str, blocks: list[dict], ctx: dict[str, Any]) -> list[dict]:
    for fn in PLAN_POSTPROCESSORS:
        blocks = fn(db, date, blocks, ctx)
    return blocks
