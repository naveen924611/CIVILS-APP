"""Database side of the Telugu practice: today's set and the progress numbers."""
from datetime import date, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.kv import get_kv
from app.config import Settings
from app.db.models_v2 import TeluguItem, TeluguProgress
from app.db.util import as_utc

from . import practice

MINUTES_KEY = "study.telugu_minutes"
DEFAULT_MINUTES = 15


def minutes_setting(db: Session) -> int:
    value = get_kv(db, MINUTES_KEY, DEFAULT_MINUTES)
    try:
        return max(0, min(120, int(value)))
    except (TypeError, ValueError):
        return DEFAULT_MINUTES


def _history(db: Session, settings: Settings) -> list[practice.Done]:
    rows = db.scalars(select(TeluguProgress).where(TeluguProgress.deleted.is_(False), TeluguProgress.done.is_(True)))
    out = []
    for r in rows:
        at = as_utc(r.at) if r.at else datetime.now(timezone.utc)
        out.append(practice.Done(r.item_id, r.score, practice.study_day(at, settings.timezone), at.isoformat()))
    return out


def _items(db: Session) -> list[TeluguItem]:
    return list(db.scalars(select(TeluguItem).where(TeluguItem.deleted.is_(False))))


def today_set(db: Session, settings: Settings, today: date) -> dict:
    items = _items(db)
    history = _history(db, settings)
    minutes = minutes_setting(db)
    by_id = {i.id: i for i in items}
    picked = practice.pick_today([practice.Item(i.id, i.kind, i.position) for i in items], history, today, minutes)
    done_today = {h.item_id for h in history if h.on == today}
    latest = practice.latest_by_item(history)
    out = []
    for p in picked:
        row = by_id[p.id]
        last = latest.get(p.id)
        out.append({
            "id": row.id, "kind": row.kind, "level": row.level, "content": row.content_json,
            "done": p.id in done_today, "score": last.score if last else None,
        })
    return {
        "day": today.isoformat(), "minutes": minutes, "quotas": practice.quotas(minutes, today.weekday()),
        "items": out, "done": sum(1 for i in out if i["done"]), "total": len(out),
    }


def progress(db: Session, settings: Settings, today: date, days: int = 14) -> dict:
    items = _items(db)
    history = _history(db, settings)
    kind_of = {i.id: i.kind for i in items}
    per_kind: dict[str, dict] = {k: {"items": 0, "practised": 0, "avg_score": None} for k in practice.KIND_ORDER}
    for i in items:
        if i.kind in per_kind:
            per_kind[i.kind]["items"] += 1
    scores: dict[str, list[float]] = {}
    for item_id, h in practice.latest_by_item(history).items():
        kind = kind_of.get(item_id)
        if kind in per_kind:
            per_kind[kind]["practised"] += 1
            if h.score is not None:
                scores.setdefault(kind, []).append(h.score)
    for kind, vals in scores.items():
        per_kind[kind]["avg_score"] = round(sum(vals) / len(vals), 2)
    counts: dict[date, int] = {}
    for h in history:
        counts[h.on] = counts.get(h.on, 0) + 1
    recent = [
        {"day": (today - timedelta(days=n)).isoformat(), "count": counts.get(today - timedelta(days=n), 0)}
        for n in range(days - 1, -1, -1)
    ]
    return {
        "today": today.isoformat(), "streak": practice.streak(set(counts), today),
        "total_done": len(history), "kinds": per_kind, "recent": recent,
    }
