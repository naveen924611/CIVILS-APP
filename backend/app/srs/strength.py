"""Topic strength and automatic status upgrades, computed from the memory state of the topic's cards.

The tablet computes the same numbers (android srs/Strength.kt); both are checked against data/fsrs_vectors.json
("strength_cases"), so keep the two files identical when you change a formula.

For one card with a state:      memory = retrievability(now) * min(1, stability / 21)
  (how likely you are to recall it now, discounted when the memory is still young; 21 days counts as "durable")
A card that was never reviewed counts 0.
Topic strength (0..1)         = sum(memory of all cards) / number of cards          (only for topics that have cards)
coverage                      = share of cards reviewed at least once
repeat_share                  = share of cards reviewed at least twice

Status only moves UP (never down), and only from these rules:
    strong       coverage >= 0.8 and strength >= 0.70
    revised      coverage >= 0.6 and repeat_share >= 0.5 and strength >= 0.30
    studied      coverage >= 0.6
    in_progress  coverage > 0
The owner can always set any status by hand (Syllabus map); a hand-set higher status is never lowered.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from app.srs.fsrs6 import Fsrs6, is_new

STABILITY_DURABLE_DAYS = 21.0
STATUS_ORDER = ["not_started", "in_progress", "studied", "revised", "strong"]


@dataclass(frozen=True)
class TopicMemory:
    strength: float
    coverage: float
    repeat_share: float
    cards: int


def card_memory(engine: Fsrs6, state: dict | None, now: datetime) -> float:
    if is_new(state):
        return 0.0
    assert state is not None
    return engine.retrievability(state, now) * min(1.0, float(state["stability"]) / STABILITY_DURABLE_DAYS)


def topic_memory(engine: Fsrs6, states: list[dict | None], now: datetime) -> TopicMemory | None:
    """None when the topic has no cards."""
    n = len(states)
    if n == 0:
        return None
    total = sum(card_memory(engine, s, now) for s in states)
    reviewed = sum(1 for s in states if not is_new(s))
    repeated = sum(1 for s in states if not is_new(s) and int((s or {}).get("reps") or 0) >= 2)
    return TopicMemory(strength=total / n, coverage=reviewed / n, repeat_share=repeated / n, cards=n)


def status_for(current: str, mem: TopicMemory) -> str:
    """The status after applying the upgrade rules to `current` (never lower than `current`)."""
    if mem.coverage >= 0.8 and mem.strength >= 0.70:
        wanted = "strong"
    elif mem.coverage >= 0.6 and mem.repeat_share >= 0.5 and mem.strength >= 0.30:
        wanted = "revised"
    elif mem.coverage >= 0.6:
        wanted = "studied"
    elif mem.coverage > 0:
        wanted = "in_progress"
    else:
        wanted = "not_started"
    cur = STATUS_ORDER.index(current) if current in STATUS_ORDER else 0
    return STATUS_ORDER[max(cur, STATUS_ORDER.index(wanted))]
