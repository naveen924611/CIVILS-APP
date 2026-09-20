"""Explain-back feedback: compares what the owner said with the topic's key points."""
import re

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import ExplainSession, Note, Topic
from app.llm import promptlib
from app.rag.search import search as rag_search

from .schemas import ExplainOut

PROMPT = "answers_explain_v1"
MAX_TRANSCRIPT = 6000


def _as_text(item) -> str:
    if isinstance(item, str):
        return item.strip()
    if isinstance(item, dict):
        for key in ("text", "point", "fact", "q"):
            if item.get(key):
                return str(item[key]).strip()
    return ""


def key_points_from_note(db: Session, topic_id: str | None) -> list[str]:
    if not topic_id:
        return []
    note = db.scalar(
        select(Note).where(Note.topic_id == topic_id, Note.deleted.is_(False)).order_by(Note.updated_at.desc())
    )
    if note is None:
        return []
    sections = note.sections or {}
    out: list[str] = []
    for key in ("key_points", "must_remember"):
        for item in sections.get(key) or []:
            text = _as_text(item)
            if text and text not in out:
                out.append(text)
    return out[:14]


def key_points_text(db: Session, gateway, topic: Topic | None, topic_id: str | None) -> tuple[str, bool]:
    """Returns (text for the prompt, True when it comes from the owner's notes)."""
    points = key_points_from_note(db, topic_id)
    if points:
        return "\n".join(f"{i}. {p}" for i, p in enumerate(points, 1)), True
    if topic is not None:
        hits = rag_search(db, gateway, topic.title, topic_ids=[topic.id], k=4)
        if hits:
            body = "\n".join(f"- {re.sub(chr(10) + '+', ' ', h.text)[:500]}" for h in hits)
            return "No written key points yet. Choose the key points from this material of the student:\n" + body, True
    return "(none: use the most important points of the topic from common knowledge)", False


def feedback_for(db: Session, gateway, session: ExplainSession) -> dict | None:
    """Builds feedback_json for a session, or None when the AI was not available."""
    topic = db.get(Topic, session.topic_id) if session.topic_id else None
    points, from_material = key_points_text(db, gateway, topic, session.topic_id)
    system, user = promptlib.load(PROMPT)
    out = gateway.generate_json(
        feature="explain_feedback",
        system=system,
        user=promptlib.render(
            user,
            topic=topic.title if topic else "A topic of the student's choice",
            key_points=points,
            transcript=session.transcript.strip()[:MAX_TRANSCRIPT].replace("</transcript>", ""),
        ),
        schema=ExplainOut,
        max_output_tokens=1800,
    )
    if out is None:
        return None
    assert isinstance(out, ExplainOut)
    total = len(out.covered) + len(out.missed)
    return {
        "covered": out.covered,
        "missed": out.missed,
        "needs_correcting": [c.model_dump() for c in out.needs_correcting if c.said or c.correct],
        "coverage": {"covered": len(out.covered), "total": total},
        "model_explanation": out.model_explanation,
        "from_your_material": from_material and not out.general_knowledge,
    }
