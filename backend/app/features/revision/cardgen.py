"""Makes flashcards from the owner's notes and highlights (spec 7.5: "Card sources").

No AI is needed here, so it works offline and never costs quota:
  * `sections.cards` of a note are ready-made question/answer pairs (written by the notes job) -> used as they are;
  * `sections.must_remember` points become "finish the sentence" cards (front = the first half, back = the whole point);
  * highlights of kind `must` become the same kind of card, kind `card` becomes a "What does this point say?" card.
A card is never created twice: the same front text on the same topic (any source) is skipped, so this is safe to run
after every change and alongside other builders that also create cards.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Card
from app.db.models_v2 import Highlight, Note, Topic

from .service import subject_of

MAX_FRONT = 400
MAX_BACK = 600


def norm(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (text or "").lower()).strip()


def finish_sentence_front(point: str, title: str = "") -> str:
    """'Article 21 protects life and personal liberty' -> 'Finish the point: Article 21 protects ...' (first ~45 percent)."""
    words = point.split()
    if len(words) < 4:
        return f"Recall this point about {title}: {point}"[:MAX_FRONT] if title else f"Recall: {point}"[:MAX_FRONT]
    keep = min(max(3, int(round(len(words) * 0.45))), len(words) - 2)
    head = " ".join(words[:keep])
    lead = f"{title}: " if title else ""
    return f"{lead}Finish the point: {head} ...".strip()[:MAX_FRONT]


def _new_card(front: str, back: str, topic: Topic | None, source_type: str, source_id: str, group: str, now: datetime) -> Card:
    return Card(
        front=front[:MAX_FRONT], back=back[:MAX_BACK], topic_id=topic.id if topic else None, source_type=source_type,
        source_id=source_id, group=group[:60] or "General", fsrs_state_json=None, due_at=now,
    )


def existing_fronts(db: Session) -> set[tuple[str | None, str]]:
    return {(c.topic_id, norm(c.front)) for c in db.scalars(select(Card).where(Card.deleted.is_(False)))}


def cards_for_note(db: Session, note: Note, topics: dict[str, Topic], seen: set[tuple[str | None, str]], now: datetime) -> list[Card]:
    topic = topics.get(note.topic_id)
    if topic is None or topic.deleted:
        return []
    group = subject_of(topic, topics)
    sections = note.sections or {}
    made: list[Card] = []

    def add(front: str, back: str) -> None:
        front, back = (front or "").strip(), (back or "").strip()
        key = (topic.id, norm(front))
        if not front or not back or key in seen:
            return
        seen.add(key)
        card = _new_card(front, back, topic, "note", note.id, group, now)
        db.add(card)
        made.append(card)

    for c in sections.get("cards") or []:
        if isinstance(c, dict):
            add(str(c.get("front", "")), str(c.get("back", "")))
    for point in sections.get("must_remember") or []:
        text = str(point).strip()
        if text:
            add(finish_sentence_front(text, topic.title), text)
    return made


def cards_for_highlight(db: Session, h: Highlight, topics: dict[str, Topic], seen: set[tuple[str | None, str]], now: datetime) -> Card | None:
    if h.kind not in ("must", "card") or not (h.text or "").strip():
        return None
    topic = topics.get(h.topic_id) if h.topic_id else None
    title = topic.title if topic else ""
    text = h.text.strip()
    front = finish_sentence_front(text, title) if h.kind == "must" else f"{title + ': ' if title else ''}What does this point say? {text[:60]}..."
    back = text
    key = (topic.id if topic else None, norm(front))
    if key in seen:
        return None
    seen.add(key)
    card = _new_card(front, back, topic, "highlight", h.id, subject_of(topic, topics) if topic else "General", now)
    db.add(card)
    return card


def generate_cards(db: Session, topic_id: str | None = None, now: datetime | None = None) -> dict:
    """Creates the missing cards for every note (or one topic) and for the owner's 'must remember' and 'card' highlights.
    Returns {"created": n, "notes": n, "highlights": n}."""
    now = now or datetime.now(timezone.utc)
    topics = {t.id: t for t in db.scalars(select(Topic))}
    seen = existing_fronts(db)
    q = select(Note).where(Note.deleted.is_(False))
    if topic_id:
        q = q.where(Note.topic_id == topic_id)
    from_notes = from_highlights = 0
    for note in db.scalars(q):
        from_notes += len(cards_for_note(db, note, topics, seen, now))
    hq = select(Highlight).where(Highlight.deleted.is_(False), Highlight.kind.in_(("must", "card")))
    if topic_id:
        hq = hq.where(Highlight.topic_id == topic_id)
    for h in db.scalars(hq):
        if cards_for_highlight(db, h, topics, seen, now) is not None:
            from_highlights += 1
    db.flush()
    return {"created": from_notes + from_highlights, "notes": from_notes, "highlights": from_highlights}
