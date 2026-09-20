"""Adds a daily 'Answer writing' block to the plan when the owner has a draft question waiting."""
from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import AnswerSubmission
from app.features.plan_hooks import plan_postprocessor

BLOCK_MINUTES = 30
LATEST_START = 21 * 60


def _minutes(hhmm: str) -> int | None:
    try:
        h, m = hhmm.split(":")
        return int(h) * 60 + int(m)
    except (ValueError, AttributeError):
        return None


def _today(ctx: dict) -> str:
    settings = ctx.get("settings")
    tz = getattr(settings, "timezone", "Asia/Kolkata")
    return datetime.now(ZoneInfo(tz)).strftime("%Y-%m-%d")


@plan_postprocessor
def add_answer_block(db: Session, date: str, blocks: list[dict], ctx: dict) -> list[dict]:
    if date < _today(ctx):
        return blocks
    block_id = f"answer-{date}"
    if any(b.get("id") == block_id or b.get("kind") == "answer" for b in blocks):
        return blocks
    draft = db.scalar(
        select(AnswerSubmission)
        .where(AnswerSubmission.status == "draft", AnswerSubmission.deleted.is_(False))
        .order_by(AnswerSubmission.created_at)
    )
    if draft is None:
        return blocks
    end = 0
    for b in blocks:
        start = _minutes(str(b.get("start", "")))
        if start is not None:
            end = max(end, start + int(b.get("minutes") or 0))
    start_at = min(max(end, 17 * 60), LATEST_START)
    question = " ".join(draft.question.split())
    blocks.append({
        "id": block_id,
        "kind": "answer",
        "start": f"{start_at // 60:02d}:{start_at % 60:02d}",
        "minutes": BLOCK_MINUTES,
        "title": "Answer writing",
        "detail": question if len(question) <= 90 else question[:87].rstrip() + "...",
        "topic_id": draft.topic_id,
        "ref": "answers",
    })
    return blocks
