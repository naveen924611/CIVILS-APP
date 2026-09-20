"""The news pipeline: fetch -> remove repeats -> pick -> read -> AI summary -> save.

Stores only the summary, metadata and link. Items with relevance under 5 for both exams are hidden
(still searchable). Items scoring 7 or more make up to 3 flashcards for the Current affairs group.
"""
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import Card, NewsItem
from app.db.util import as_utc
from app.llm import promptlib
from app.llm.gateway import LlmGateway
from app.llm.schemas import NewsItemOut
from app.pipelines.news.dedup import dedupe, normalize_url, title_tokens
from app.pipelines.news.extract import fetch_article_text
from app.pipelines.news.feeds import RawEntry, Robots, fetch_feed, load_feeds
from app.pipelines.news.select import select_entries

log = logging.getLogger(__name__)
PROMPT = "news_item_v1"
MIN_TEXT_CHARS = 150


@dataclass
class PipelineStats:
    fetched: int = 0
    new: int = 0
    selected: int = 0
    created: int = 0
    skipped: int = 0
    hidden: int = 0
    cards: int = 0
    level: int = 0


def build_prompt(entry: RawEntry, article: str, level: int) -> tuple[str, str]:
    system, user_tpl = promptlib.load(PROMPT)
    words = "60 to 100" if level >= 2 else "120 to 180"
    mcq = 'Set "mcqs" to an empty list.' if level >= 1 else 'Write 2 or 3 multiple-choice questions in "mcqs".'
    user = promptlib.render(
        user_tpl,
        summary_words=words,
        mcq_instruction=mcq,
        source=entry.source,
        published=entry.published.date().isoformat() if entry.published else "unknown",
        title=entry.title,
        article=article,
    )
    return system, user


def save_item(db: Session, entry: RawEntry, out: NewsItemOut, now: datetime) -> tuple[NewsItem, int]:
    hidden = out.relevance_upsc < 5 and out.relevance_appsc < 5
    item = NewsItem(
        url=entry.url, source=entry.source, title=out.title, summary=out.summary.strip(),
        relevance_upsc=out.relevance_upsc, relevance_appsc=out.relevance_appsc,
        papers=out.papers, prelims_facts=[f.model_dump() for f in out.prelims_facts],
        mains_angle=out.mains_angle, keywords=out.keywords, is_ap_specific=out.is_ap_specific,
        mcqs=[m.model_dump() for m in out.mcqs], hidden=hidden,
        published_at=entry.published, fetched_at=now,
    )
    db.add(item)
    db.flush()
    cards = 0
    if max(out.relevance_upsc, out.relevance_appsc) >= 7:
        for fact in out.prelims_facts[:3]:
            db.add(Card(front=fact.q, back=fact.a, source_type="news", source_id=item.id, group="Current affairs"))
            cards += 1
    db.commit()
    return item, cards


def run_news_pipeline(
    db: Session,
    gateway: LlmGateway,
    client: httpx.Client,
    settings: Settings,
    now: datetime | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> PipelineStats:
    now = now or datetime.now(timezone.utc)
    stats = PipelineStats(level=gateway.guard.level())
    robots = Robots(client)

    entries: list[RawEntry] = []
    for cfg in load_feeds(settings.feeds_file):
        entries += fetch_feed(client, robots, cfg)
    stats.fetched = len(entries)

    recent = db.execute(
        select(NewsItem.url, NewsItem.title).where(NewsItem.fetched_at >= now - timedelta(days=14))
    ).all()
    seen_urls = {normalize_url(u) for u, _ in recent}
    seen_titles = [title_tokens(t) for _, t in recent]
    fresh = dedupe(entries, seen_urls, seen_titles)
    stats.new = len(fresh)

    chosen = select_entries(fresh, settings.news_max_articles_per_run, settings.news_window_hours, now)
    stats.selected = len(chosen)

    for entry in chosen:
        article = fetch_article_text(client, robots, entry.url) or entry.summary
        if len(article) < MIN_TEXT_CHARS:
            log.info("skipped (too little text): %s", entry.title[:60])
            stats.skipped += 1
            continue
        level = gateway.guard.level()
        system, user = build_prompt(entry, article, level)
        out = gateway.generate_json(feature="brief_item", system=system, user=user, schema=NewsItemOut)
        if out is None:
            log.warning("skipped (AI failed: %s): %s", gateway.last_error, entry.title[:60])
            stats.skipped += 1
            continue
        if db.scalar(select(NewsItem.id).where(NewsItem.url == entry.url)):
            continue
        item, cards = save_item(db, entry, out, now)
        stats.created += 1
        stats.hidden += int(item.hidden)
        stats.cards += cards
        sleep(0.5)
    stats.level = gateway.guard.level()
    return stats


def recent_unbriefed(db: Session, limit: int) -> list[NewsItem]:
    """Visible items not yet put in any brief, best first."""
    rows = db.scalars(
        select(NewsItem).where(NewsItem.hidden.is_(False), NewsItem.brief_id.is_(None), NewsItem.deleted.is_(False))
    ).all()
    rows.sort(key=lambda i: (max(i.relevance_upsc, i.relevance_appsc), as_utc(i.published_at) or as_utc(i.fetched_at)), reverse=True)
    return rows[:limit]
