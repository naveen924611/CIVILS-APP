"""Importance (0 to 10) of a topic (spec 7.1): paper weight + exam tags + past-paper frequency (recency weighted) + news boost.

The first guess (from the starter outline or the tree the owner approved) is remembered in KV `syllabus.importance_prior`
({topic_id: 0-10}) so that recomputing is repeatable and never drifts.
"""
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.kv import get_kv, set_kv
from app.db.models import NewsItem
from app.db.models_v2 import Pyq, Topic
from app.db.util import as_utc

PRIOR_KEY = "syllabus.importance_prior"
RECENCY_DECAY = 0.85  # a paper one year older counts 85 percent as much

# (word found in the paper title, weight 0-10); first match wins
PAPER_WEIGHTS: list[tuple[str, float]] = [
    ("qualifying", 2.0), ("telugu", 2.0), ("english", 2.5), ("indian language", 2.0), ("csat", 3.5), ("aptitude", 4.0),
    ("essay", 6.0), ("ethics", 7.0), ("polity", 8.0), ("governance", 8.0), ("economy", 8.0), ("history", 7.5),
    ("general studies", 8.0), ("gs paper", 8.0), ("gs ", 8.0), ("science", 7.0), ("paper", 6.0),
]


def paper_weight(title: str) -> float:
    low = (title or "").lower()
    for word, weight in PAPER_WEIGHTS:
        if word in low:
            return weight
    return 5.0


def score(prior: float | None, paper_w: float, tags: list[str], pyq_score: float | None, news_count: int) -> float:
    """Pure formula. pyq_score is 0-10 (share of the most-asked topic) or None when no past papers exist."""
    base = paper_w if prior is None else prior
    blend = 0.6 * base + 0.4 * paper_w
    if len(set(tags)) >= 2:  # shared by both exams
        blend += 0.5
    if pyq_score is not None:
        value = 0.35 * blend + 0.65 * pyq_score
    else:
        value = blend
    value += min(1.0, 0.25 * news_count)
    return round(max(0.0, min(10.0, value)), 1)


def _descendants(children: dict[str | None, list[str]], topic_id: str) -> list[str]:
    out, stack = [topic_id], [topic_id]
    while stack:
        for kid in children.get(stack.pop(), []):
            out.append(kid)
            stack.append(kid)
    return out


def recompute(db: Session, today: datetime | None = None) -> dict:
    """Recomputes Topic.importance for every approved topic. Returns {"topics": n, "with_pyq": n, "changed": n}."""
    now = today or datetime.now(timezone.utc)
    topics = list(db.scalars(select(Topic).where(Topic.deleted.is_(False), Topic.approved.is_(True))))
    if not topics:
        return {"topics": 0, "with_pyq": 0, "changed": 0}
    by_id = {t.id: t for t in topics}
    children: dict[str | None, list[str]] = {}
    for t in topics:
        children.setdefault(t.parent_id, []).append(t.id)
    prior_map: dict = get_kv(db, PRIOR_KEY, {}) or {}

    paper_of: dict[str, float] = {}
    for t in topics:
        paper_of[t.id] = paper_weight(t.paper or t.title)

    weight_by_topic: dict[str, float] = {}
    for q in db.scalars(select(Pyq).where(Pyq.deleted.is_(False))):
        age = max(0, now.year - int(q.year or now.year))
        w = RECENCY_DECAY ** age
        for tid in q.topic_ids or []:
            weight_by_topic[tid] = weight_by_topic.get(tid, 0.0) + w
    total_weight: dict[str, float] = {}
    for t in topics:
        total_weight[t.id] = sum(weight_by_topic.get(d, 0.0) for d in _descendants(children, t.id))
    peak = max(total_weight.values(), default=0.0)

    since = now - timedelta(days=30)
    news_by_topic: dict[str, int] = {}
    for item in db.scalars(select(NewsItem).where(NewsItem.deleted.is_(False), NewsItem.hidden.is_(False))):
        stamp = as_utc(item.published_at or item.fetched_at)
        if stamp is not None and stamp < since:
            continue
        for tid in item.topic_ids or []:
            news_by_topic[tid] = news_by_topic.get(tid, 0) + 1

    changed = 0
    with_pyq = 0
    for t in topics:
        prior = prior_map.get(t.id)
        if prior is None:  # inherit from the closest ancestor that has one
            parent = by_id.get(t.parent_id or "")
            while parent is not None and prior is None:
                prior = prior_map.get(parent.id)
                parent = by_id.get(parent.parent_id or "")
        pyq = None
        if peak > 0:
            pyq = round(10.0 * total_weight[t.id] / peak, 2)
            if total_weight[t.id] > 0:
                with_pyq += 1
        value = score(prior, paper_of[t.id], list(t.exam_tags or []), pyq, news_by_topic.get(t.id, 0))
        if abs((t.importance or 0.0) - value) > 1e-9:
            t.importance = value
            changed += 1
    db.commit()
    return {"topics": len(topics), "with_pyq": with_pyq, "changed": changed}


def remember_priors(db: Session, priors: dict[str, float]) -> None:
    if not priors:
        return
    current = dict(get_kv(db, PRIOR_KEY, {}) or {})
    current.update({k: round(float(v), 2) for k, v in priors.items()})
    set_kv(db, PRIOR_KEY, current)
