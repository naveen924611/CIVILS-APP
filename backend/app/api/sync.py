"""Two-way sync with the tablet.

GET  /sync/pull?since=ISO   everything changed after `since` (deleted rows included).
POST /sync/push             rows created or edited on the tablet.

Pull answer:  {server_time, next_since, more, news_items, briefs, alerts, tables: {name: [rows]}}
The app stores `next_since` and sends it back next time. If `more` is true, call again straight away.
Push body:    {tables: {name: [row, ...]}}  where each row has `id`, `updated_at` and its fields.
Push answer:  {accepted: {name: [ids]}, rejected: [{table, id, reason}], server_time}
Rules: last write wins by the tablet's `updated_at`. Only fields listed in the model's `push_fields` are
accepted. Tables without push_fields are read-only for the tablet. A note the owner edited is never
overwritten by an older write. A job is accepted only while it is new or still queued.
"""
import logging
from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api import serializers as ser
from app.auth.deps import current_user
from app.db.models import Alert, Brief, NewsItem
from app.db.session import get_db
from app.db.util import as_utc, iso
from app.sync.hooks import HOOKS
from app.sync.registry import sync_tables

log = logging.getLogger(__name__)
router = APIRouter(tags=["sync"])
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
PAGE = 500


@router.get("/sync/pull")
def pull(since: datetime | None = None, _: str = Depends(current_user), db: Session = Depends(get_db)):
    now = datetime.now(timezone.utc)
    cut = as_utc(since) or EPOCH
    full_edges: list[datetime] = []

    def changed(model, *extra):
        # ">=" so rows sharing the timestamp at a page boundary are not lost (the app just overwrites duplicates)
        q = select(model).where(model.updated_at >= cut, *extra).order_by(model.updated_at, model.id).limit(PAGE)
        rows = list(db.scalars(q))
        if len(rows) >= PAGE:
            full_edges.append(as_utc(rows[-1].updated_at))
        return rows

    items = changed(NewsItem, NewsItem.hidden.is_(False))
    briefs = changed(Brief)
    alerts = changed(Alert)
    tables: dict[str, list[dict]] = {}
    for name, model in sync_tables().items():
        rows = changed(model)
        if rows:
            tables[name] = [r.to_dict() for r in rows]
    more = bool(full_edges)
    if more:
        edge = min(full_edges)
        # a full page made only of one timestamp would loop forever: step past it
        next_since = edge if edge > cut else cut + timedelta(microseconds=1)
    else:
        next_since = now
    return {
        "server_time": iso(now),
        "next_since": iso(next_since),
        "more": more,
        "news_items": [ser.news_item(x) for x in items],
        "briefs": [ser.brief(x) for x in briefs],
        "alerts": [ser.alert(x) for x in alerts],
        "tables": tables,
    }


class PushIn(BaseModel):
    tables: dict[str, list[dict[str, Any]]] = Field(default_factory=dict)


@router.post("/sync/push")
def push(body: PushIn, request: Request, _: str = Depends(current_user), db: Session = Depends(get_db)):
    registry = sync_tables()
    accepted: dict[str, list[str]] = {}
    rejected: list[dict] = []
    new_jobs = False
    for name, rows in body.tables.items():
        model = registry.get(name)
        if model is None or not model.push_fields:
            for r in rows:
                rejected.append({"table": name, "id": str(r.get("id", "")), "reason": "read-only or unknown table"})
            continue
        for data in rows:
            row_id = str(data.get("id") or "")
            if not row_id or len(row_id) > 36:
                rejected.append({"table": name, "id": row_id, "reason": "missing or bad id"})
                continue
            try:
                outcome = _apply(db, model, name, row_id, data)
            except (ValueError, TypeError, KeyError) as exc:
                db.rollback()
                rejected.append({"table": name, "id": row_id, "reason": f"bad data: {str(exc)[:120]}"})
                continue
            if outcome in ("stale", "locked"):
                # the server copy is newer (or the job already ran): the tablet gets it on its next pull,
                # so the tablet's copy counts as handled
                accepted.setdefault(name, []).append(row_id)
                continue
            accepted.setdefault(name, []).append(row_id)
            if name == "jobs" and outcome == "new":
                new_jobs = True
    db.commit()
    if new_jobs:
        svc = getattr(request.app.state, "services", None)
        if svc is not None:
            svc.kick_jobs()
    return {"accepted": accepted, "rejected": rejected, "server_time": iso(datetime.now(timezone.utc))}


def _apply(db: Session, model, name: str, row_id: str, data: dict) -> str:
    """Returns new | updated | stale | locked (a job that already started)."""
    client_ts = model.coerce("updated_at", data["updated_at"]) if data.get("updated_at") else datetime.now(timezone.utc)
    row = db.get(model, row_id)
    is_new = row is None
    if is_new:
        row = model(id=row_id)
        row.apply_push(data)
        # tablet-created rows may also carry their own created/at timestamps
        for key in ("created_at",):
            if key in data and key in model.__table__.columns and key not in model.push_fields:
                setattr(row, key, model.coerce(key, data[key]))
        db.add(row)
    else:
        if name == "jobs" and row.status != "queued":
            return "locked"
        server_ts = as_utc(row.updated_at)
        owner_edit = name == "notes" and bool(data.get("owner_edited"))
        if server_ts and client_ts and client_ts < server_ts and not owner_edit:
            return "stale"
        row.apply_push(data)
    for hook in HOOKS.get(name, []):
        hook(db, row, is_new)
    db.flush()
    return "new" if is_new else "updated"


@router.post("/alerts/{alert_id}/read")
def mark_read(alert_id: str, _: str = Depends(current_user), db: Session = Depends(get_db)):
    row = db.get(Alert, alert_id)
    if row is None:
        raise HTTPException(404, "No such alert")
    row.read = True
    db.commit()
    return {"ok": True}


@router.post("/alerts/read-all")
def mark_all_read(_: str = Depends(current_user), db: Session = Depends(get_db)):
    for row in db.scalars(select(Alert).where(Alert.read.is_(False))):
        row.read = True
    db.commit()
    return {"ok": True}
