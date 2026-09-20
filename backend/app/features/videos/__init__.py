"""Videos (M11): a YouTube helper. Stores only ids, titles, channels, links and the owner's own notes; never video files.

Routes (login required):
  POST /videos/resolve   {"url_or_id": "...", "topic_id": null}  ->  creates or finds the Video row
  GET  /videos/search?q=&topic_id=   uses the YouTube Data API only when YOUTUBE_API_KEY is set on the server
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Topic, Video
from app.db.session import get_db
from app.features.storage.ratelimit import rate_limit
from app.services import Services, get_services

from . import youtube
from .ids import parse_youtube_id, watch_url

router = APIRouter(prefix="/videos", tags=["videos"])


class ResolveIn(BaseModel):
    url_or_id: str
    topic_id: str | None = None


def topic_query(db: Session, topic_id: str | None) -> str:
    """A search phrase made from the topic (and its subject) title. Empty when there is no such topic."""
    topic = db.get(Topic, topic_id) if topic_id else None
    if topic is None or topic.deleted:
        return ""
    parent = db.get(Topic, topic.parent_id) if topic.parent_id else None
    parts = [topic.title]
    if parent is not None and parent.title.lower() not in topic.title.lower():
        parts.append(parent.title)
    return " ".join(parts + ["explained"]).strip()


@router.post("/resolve", dependencies=[Depends(rate_limit(30, 60, "videos-resolve"))])
def resolve(body: ResolveIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    vid = parse_youtube_id(body.url_or_id)
    if vid is None:
        raise HTTPException(400, "That does not look like a YouTube link. Paste the full link or the 11-letter video id.")
    if body.topic_id and db.get(Topic, body.topic_id) is None:
        raise HTTPException(404, "No such topic")
    info = youtube.resolve(svc.http, vid)
    if not info.found:
        raise HTTPException(404, info.reason)
    stmt = select(Video).where(Video.youtube_id == vid)
    stmt = stmt.where(Video.topic_id == body.topic_id) if body.topic_id else stmt.where(Video.topic_id.is_(None))
    row = db.scalars(stmt).first()
    created = row is None
    title = info.title or f"YouTube video {vid}"
    if row is None:
        row = Video(youtube_id=vid, topic_id=body.topic_id, title=title, channel=info.channel,
                    duration_seconds=info.duration_seconds, embeddable=info.embeddable)
        db.add(row)
    else:
        row.deleted = False
        if info.verified or not row.title:  # a failed lookup must not overwrite good data
            row.title = title
            row.channel = info.channel or row.channel
            row.embeddable = info.embeddable
            row.duration_seconds = info.duration_seconds or row.duration_seconds
    db.commit()
    return {"video": row.to_dict(), "created": created, "verified": info.verified, "reason": info.reason,
            "thumbnail_url": info.thumbnail_url, "watch_url": watch_url(vid)}


@router.get("/search", dependencies=[Depends(rate_limit(30, 3600, "videos-search"))])
def search(q: str = Query(default="", max_length=200), topic_id: str | None = None,
           db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    query = q.strip() or topic_query(db, topic_id)
    if not query:
        return {"query": "", "results": [], "reason": "no_query",
                "message": "Type what you want to watch, or open a topic."}
    results, reason, cached = youtube.search(svc.http, query)
    message = {
        "no_api_key": "Video search is off because no YouTube key is set on the server. You can still paste a link.",
        "search_failed": "YouTube search did not answer just now (the free daily limit may be used up). Paste a link instead.",
    }.get(reason, "")
    return {"query": query, "results": results, "reason": reason, "message": message, "cached": cached}
