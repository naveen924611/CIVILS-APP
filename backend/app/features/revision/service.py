"""Database side of revision: the day's queue, grading a card on the server, topic strength, snooze, settings.

The tablet does all of this offline with the same rules (android srs/*); the server copy is used for nightly
recalculation, for tests and for anything that runs while the tablet is away (weekly report, planner).
"""
from __future__ import annotations

import logging
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.kv import get_kv
from app.db.models import Card
from app.db.models_v2 import Exam, Review, RevisionOrder, RevisionRule, Topic
from app.db.util import as_utc
from app.srs import fsrs6
from app.srs.strength import status_for, topic_memory

from . import queue as q

log = logging.getLogger(__name__)
IST = ZoneInfo("Asia/Kolkata")

KV_SLOT = "revision.slot_time"
KV_MAX = "revision.max_cards"
KV_SUNDAY = "revision.sunday_review"
KV_RETENTION = "revision.retention"
KV_WEIGHTS = "revision.weights"
KV_NEW = "revision.new_per_day"
KV_SNOOZE = "revision.snoozed"  # {"topic_id": "YYYY-MM-DD"} snoozed until that date (the topic is left out before it)


# ---------------------------------------------------------------------------------------------- dates
def study_day(now: datetime | None = None) -> date:
    return (now or datetime.now(timezone.utc)).astimezone(IST).date()


def end_of_day(day: date) -> datetime:
    """The last instant of a study day (India) as UTC. Cards due up to here count as 'due today'."""
    return datetime.combine(day + timedelta(days=1), time(0, 0), tzinfo=IST).astimezone(timezone.utc) - timedelta(milliseconds=1)


def start_of_day(day: date) -> datetime:
    return datetime.combine(day, time(0, 0), tzinfo=IST).astimezone(timezone.utc)


# ---------------------------------------------------------------------------------------------- settings
def get_engine(db: Session) -> fsrs6.Fsrs6:
    retention = get_kv(db, KV_RETENTION, None)
    try:
        return fsrs6.engine(float(retention) if retention is not None else None)
    except (TypeError, ValueError):
        return fsrs6.engine()


def _int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def load_config(db: Session) -> q.RevisionConfig:
    """KV settings first, then enabled rule rows (revision_rules) that add or override: pinned_subject {subject},
    daily_group {group, cards}, max_cards {n}, sunday_review {enabled}."""
    cfg = q.RevisionConfig(
        max_cards=max(_int(get_kv(db, KV_MAX, q.DEFAULT_MAX_CARDS), q.DEFAULT_MAX_CARDS), 5),
        new_per_day=max(_int(get_kv(db, KV_NEW, q.DEFAULT_NEW_PER_DAY), q.DEFAULT_NEW_PER_DAY), 0),
        sunday_review=bool(get_kv(db, KV_SUNDAY, True)),
    )
    w = get_kv(db, KV_WEIGHTS, None)
    if isinstance(w, list) and len(w) == 4:
        try:
            cfg.weights = tuple(float(x) for x in w)  # type: ignore[assignment]
        except (TypeError, ValueError):
            pass
    rows = db.scalars(select(RevisionRule).where(RevisionRule.deleted.is_(False), RevisionRule.enabled.is_(True)))
    for r in rows:
        p = r.params_json or {}
        if r.type == "pinned_subject" and p.get("subject"):
            cfg.pinned_subject = str(p["subject"])
        elif r.type == "daily_group" and p.get("group"):
            cfg.daily_group = str(p["group"])
            cfg.daily_group_cards = max(_int(p.get("cards"), 10), 1)
        elif r.type == "max_cards" and p.get("n"):
            cfg.max_cards = max(_int(p["n"], cfg.max_cards), 5)
        elif r.type == "sunday_review":
            cfg.sunday_review = bool(p.get("enabled", True))
    return cfg


# ---------------------------------------------------------------------------------------------- topics and cards
def subject_of(topic: Topic | None, topics: dict[str, Topic]) -> str:
    """Subject name (level 1 ancestor). Falls back to the topic's own title, or 'General'."""
    node, seen = topic, set()
    while node is not None and node.id not in seen:
        seen.add(node.id)
        if node.level == 1:
            return node.title
        node = topics.get(node.parent_id) if node.parent_id else None
    return topic.title if topic is not None else "General"


def exam_proximity(topic: Topic, exams: list[Exam], today: date) -> float:
    """1.0 when an exam this topic belongs to is today, falling to 0 at 180 days or more; 0 when no date is known."""
    best = 0.0
    tags = [t.lower() for t in (topic.exam_tags or [])]
    for e in exams:
        if e.date is None:
            continue
        if tags and not any(t in e.name.lower() for t in tags):
            continue
        left = (as_utc(e.date).astimezone(IST).date() - today).days
        if left < 0:
            continue
        best = max(best, 1.0 - min(left, 180) / 180.0)
    return best


def _queue_card(c: Card) -> q.QueueCard:
    return q.QueueCard(id=c.id, topic_id=c.topic_id, group=c.group or "Cards", state=c.fsrs_state_json, due_at=as_utc(c.due_at))


def load_cards(db: Session) -> list[Card]:
    return list(db.scalars(select(Card).where(Card.deleted.is_(False))))


def topic_facts(db: Session, today: date) -> tuple[dict[str, q.TopicFacts], dict[str, Topic]]:
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False)))}
    exams = list(db.scalars(select(Exam).where(Exam.deleted.is_(False))))
    facts = {
        t.id: q.TopicFacts(
            id=t.id, title=t.title, subject=subject_of(t, topics), strength=float(t.strength or 0.0),
            importance=min(max(float(t.importance or 0.0) / 10.0, 0.0), 1.0), exam_proximity=exam_proximity(t, exams, today),
        )
        for t in topics.values()
    }
    return facts, topics


def snoozed_topics(db: Session, today: date) -> set[str]:
    data = get_kv(db, KV_SNOOZE, {}) or {}
    out = set()
    if isinstance(data, dict):
        for tid, until in data.items():
            try:
                if date.fromisoformat(str(until)[:10]) > today:
                    out.add(tid)
            except ValueError:
                continue
    return out


# ---------------------------------------------------------------------------------------------- the queue
def manual_order(db: Session, day: date) -> list[str] | None:
    row = db.scalar(
        select(RevisionOrder).where(RevisionOrder.date == day.isoformat(), RevisionOrder.deleted.is_(False))
        .order_by(RevisionOrder.updated_at.desc())
    )
    return list(row.group_order or []) if row is not None else None


def build_queue(db: Session, now: datetime | None = None, *, smart: bool = False) -> dict:
    """The queue for the study day of `now`. `smart=True` ignores a saved 'My order'."""
    now = now or datetime.now(timezone.utc)
    day = study_day(now)
    engine = get_engine(db)
    cfg = load_config(db)
    facts, _ = topic_facts(db, day)
    cards = load_cards(db)
    qcards = [_queue_card(c) for c in cards]
    by_id = {c.id: c for c in qcards}
    groups = q.build_groups(
        engine, qcards, facts, now, end_of_day(day), cfg, is_sunday=day.weekday() == 6,
        snoozed_topics=snoozed_topics(db, day),
    )
    groups = q.apply_rules(groups, cfg, by_id)
    order = None if smart else manual_order(db, day)
    if order:
        groups = q.apply_manual_order(groups, order)
        groups = q.cut_to_max(groups, cfg.max_cards)
    total = sum(g.count for g in groups)
    return {
        "date": day.isoformat(),
        "mode": "my" if order else "smart",
        "sunday_review": day.weekday() == 6 and cfg.sunday_review,
        "slot_time": get_kv(db, KV_SLOT, "18:00"),
        "max_cards": cfg.max_cards,
        "groups": [g.to_dict(cfg.minutes_per_card) for g in groups],
        "total_cards": total,
        "minutes": int(round(total * cfg.minutes_per_card)),
    }


def due_counts(db: Session, days: list[date], now: datetime | None = None) -> dict[date, tuple[int, list[tuple[str, int]]]]:
    """For the planner: per day, how many cards will be due and the biggest groups (name, cards). Today counts everything
    overdue; a later day counts cards due on that day only. Uses the max-cards limit."""
    now = now or datetime.now(timezone.utc)
    today = study_day(now)
    cfg = load_config(db)
    facts, _ = topic_facts(db, today)
    out: dict[date, tuple[int, list[tuple[str, int]]]] = {}
    cards = load_cards(db)
    for d in days:
        lo = start_of_day(d) if d > today else None
        hi = end_of_day(d)
        counts: dict[str, int] = {}
        total = 0
        new_left = cfg.new_per_day if d == today else 0
        for c in cards:
            qc = _queue_card(c)
            if q.is_due(qc, hi) is False:
                continue
            if fsrs6.is_new(qc.state):
                if new_left <= 0:
                    continue
                new_left -= 1
            elif lo is not None and qc.due_at is not None and qc.due_at < lo:
                continue
            name = facts[c.topic_id].title if c.topic_id in facts else (c.group or "Cards")
            counts[name] = counts.get(name, 0) + 1
            total += 1
        top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
        out[d] = (min(total, cfg.max_cards), top)
    return out


# ---------------------------------------------------------------------------------------------- grading on the server
def state_of(card: Card) -> dict | None:
    return card.fsrs_state_json or None


def grade_card(db: Session, card: Card, grade: int, at: datetime | None = None) -> dict:
    """Applies a grade (1..4) with FSRS-6: writes a Review row (state BEFORE the grade, so it can be undone) and updates the
    card's state and due time. Returns the new state."""
    at = at or datetime.now(timezone.utc)
    engine = get_engine(db)
    before = state_of(card)
    new_state = engine.review(before, grade, at)
    db.add(Review(card_id=card.id, grade=grade, reviewed_at=at, state_json=before))
    card.fsrs_state_json = new_state
    card.due_at = fsrs6.parse_time(new_state["due"])
    db.flush()
    return new_state


def intervals_for(db: Session, card: Card, at: datetime | None = None) -> dict[int, int]:
    return get_engine(db).next_intervals(state_of(card), at or datetime.now(timezone.utc))


# ---------------------------------------------------------------------------------------------- strength
def update_strength(db: Session, now: datetime | None = None, topic_ids: set[str] | None = None) -> dict:
    """Recomputes Topic.strength from card memory and moves the status UP when the rules say so. Returns counts."""
    now = now or datetime.now(timezone.utc)
    engine = get_engine(db)
    per_topic: dict[str, list[dict | None]] = {}
    for c in load_cards(db):
        if c.topic_id and (topic_ids is None or c.topic_id in topic_ids):
            per_topic.setdefault(c.topic_id, []).append(state_of(c))
    changed = upgraded = 0
    for tid, states in per_topic.items():
        topic = db.get(Topic, tid)
        if topic is None or topic.deleted:
            continue
        mem = topic_memory(engine, states, now)
        if mem is None:
            continue
        if abs((topic.strength or 0.0) - mem.strength) > 1e-6:
            topic.strength = float(mem.strength)
            changed += 1
        new_status = status_for(topic.status or "not_started", mem)
        if new_status != topic.status:
            topic.status = new_status
            upgraded += 1
    db.flush()
    return {"topics": len(per_topic), "strength_changed": changed, "status_upgraded": upgraded}


# ---------------------------------------------------------------------------------------------- snooze and order
def snooze_topic(db: Session, topic_id: str, now: datetime | None = None) -> dict:
    """Moves the topic's due cards to the next 2 to 3 days (within the max-cards limit) and hides it from today's queue."""
    now = now or datetime.now(timezone.utc)
    day = study_day(now)
    cfg = load_config(db)
    end = end_of_day(day)
    due = [c for c in load_cards(db) if c.topic_id == topic_id and q.is_due(_queue_card(c), end)]
    due.sort(key=lambda c: (as_utc(c.due_at) or now, c.id))
    offsets = q.snooze_offsets(len(due), cfg.max_cards)
    for card, off in zip(due, offsets, strict=True):
        target = day + timedelta(days=off)
        card.due_at = start_of_day(target) + timedelta(hours=6)  # 6 am India time on that day
    db.flush()
    return {"topic_id": topic_id, "moved": len(due), "days": sorted(set(offsets))}


def save_manual_order(db: Session, day: date, group_order: list[str]) -> RevisionOrder:
    row = db.scalar(select(RevisionOrder).where(RevisionOrder.date == day.isoformat(), RevisionOrder.deleted.is_(False)))
    if row is None:
        row = RevisionOrder(date=day.isoformat(), group_order=list(group_order))
        db.add(row)
    else:
        row.group_order = list(group_order)
    db.flush()
    return row


def clear_manual_order(db: Session, day: date) -> int:
    n = 0
    for row in db.scalars(select(RevisionOrder).where(RevisionOrder.date == day.isoformat(), RevisionOrder.deleted.is_(False))):
        row.deleted = True
        n += 1
    db.flush()
    return n


def sunday_review(db: Session, now: datetime | None = None) -> dict:
    """The cards of the full-week review: everything due plus fading cards of the week (see queue.sunday_extra_ids)."""
    now = now or datetime.now(timezone.utc)
    day = study_day(now)
    engine = get_engine(db)
    qcards = [_queue_card(c) for c in load_cards(db)]
    extra = q.sunday_extra_ids(engine, qcards, now, end_of_day(day))
    reviewed_week = sum(
        1 for c in qcards if c.state and c.state.get("last_review") and (now - fsrs6.parse_time(c.state["last_review"])).days <= 7
    )
    return {"date": day.isoformat(), "is_sunday": day.weekday() == 6, "card_ids": extra, "cards_reviewed_this_week": reviewed_week}
