"""Turns database rows into the JSON the tablet app stores (timestamps are always UTC, ending in Z)."""
from app.db.models import Alert, Brief, Card, NewsItem
from app.db.util import iso


def news_item(n: NewsItem) -> dict:
    return {
        "id": n.id,
        "url": n.url,
        "source": n.source,
        "title": n.title,
        "summary": n.summary,
        "relevance_upsc": n.relevance_upsc,
        "relevance_appsc": n.relevance_appsc,
        "papers": n.papers or [],
        "topic_ids": n.topic_ids or [],
        "prelims_facts": n.prelims_facts or [],
        "mains_angle": n.mains_angle,
        "keywords": n.keywords or [],
        "is_ap_specific": n.is_ap_specific,
        "mcqs": n.mcqs or [],
        "published_at": iso(n.published_at),
        "audio_url": f"/audio/{n.id}.mp3" if n.audio_path else None,
        "audio_seconds": n.audio_seconds,
        "brief_id": n.brief_id,
        "updated_at": iso(n.updated_at),
        "deleted": n.deleted,
    }


def brief(b: Brief) -> dict:
    return {
        "id": b.id,
        "kind": b.kind,
        "scheduled_for": iso(b.scheduled_for),
        "status": b.status,
        "item_ids": b.item_ids or [],
        "audio_seconds_total": b.audio_seconds_total,
        "note": b.note,
        "created_at": iso(b.created_at),
        "updated_at": iso(b.updated_at),
        "deleted": b.deleted,
    }


def card(c: Card) -> dict:
    return {
        "id": c.id,
        "front": c.front,
        "back": c.back,
        "topic_id": c.topic_id,
        "source_type": c.source_type,
        "source_id": c.source_id,
        "group": c.group,
        "fsrs_state": c.fsrs_state_json,
        "due_at": iso(c.due_at),
        "updated_at": iso(c.updated_at),
        "deleted": c.deleted,
    }


def alert(a: Alert) -> dict:
    return {
        "id": a.id,
        "kind": a.kind,
        "title": a.title,
        "body": a.body,
        "payload": a.payload or {},
        "read": a.read,
        "created_at": iso(a.created_at),
        "updated_at": iso(a.updated_at),
        "deleted": a.deleted,
    }
