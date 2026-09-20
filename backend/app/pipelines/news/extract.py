"""Fetches an article only to give the AI something to read. The text is NOT stored."""
import logging

import httpx
import trafilatura

from app.pipelines.news.feeds import USER_AGENT, Robots

log = logging.getLogger(__name__)
MAX_CHARS = 6000


def fetch_article_text(client: httpx.Client, robots: Robots, url: str) -> str | None:
    if not robots.allowed(url):
        return None
    try:
        r = client.get(url, headers={"User-Agent": USER_AGENT}, timeout=25, follow_redirects=True)
        r.raise_for_status()
    except httpx.HTTPError:
        return None
    text = trafilatura.extract(r.text, include_comments=False, include_tables=False)
    return text[:MAX_CHARS] if text else None
