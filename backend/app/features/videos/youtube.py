"""Talking to YouTube: oEmbed (no key) for title and channel, the Data API v3 only when YOUTUBE_API_KEY is set.

Nothing here downloads a video. The API key is read from the environment, sent in a header (never in the URL, so it
cannot appear in request logs) and never logged.
"""
import logging
import os
import time
from dataclasses import dataclass, field

import httpx

from .ids import parse_iso_duration, watch_url

log = logging.getLogger(__name__)

OEMBED_URL = "https://www.youtube.com/oembed"
API_BASE = "https://www.googleapis.com/youtube/v3"
CACHE_SECONDS = 24 * 3600
CACHE_MAX = 200


def api_key() -> str:
    return os.environ.get("YOUTUBE_API_KEY", "").strip()


@dataclass
class Resolved:
    """What we learned about one video. `verified` is False when YouTube could not be asked."""

    youtube_id: str
    title: str = ""
    channel: str = ""
    thumbnail_url: str = ""
    duration_seconds: int = 0
    embeddable: bool = False
    verified: bool = False
    found: bool = True
    reason: str = ""
    extras: dict = field(default_factory=dict)


def oembed(http: httpx.Client, youtube_id: str) -> Resolved:
    """Title, channel and thumbnail from YouTube's public oEmbed endpoint.

    200 means the video exists and allows embedding. 401/403 means embedding is switched off by the owner of the video.
    404 means it is private or gone. Anything else (or a network error) cannot be verified, so we say "not embeddable"
    and the app opens YouTube instead.
    """
    out = Resolved(youtube_id)
    try:
        r = http.get(OEMBED_URL, params={"url": watch_url(youtube_id), "format": "json"}, timeout=10)
    except httpx.HTTPError as exc:
        out.reason = f"Could not reach YouTube ({type(exc).__name__})"
        return out
    if r.status_code == 200:
        try:
            data = r.json()
        except ValueError:
            out.reason = "YouTube sent an answer that could not be read"
            return out
        out.title = str(data.get("title") or "")
        out.channel = str(data.get("author_name") or "")
        out.thumbnail_url = str(data.get("thumbnail_url") or "")
        out.embeddable = bool(out.title)
        out.verified = True
        return out
    out.verified = r.status_code in (401, 403, 404)
    if r.status_code in (401, 403):
        out.reason = "The owner of this video does not allow playing it inside other apps"
    elif r.status_code == 404:
        out.found = False
        out.reason = "This video is private or does not exist"
    else:
        out.reason = f"YouTube answered {r.status_code}"
    return out


def api_details(http: httpx.Client, youtube_id: str) -> dict | None:
    """Duration and the real 'embeddable' flag from the Data API (1 quota unit). None when the key is not set or fails."""
    key = api_key()
    if not key:
        return None
    try:
        r = http.get(f"{API_BASE}/videos", params={"part": "contentDetails,status,snippet", "id": youtube_id},
                     headers={"X-Goog-Api-Key": key}, timeout=10)
        if r.status_code != 200:
            log.info("YouTube details lookup answered %s", r.status_code)
            return None
        items = r.json().get("items") or []
    except (httpx.HTTPError, ValueError):
        return None
    if not items:
        return None
    item = items[0]
    return {
        "duration_seconds": parse_iso_duration((item.get("contentDetails") or {}).get("duration")),
        "embeddable": (item.get("status") or {}).get("embeddable"),
        "title": (item.get("snippet") or {}).get("title") or "",
        "channel": (item.get("snippet") or {}).get("channelTitle") or "",
    }


def resolve(http: httpx.Client, youtube_id: str) -> Resolved:
    out = oembed(http, youtube_id)
    if not out.found:
        return out
    extra = api_details(http, youtube_id)
    if extra:
        out.duration_seconds = extra["duration_seconds"]
        out.title = out.title or extra["title"]
        out.channel = out.channel or extra["channel"]
        if extra["embeddable"] is False:  # the stricter answer wins
            out.embeddable = False
            out.reason = out.reason or "This video does not allow playing inside other apps"
        if extra["embeddable"] is not None and not out.verified:
            out.verified = True
    return out


# ---------------------------------------------------------------- search (needs the key, free daily quota)

_cache: dict[str, tuple[float, list[dict]]] = {}


def clear_cache() -> None:
    _cache.clear()


def _now() -> float:
    return time.time()


def search(http: httpx.Client, query: str, max_results: int = 8) -> tuple[list[dict], str, bool]:
    """Returns (results, reason, cached). reason is "" when all is well, else "no_api_key" or "search_failed"."""
    key = api_key()
    if not key:
        return [], "no_api_key", False
    norm = " ".join(query.lower().split())
    hit = _cache.get(norm)
    if hit and hit[0] > _now():
        return hit[1], "", True
    try:
        r = http.get(f"{API_BASE}/search", headers={"X-Goog-Api-Key": key}, timeout=10, params={
            "part": "snippet", "type": "video", "videoEmbeddable": "true", "safeSearch": "strict",
            "maxResults": max_results, "q": query, "relevanceLanguage": "en"})
    except httpx.HTTPError as exc:
        log.info("YouTube search failed: %s", type(exc).__name__)
        return [], "search_failed", False
    if r.status_code != 200:
        log.info("YouTube search answered %s", r.status_code)
        return [], "search_failed", False
    try:
        items = r.json().get("items") or []
    except ValueError:
        return [], "search_failed", False
    results = []
    for it in items:
        vid = (it.get("id") or {}).get("videoId")
        snip = it.get("snippet") or {}
        if not vid:
            continue
        thumbs = snip.get("thumbnails") or {}
        thumb = (thumbs.get("medium") or thumbs.get("default") or {}).get("url", "")
        results.append({"youtube_id": vid, "title": snip.get("title", ""), "channel": snip.get("channelTitle", ""),
                        "thumbnail_url": thumb, "published_at": snip.get("publishedAt", ""),
                        "watch_url": watch_url(vid), "embeddable": True})
    if len(_cache) >= CACHE_MAX:
        _cache.pop(next(iter(_cache)))
    _cache[norm] = (_now() + CACHE_SECONDS, results)
    return results, "", False
