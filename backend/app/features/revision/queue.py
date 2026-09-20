"""Revision queue rules (spec 6.15 and 7.5) as plain functions: no database, no clock. The tablet has the same
rules in android srs/RevisionQueue.kt; keep the two files in step.

A queue is a list of GROUPS (one per topic; cards without a topic are grouped by their `group` name, for example
"Current affairs"). Each group has cards that are due, and cards that were never reviewed (new).

Smart order ranks groups by
    priority = w1*(1 - retrievability) + w2*weakness + w3*importance + w4*exam_proximity
with w = 0.4, 0.25, 0.2, 0.15 (KV `revision.weights` may replace them). All four parts are 0..1.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from app.srs.fsrs6 import Fsrs6, is_new, parse_time

DEFAULT_WEIGHTS = (0.4, 0.25, 0.2, 0.15)
DEFAULT_MAX_CARDS = 80
DEFAULT_NEW_PER_DAY = 20
SUNDAY_EXTRA_CAP = 40
SUNDAY_RETRIEVABILITY = 0.9
DEFAULT_MINUTES_PER_CARD = 0.7

REASON_WEAK = "Weak"
REASON_DUE = "Due today"
REASON_FADING = "Fading"
REASON_BRIEFS = "From your briefs"
REASON_PAPERS = "Often in past papers"
REASON_SUNDAY = "Sunday review"
REASON_NEW = "New cards"
REASON_PINNED = "Pinned subject"


@dataclass
class QueueCard:
    """What the queue needs to know about one card."""

    id: str
    topic_id: str | None
    group: str  # subject name or "Current affairs"
    state: dict | None
    due_at: datetime | None


@dataclass
class TopicFacts:
    """Facts about a topic that the ranking uses (0..1 scales; unknown = 0)."""

    id: str
    title: str
    subject: str = ""
    strength: float = 0.0
    importance: float = 0.0  # 0..1 (topic importance 0..10 divided by 10)
    exam_proximity: float = 0.0  # 0..1, 1 = the exam this topic belongs to is very near


@dataclass
class RevisionConfig:
    max_cards: int = DEFAULT_MAX_CARDS
    new_per_day: int = DEFAULT_NEW_PER_DAY
    weights: tuple[float, float, float, float] = DEFAULT_WEIGHTS
    pinned_subject: str | None = None
    daily_group: str | None = None  # a group whose cards come every day (e.g. "Current affairs")
    daily_group_cards: int = 10
    sunday_review: bool = True
    minutes_per_card: float = DEFAULT_MINUTES_PER_CARD


@dataclass
class QueueGroup:
    key: str  # topic id, or "g:<group name>" for cards without a topic
    topic_id: str | None
    title: str
    subject: str
    reason: str
    priority: float
    card_ids: list[str] = field(default_factory=list)
    due: int = 0
    new: int = 0
    extra: int = 0  # Sunday review cards (not due yet)

    @property
    def count(self) -> int:
        return len(self.card_ids)

    def minutes(self, per_card: float) -> int:
        return max(1, int(round(self.count * per_card))) if self.card_ids else 0

    def to_dict(self, per_card: float = DEFAULT_MINUTES_PER_CARD) -> dict:
        return {
            "key": self.key, "topic_id": self.topic_id, "title": self.title, "subject": self.subject,
            "reason": self.reason, "priority": round(self.priority, 4), "cards": self.count, "due": self.due,
            "new": self.new, "extra": self.extra, "minutes": self.minutes(per_card), "card_ids": list(self.card_ids),
        }


def group_key(card: QueueCard) -> str:
    return card.topic_id if card.topic_id else f"g:{card.group or 'Cards'}"


def is_due(card: QueueCard, end_of_day: datetime) -> bool:
    """A card is due when its due time is at or before the end of the study day. A never-reviewed card has its creation
    time as due time, so it is due at once (unless it was snoozed to a later day)."""
    due = card.due_at
    if due is None and card.state and card.state.get("due"):
        due = parse_time(card.state["due"])
    return due is None or due <= end_of_day


def retrievability_of(engine: Fsrs6, card: QueueCard, now: datetime) -> float:
    return engine.retrievability(card.state, now)


def priority(weights: tuple[float, float, float, float], retr: float, weakness: float, importance: float, proximity: float) -> float:
    w1, w2, w3, w4 = weights
    return w1 * (1 - retr) + w2 * weakness + w3 * importance + w4 * proximity


def _reason(retr_avg: float, weakness: float, importance: float, topic: TopicFacts | None, due: int, reviewed: int) -> str:
    if topic is None:
        return REASON_BRIEFS
    if reviewed and weakness >= 0.6:
        return REASON_WEAK
    if reviewed and retr_avg < 0.75:
        return REASON_FADING
    if importance >= 0.7:
        return REASON_PAPERS
    if due:
        return REASON_DUE
    return REASON_NEW


def sunday_extra_ids(engine: Fsrs6, cards: list[QueueCard], now: datetime, end_of_day: datetime) -> list[str]:
    """Full-week review: cards reviewed in the last 7 days that are not due yet but are already fading (recall below
    90 percent), weakest first, at most 40."""
    picked: list[tuple[float, str]] = []
    for c in cards:
        if is_new(c.state) or is_due(c, end_of_day) or not c.state or not c.state.get("last_review"):
            continue
        last = parse_time(c.state["last_review"])
        if (now - last).days > 7:
            continue
        r = engine.retrievability(c.state, now)
        if r < SUNDAY_RETRIEVABILITY:
            picked.append((r, c.id))
    picked.sort()
    return [cid for _, cid in picked[:SUNDAY_EXTRA_CAP]]


def build_groups(
    engine: Fsrs6,
    cards: list[QueueCard],
    topics: dict[str, TopicFacts],
    now: datetime,
    end_of_day: datetime,
    cfg: RevisionConfig,
    *,
    is_sunday: bool = False,
    snoozed_topics: set[str] | None = None,
) -> list[QueueGroup]:
    """Smart-ordered groups for the day, before the max-cards cut. Cards inside a group: due first (most overdue first),
    then Sunday extras, then new cards."""
    snoozed = snoozed_topics or set()
    extra_ids = set(sunday_extra_ids(engine, cards, now, end_of_day)) if (is_sunday and cfg.sunday_review) else set()
    by_group: dict[str, list[QueueCard]] = {}
    for c in cards:
        if c.topic_id and c.topic_id in snoozed:
            continue
        if is_due(c, end_of_day) or c.id in extra_ids:
            by_group.setdefault(group_key(c), []).append(c)

    out: list[QueueGroup] = []
    for key, members in by_group.items():
        topic = topics.get(members[0].topic_id) if members[0].topic_id else None
        reviewed = [c for c in members if not is_new(c.state)]
        due_cards = [c for c in members if c.id not in extra_ids and is_due(c, end_of_day)]
        retr_avg = (sum(retrievability_of(engine, c, now) for c in reviewed) / len(reviewed)) if reviewed else 1.0
        weakness = 0.0
        importance = 0.0
        proximity = 0.0
        if topic is not None:
            weakness = 1.0 - min(max(topic.strength, 0.0), 1.0) if reviewed else 0.5
            importance = min(max(topic.importance, 0.0), 1.0)
            proximity = min(max(topic.exam_proximity, 0.0), 1.0)
        else:
            weakness = 0.3  # current affairs and other loose cards: a modest, steady pull
        reason = REASON_SUNDAY if (extra_ids and not any(c.id not in extra_ids for c in members) and reviewed) else _reason(
            retr_avg, weakness, importance, topic, len(due_cards), len(reviewed)
        )

        def order(c: QueueCard):
            new = is_new(c.state)
            overdue = c.due_at.timestamp() if (c.due_at is not None) else 0.0
            return (new, c.id in extra_ids, overdue, c.id)

        ordered = sorted(members, key=order)
        title = topic.title if topic else members[0].group
        out.append(QueueGroup(
            key=key, topic_id=members[0].topic_id, title=title, subject=(topic.subject if topic else members[0].group),
            reason=reason, priority=priority(cfg.weights, retr_avg, weakness, importance, proximity),
            card_ids=[c.id for c in ordered], due=len(due_cards) - sum(1 for c in due_cards if is_new(c.state)),
            new=sum(1 for c in members if is_new(c.state)), extra=sum(1 for c in members if c.id in extra_ids),
        ))
    out.sort(key=lambda g: (-g.priority, g.title.lower(), g.key))
    return out


def apply_rules(groups: list[QueueGroup], cfg: RevisionConfig, all_cards: dict[str, QueueCard] | None = None) -> list[QueueGroup]:
    """Rules engine: pinned subject first, the daily group present with at least its share, new cards limited per day,
    then the max cards per day cut (from the bottom of the order). Returns new group objects (inputs are not changed)."""
    groups = [QueueGroup(**{**g.__dict__, "card_ids": list(g.card_ids)}) for g in groups]

    # 1. new cards per day: keep the first `new_per_day` new cards in the current order
    if cfg.new_per_day >= 0:
        budget = cfg.new_per_day
        for g in groups:
            keep: list[str] = []
            new_left = g.new
            for cid in g.card_ids:
                card = (all_cards or {}).get(cid)
                is_n = card is not None and is_new(card.state)
                if is_n:
                    if budget <= 0:
                        new_left -= 1
                        continue
                    budget -= 1
                keep.append(cid)
            g.card_ids = keep
            g.new = max(new_left, 0)
        groups = [g for g in groups if g.card_ids]

    # 2. pinned subject first (keeps the smart order inside the pinned part and inside the rest)
    pinned_count = 0
    if cfg.pinned_subject:
        pin = cfg.pinned_subject.strip().lower()
        pinned = [g for g in groups if g.subject.strip().lower() == pin or g.title.strip().lower() == pin]
        rest = [g for g in groups if g not in pinned]
        for g in pinned:
            g.reason = g.reason if g.reason in (REASON_WEAK, REASON_FADING) else REASON_PINNED
        groups = pinned + rest
        pinned_count = len(pinned)

    # 3. a daily group (for example current affairs): its first N cards always make the cut, and it is moved up to
    #    just after the pinned groups so that the max-cards cut never removes it
    if cfg.daily_group:
        name = cfg.daily_group.strip().lower()
        daily = [g for g in groups if g.key.lower() == f"g:{name}" or g.title.strip().lower() == name]
        if daily:
            for g in daily:
                g.card_ids = g.card_ids[: max(cfg.daily_group_cards, 0) or len(g.card_ids)]
                g.due = min(g.due, len(g.card_ids))
            others = [g for g in groups if g not in daily]
            head = min(pinned_count, len(others))
            groups = others[:head] + daily + others[head:]

    return cut_to_max(groups, cfg.max_cards)


def cut_to_max(groups: list[QueueGroup], max_cards: int) -> list[QueueGroup]:
    """Keeps the first `max_cards` cards in queue order; groups that end up empty are dropped."""
    left = max(max_cards, 0)
    out: list[QueueGroup] = []
    for g in groups:
        if left <= 0:
            break
        if len(g.card_ids) > left:
            g.card_ids = g.card_ids[:left]
            g.due = min(g.due, len(g.card_ids))
            g.new = min(g.new, len(g.card_ids))
            g.extra = min(g.extra, len(g.card_ids))
        left -= len(g.card_ids)
        if g.card_ids:
            out.append(g)
    return out


def apply_manual_order(groups: list[QueueGroup], order: list[str]) -> list[QueueGroup]:
    """'My order': groups named in `order` (by key) come first, in that order; new groups follow in smart order."""
    pos = {k: i for i, k in enumerate(order)}
    known = sorted((g for g in groups if g.key in pos), key=lambda g: pos[g.key])
    return known + [g for g in groups if g.key not in pos]


def snooze_offsets(count: int, max_per_day: int = DEFAULT_MAX_CARDS, spread_days: int = 3) -> list[int]:
    """Day offsets (1 = tomorrow) for the cards of a snoozed topic: spread evenly over the next 2 to 3 days without
    putting more than `max_per_day` cards on one day. [] for no cards."""
    if count <= 0:
        return []
    days = max(1, min(spread_days, count))
    days = max(days, -(-count // max(max_per_day, 1)))  # ceiling: more days if a day would overflow
    return [1 + (i * days) // count for i in range(count)]
