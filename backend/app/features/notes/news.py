"""'In the news' (spec 6.6, 9.4): matches recent news / brief items to syllabus topics without using the AI.

A news item is tied to a topic when the topic title (or one of its note keywords) appears in the item's title, keywords
or summary. The matches are stored on `NewsItem.topic_ids` and copied into `Note.sections["in_the_news"]`
(`[{id, title, summary, url, source, published_at}]`) so the tablet never has to read the news table for a topic.
"""
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import NewsItem
from app.db.models_v2 import Note, Topic
from app.db.util import as_utc, iso
from app.features.notes.text import norm, short

WINDOW_DAYS = 45
MAX_PER_TOPIC = 12
MAX_TOPICS_PER_ITEM = 3
THRESHOLD = 3.0
_GENERIC = frozenset(
    "introduction overview basic basics concept concepts general others other miscellaneous topic topics paper syllabus "
    "study meaning features importance role types type development history recent issues issue and the of in for "
    "with from their its into other related aspects aspect problems problem".split()
)


@dataclass
class TopicTerms:
    id: str
    level: int
    title_norm: str
    title_tokens: frozenset
    keywords: list[str] = field(default_factory=list)


def _tokens(text: str) -> set[str]:
    return {w for w in norm(text).split() if len(w) >= 4 and w not in _GENERIC}


def build_terms(db: Session) -> list[TopicTerms]:
    """Terms for every approved topic below paper level (a paper heading like 'GS Paper II' would match everything)."""
    topics = list(db.scalars(select(Topic).where(Topic.deleted.is_(False), Topic.approved.is_(True), Topic.level >= 1)))
    kw: dict[str, list[str]] = {}
    for note in db.scalars(select(Note).where(Note.deleted.is_(False))):
        words = [norm(str(k)) for k in (note.sections or {}).get("keywords", []) or []]
        kw[note.topic_id] = [w for w in words if len(w) >= 4]
    has_children = {t.parent_id for t in topics if t.parent_id}
    out: list[TopicTerms] = []
    for t in topics:
        tokens = _tokens(t.title)
        if not tokens:
            continue
        # a subject (level 1) with children is too broad to match on its own words; its notes still list its own keywords
        if t.level == 1 and t.id in has_children:
            continue
        out.append(TopicTerms(t.id, t.level, norm(t.title), frozenset(tokens), kw.get(t.id, [])))
    return out


def score(text_norm: str, tokens: set[str], t: TopicTerms) -> float:
    padded = f" {text_norm} "
    s = 0.0
    if len(t.title_norm) >= 4 and f" {t.title_norm} " in padded:
        s += 3.0
    if len(t.title_tokens) >= 2 and t.title_tokens <= tokens:
        s += 2.0
    elif len(t.title_tokens) == 1 and next(iter(t.title_tokens)) in tokens:
        s += 1.0
    for word in t.keywords:
        if f" {word} " in padded:
            s += 3.0 if " " in word else 1.5  # a phrase like 'sarkaria commission' is a strong sign; one word is a weak one
    return s


def best_topics(text: str, terms: list[TopicTerms], limit: int = MAX_TOPICS_PER_ITEM, threshold: float = THRESHOLD) -> list[tuple[str, float]]:
    text_norm = norm(text)
    tokens = _tokens(text)
    scored = [(t.id, score(text_norm, tokens, t) + 0.01 * t.level) for t in terms]
    scored = [(i, s) for i, s in scored if s >= threshold]
    scored.sort(key=lambda p: p[1], reverse=True)
    return scored[:limit]


def best_topic_for_text(db: Session, text: str) -> Topic | None:
    """For a capture that has no topic: the most likely topic (a lower bar than for news), or None."""
    terms = build_terms(db)
    hits = best_topics(text, terms, limit=1, threshold=2.0)
    return db.get(Topic, hits[0][0]) if hits else None


def _item_text(item: NewsItem) -> str:
    return " ".join([item.title or "", " ".join(str(k) for k in item.keywords or []), item.summary or ""])


def _card(item: NewsItem) -> dict:
    return {
        "id": item.id, "title": short(item.title, 200), "summary": short(item.summary, 400), "url": item.url,
        "source": item.source, "published_at": iso(item.published_at or item.fetched_at),
    }


def refresh(db: Session, now: datetime | None = None) -> dict:
    """Matches the last WINDOW_DAYS days of news to topics and rewrites the 'in the news' list of every affected note.
    Returns {"items": n, "matched": n, "notes": n}. Safe to run any time."""
    now = now or datetime.now(timezone.utc)
    since = now - timedelta(days=WINDOW_DAYS)
    terms = build_terms(db)
    items = [
        i for i in db.scalars(select(NewsItem).where(NewsItem.deleted.is_(False), NewsItem.hidden.is_(False)))
        if (as_utc(i.published_at or i.fetched_at) or now) >= since
    ]
    per_topic: dict[str, list[NewsItem]] = {}
    matched = 0
    for item in items:
        hits = best_topics(_item_text(item), terms) if terms else []
        ids = [h[0] for h in hits]
        if ids != list(item.topic_ids or []):
            item.topic_ids = ids
        if ids:
            matched += 1
        for tid in ids:
            per_topic.setdefault(tid, []).append(item)
    changed = 0
    for note in db.scalars(select(Note).where(Note.deleted.is_(False))):
        picked = sorted(per_topic.get(note.topic_id, []), key=lambda i: as_utc(i.published_at or i.fetched_at) or now, reverse=True)
        cards = [_card(i) for i in picked[:MAX_PER_TOPIC]]
        sections = dict(note.sections or {})
        if sections.get("in_the_news", []) != cards and (cards or sections.get("in_the_news")):
            sections["in_the_news"] = cards
            note.sections = sections
            changed += 1
    db.commit()
    return {"items": len(items), "matched": matched, "notes": changed}
