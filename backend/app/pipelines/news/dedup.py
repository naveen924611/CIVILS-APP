"""Removes repeats: same link (after cleaning tracking bits) or nearly the same headline."""
import re
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

from app.pipelines.news.feeds import RawEntry

_TRACKING = {"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid", "fbclid", "ref", "ref_src"}
_STOP = {"the", "and", "for", "with", "from", "that", "this", "are", "has", "have", "will", "its", "into", "over", "after", "amid"}


def normalize_url(url: str) -> str:
    p = urlparse(url.strip())
    query = [(k, v) for k, v in parse_qsl(p.query) if k.lower() not in _TRACKING]
    host = p.netloc.lower().removeprefix("www.")
    path = p.path.rstrip("/") or "/"
    return urlunparse((p.scheme.lower(), host, path, "", urlencode(query), ""))


def title_tokens(title: str) -> frozenset[str]:
    words = re.findall(r"[a-z0-9]+", title.lower())
    return frozenset(w for w in words if len(w) > 2 and w not in _STOP)


def similar(a: frozenset[str], b: frozenset[str], threshold: float = 0.6) -> bool:
    if not a or not b:
        return False
    return len(a & b) / len(a | b) >= threshold


def dedupe(
    entries: list[RawEntry],
    seen_urls: set[str],
    seen_titles: list[frozenset[str]],
) -> list[RawEntry]:
    """Keeps the highest-priority copy of every story. `seen_*` hold what we already stored earlier."""
    urls = set(seen_urls)
    titles = list(seen_titles)
    kept = []
    for e in sorted(entries, key=lambda x: x.priority):
        nu = normalize_url(e.url)
        toks = title_tokens(e.title)
        if nu in urls or any(similar(toks, t) for t in titles):
            continue
        urls.add(nu)
        titles.append(toks)
        kept.append(e)
    return kept
