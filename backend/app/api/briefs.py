import threading
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api import serializers as ser
from app.auth.deps import current_user
from app.db.models import Brief, NewsItem
from app.db.session import get_db
from app.settings_store import BriefSettings, get_brief_settings, set_brief_settings

router = APIRouter(tags=["briefs"])


@router.get("/briefs")
def list_briefs(limit: int = 30, _: str = Depends(current_user), db: Session = Depends(get_db)):
    rows = db.scalars(
        select(Brief).where(Brief.deleted.is_(False)).order_by(Brief.scheduled_for.desc()).limit(min(limit, 100))
    )
    return [ser.brief(b) for b in rows]


@router.get("/briefs/{brief_id}")
def get_brief(brief_id: str, _: str = Depends(current_user), db: Session = Depends(get_db)):
    b = db.get(Brief, brief_id)
    if b is None or b.deleted:
        raise HTTPException(404, "No such brief")
    items = db.scalars(select(NewsItem).where(NewsItem.id.in_(b.item_ids or [])))
    by_id = {i.id: i for i in items}
    return {**ser.brief(b), "items": [ser.news_item(by_id[i]) for i in b.item_ids if i in by_id]}


class RunIn(BaseModel):
    kind: str = "extra1"


@router.post("/briefs/run", status_code=202)
def run_now(body: RunIn, request: Request, _: str = Depends(current_user)):
    """'Prepare a brief now'. It runs in the background; the app hears about it by push/sync."""
    service = request.app.state.brief_service
    if service.busy:
        raise HTTPException(409, "A brief is already being prepared")
    kind = body.kind if body.kind in {"morning", "evening", "extra1", "extra2"} else "extra1"
    # "now", not the next scheduled slot: next_occurrence("00:00", ...) used to be called here by mistake,
    # which is the next midnight and so almost always dated TOMORROW - a brief made now would show under
    # tomorrow's date with no items visible yet, and a brief made yesterday would show under today's date.
    brief_id = service.create_row(kind, datetime.now(timezone.utc))
    threading.Thread(target=service.run, args=(brief_id,), daemon=True).start()
    return {"brief_id": brief_id}


@router.get("/settings/briefs")
def read_brief_settings(_: str = Depends(current_user), db: Session = Depends(get_db)):
    return get_brief_settings(db).model_dump()


@router.put("/settings/briefs")
def write_brief_settings(
    body: BriefSettings, request: Request, _: str = Depends(current_user), db: Session = Depends(get_db)
):
    set_brief_settings(db, body)
    scheduler = getattr(request.app.state, "brief_scheduler", None)
    if scheduler is not None:
        scheduler.reschedule()
    return body.model_dump()


@router.get("/usage")
def usage(request: Request, _: str = Depends(current_user)):
    guard = request.app.state.llm_gateway.guard
    from app.db.util import iso

    return {
        "level": guard.level(),
        "fraction": round(guard.fraction(), 3),
        "providers": {p: {"used": guard.requests_today(p), "limit": lim} for p, lim in guard.limits().items()},
        "resets_at": iso(guard.resets_at()),
    }
