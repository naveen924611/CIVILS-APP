"""Last-month mode (spec 6.18): in the 30 days before an exam, revision switches to revision sheets (8 to 10 a day).
The planner tells us with ctx["last_month"]. The same rotation is used by the tablet's Sheets screen (ui/sheets/SheetLogic.kt),
so both show the same sheets: sheets sorted by topic importance (high first, then topic id), a window of `PER_DAY` starting
at (days since 1970-01-01 * PER_DAY) modulo the number of sheets."""
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Sheet, Topic
from app.features.plan_hooks import plan_postprocessor
from app.features.tests.common import parse_day

PER_DAY = 9
MINUTES_PER_SHEET = 4
EPOCH = date(1970, 1, 1)


def pick_sheets(items: list[tuple[str, float]], epoch_day: int, per_day: int = PER_DAY) -> list[str]:
    """items = [(topic_id, importance)]; returns the topic ids for that day."""
    ordered = [tid for tid, _ in sorted(items, key=lambda p: (-p[1], p[0]))]
    n = len(ordered)
    if n == 0:
        return []
    if n <= per_day:
        return ordered
    start = (epoch_day * per_day) % n
    return [ordered[(start + i) % n] for i in range(per_day)]


def sheet_items(db: Session) -> tuple[list[tuple[str, float]], dict[str, str]]:
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False)))}
    items: list[tuple[str, float]] = []
    for s in db.scalars(select(Sheet).where(Sheet.deleted.is_(False), Sheet.status == "ready")):
        t = topics.get(s.topic_id)
        if t is not None and (s.topic_id, t.importance or 0.0) not in items:
            items.append((s.topic_id, float(t.importance or 0.0)))
    return items, {i: topics[i].title for i, _ in items}


@plan_postprocessor
def add_sheet_block(db: Session, day: str, blocks: list[dict], ctx: dict) -> list[dict]:
    d = parse_day(day)
    if d is None or not ctx.get("last_month") or any(b.get("kind") == "sheet" for b in blocks):
        return blocks
    items, titles = sheet_items(db)
    picked = pick_sheets(items, (d - EPOCH).days)
    if not picked:
        return blocks
    names = [titles[i] for i in picked]
    start = "17:00"
    blocks.append({
        "id": f"sheets-{d.isoformat()}", "kind": "sheet", "start": start, "minutes": MINUTES_PER_SHEET * len(picked),
        "title": f"Revision sheets ({len(picked)})",
        "detail": ", ".join(names[:3]) + (" and more" if len(names) > 3 else ""), "topic_id": None, "ref": "sheets",
    })
    return blocks
