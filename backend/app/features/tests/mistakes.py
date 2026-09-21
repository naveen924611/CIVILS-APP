"""The mistake book feeds the revision cards (spec 6.23, 6.17).

The tablet keeps the book itself (it must work offline). When a mistake row arrives the server makes one revision card
for that question: a plain question card for "didn't know", a comparison card for "confused two options". A "silly
mistake" only gets the read-slowly tip on the analysis, no card. A question never gets a second card.
"""
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Card
from app.db.models_v2 import Mcq, Mistake, Topic

from .common import subject_of

SOURCE = "mistake"
LETTERS = "ABCD"


def _options_text(mcq: Mcq) -> str:
    return "\n".join(f"{LETTERS[i]}) {o}" for i, o in enumerate((mcq.options or [])[:4]))


def card_texts(mcq: Mcq, mistake: Mistake) -> tuple[str, str] | None:
    """(front, back) for the mistake, or None when no card is wanted."""
    options = list(mcq.options or [])
    if not options or not 0 <= mcq.answer_index < len(options):
        return None
    right = options[mcq.answer_index]
    why = (mcq.explanation or "").strip()
    if mistake.mistake_type == "confused":
        mine = options[mistake.your_answer] if 0 <= mistake.your_answer < len(options) else None
        if mine is None or mine == right:
            return None
        front = f"{mcq.question.strip()}\n\nYou mixed up: \"{mine}\" and \"{right}\". Which is right?"
        back = f"Right: {right}. Not: {mine}." + (f" {why}" if why else "")
        return front, back
    if mistake.mistake_type == "didnt_know":
        return f"{mcq.question.strip()}\n{_options_text(mcq)}", f"{LETTERS[mcq.answer_index]}) {right}." + (f" {why}" if why else "")
    return None


def make_card_for_mistake(db: Session, mistake: Mistake) -> Card | None:
    """Creates the card once. Returns it, or None when nothing was made."""
    if mistake.deleted or mistake.resolved:
        return None
    mcq = db.get(Mcq, mistake.mcq_id)
    if mcq is None or mcq.deleted:
        return None
    texts = card_texts(mcq, mistake)
    if texts is None:
        return None
    exists = db.scalars(select(Card).where(Card.source_type == SOURCE, Card.source_id == mcq.id)).first()
    if exists is not None:  # (also when the owner deleted it: do not bring it back)
        return None
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False)))}
    group = subject_of(mcq.topic_id, topics)
    card = Card(front=texts[0][:400], back=texts[1][:600], topic_id=mcq.topic_id, source_type=SOURCE, source_id=mcq.id,
                group=(group if group != "Other" else "Mistakes")[:60], fsrs_state_json=None,
                due_at=datetime.now(timezone.utc))
    db.add(card)
    db.flush()
    return card
