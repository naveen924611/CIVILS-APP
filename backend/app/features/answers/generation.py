"""Question setting: draft Mains-style questions linked to topics the owner is studying."""
import logging

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.db.models_v2 import AnswerSubmission, Topic
from app.llm import promptlib

from .schemas import QuestionOut

log = logging.getLogger(__name__)
PROMPT = "answers_question_v1"
KINDS = {"mains": 250, "short": 150, "essay": 1000}
STUDYING = ("in_progress", "studied", "revised")
MAX_DRAFTS = 5


def open_drafts(db: Session) -> int:
    return db.scalar(
        select(func.count(AnswerSubmission.id)).where(
            AnswerSubmission.status == "draft", AnswerSubmission.deleted.is_(False)
        )
    ) or 0


def create_draft(db: Session, gateway, topic_id: str | None, kind: str = "mains") -> AnswerSubmission | None:
    """Asks the AI for one question and stores it as a draft. None when the AI was not available."""
    kind = kind if kind in KINDS else "mains"
    topic = db.get(Topic, topic_id) if topic_id else None
    previous = list(db.scalars(
        select(AnswerSubmission.question)
        .where(AnswerSubmission.topic_id == topic_id, AnswerSubmission.deleted.is_(False))
        .order_by(AnswerSubmission.created_at.desc()).limit(5)
    )) if topic_id else []
    system, user = promptlib.load(PROMPT)
    out = gateway.generate_json(
        feature="answer_question",
        system=system,
        user=promptlib.render(
            user,
            topic=topic.title if topic else "Any important General Studies theme (polity, economy, history, geography, society, ethics)",
            kind=kind,
            existing="\n".join(f"- {q[:160]}" for q in previous) or "(none)",
        ),
        schema=QuestionOut,
        max_output_tokens=400,
    )
    if out is None:
        return None
    assert isinstance(out, QuestionOut)
    limit = out.word_limit if kind != "essay" else max(out.word_limit, 1000)
    row = AnswerSubmission(question=out.question.strip(), topic_id=topic_id, word_limit=limit, kind=kind, status="draft")
    db.add(row)
    db.commit()
    return row


def weekly_drafts(services, max_new: int = 3) -> int:
    """Scheduled weekly: makes up to `max_new` new draft questions for topics in progress. Returns how many."""
    gateway = services.gateway
    guard = getattr(gateway, "guard", None)
    if guard is not None and hasattr(guard, "level") and guard.level() >= 3:
        return 0
    made = 0
    with services.session_factory() as db:
        room = MAX_DRAFTS - open_drafts(db)
        if room <= 0:
            return 0
        drafted = set(db.scalars(
            select(AnswerSubmission.topic_id).where(
                AnswerSubmission.status == "draft", AnswerSubmission.deleted.is_(False),
                AnswerSubmission.topic_id.is_not(None),
            )
        ))
        topics = list(db.scalars(
            select(Topic)
            .where(Topic.deleted.is_(False), Topic.status.in_(STUDYING), Topic.level >= 2)
            .order_by(Topic.updated_at.desc()).limit(30)
        ))
        for topic in topics:
            if made >= min(max_new, room):
                break
            if topic.id in drafted:
                continue
            if create_draft(db, gateway, topic.id, "mains") is None:
                break  # AI busy: try again next week
            made += 1
    log.info("answers: %d new draft question(s)", made)
    return made
