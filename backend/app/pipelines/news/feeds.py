"""Reads the feed list, checks robots.txt, downloads and parses feeds."""
import calendar
import html
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.robotparser import RobotFileParser

import feedparser
import httpx
import yaml

log = logging.getLogger(__name__)
USER_AGENT = "CivilsCompanionBot/0.1 (personal study app; summaries and links only)"


@dataclass
class FeedConfig:
    name: str
    url: str
    lang: str = "en"
    priority: int = 2
    enabled: bool = True


@dataclass
class RawEntry:
    url: str
    title: str
    summary: str
    source: str
    lang: str
    priority: int
    published: datetime | None = None
    extra: dict = field(default_factory=dict)


def load_feeds(path: str | Path) -> list[FeedConfig]:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    feeds = []
    for f in data.get("feeds", []):
        feeds.append(
            FeedConfig(
                name=f["name"], url=f["url"], lang=f.get("lang", "en"),
                priority=int(f.get("priority", 2)), enabled=bool(f.get("enabled", True)),
            )
        )
    return [f for f in feeds if f.enabled]


class Robots:
    """Cached robots.txt checks. If robots.txt cannot be read we allow, as web crawlers normally do."""

    def __init__(self, client: httpx.Client):
        self._client = client
        self._cache: dict[str, RobotFileParser | None] = {}

    def allowed(self, url: str) -> bool:
        p = urlparse(url)
        origin = f"{p.scheme}://{p.netloc}"
        if origin not in self._cache:
            rp = RobotFileParser()
            try:
                r = self._client.get(origin + "/robots.txt", headers={"User-Agent": USER_AGENT}, timeout=15)
                if r.status_code == 200:
                    rp.parse(r.text.splitlines())
                    self._cache[origin] = rp
                else:
                    self._cache[origin] = None
            except httpx.HTTPError:
                self._cache[origin] = None
        rp = self._cache[origin]
        return True if rp is None else rp.can_fetch(USER_AGENT, url)


_TAG = re.compile(r"<[^>]+>")


def clean_text(raw: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(_TAG.sub(" ", raw or ""))).strip()


def _entry_time(e) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        t = e.get(key)
        if t:
            return datetime.fromtimestamp(calendar.timegm(t), tz=timezone.utc)
    return None


def parse_feed(content: bytes | str, cfg: FeedConfig) -> list[RawEntry]:
    parsed = feedparser.parse(content)
    out = []
    for e in parsed.entries:
        url = (e.get("link") or "").strip()
        title = clean_text(e.get("title", ""))
        if not url or not title:
            continue
        out.append(
            RawEntry(
                url=url, title=title, summary=clean_text(e.get("summary", "")),
                source=cfg.name, lang=cfg.lang, priority=cfg.priority, published=_entry_time(e),
            )
        )
    return out


def fetch_feed(client: httpx.Client, robots: Robots, cfg: FeedConfig) -> list[RawEntry]:
    if not robots.allowed(cfg.url):
        log.warning("robots.txt does not allow %s; skipped", cfg.name)
        return []
    try:
        r = client.get(cfg.url, headers={"User-Agent": USER_AGENT}, timeout=25, follow_redirects=True)
        r.raise_for_status()
    except httpx.HTTPError as exc:
        log.warning("feed %s failed: %s", cfg.name, type(exc).__name__)
        return []
    return parse_feed(r.content, cfg)
