"""Adds the weekly mock to the plan of its day (Sunday by default) once it has been generated (the night before)."""
from sqlalchemy.orm import Session

from app.features.plan_hooks import plan_postprocessor

from .common import parse_day
from .generate import find_weekly


@plan_postprocessor
def add_weekly_mock(db: Session, day: str, blocks: list[dict], ctx: dict) -> list[dict]:
    d = parse_day(day)
    if d is None or any(b.get("kind") == "mock" for b in blocks):
        return blocks
    test = find_weekly(db, d)
    if test is None or test.status not in ("ready", "in_progress"):
        return blocks
    blocks.append({
        "id": f"mock-{d.isoformat()}", "kind": "mock", "start": "10:00", "minutes": int(test.duration_min or 30),
        "title": "Weekly mock test", "detail": f"{len(test.mcq_ids or [])} questions", "topic_id": None,
        "ref": f"test/{test.id}",
    })
    return blocks
