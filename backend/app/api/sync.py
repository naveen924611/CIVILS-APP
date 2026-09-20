from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api import serializers as ser
from app.auth.deps import current_user
from app.db.models import Alert, Brief, Card, NewsItem
from app.db.session import get_db
from app.db.util import as_utc, iso

router = APIRouter(tags=["sync"])
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
PAGE = 500


@router.get("/sync/pull")
def pull(since: datetime | None = None, _: str = Depends(current_user), db: Session = Depends(get_db)):
    """Everything that changed after `since` (deleted rows included, so the tablet can remove them).
    The app stores `server_time` and sends it back next time."""
    now = datetime.now(timezone.utc)
    cut = as_utc(since) or EPOCH

    def changed(model, *extra):
        q = select(model).where(model.updated_at > cut, *extra).order_by(model.updated_at).limit(PAGE)
        return list(db.scalars(q))

    items = changed(NewsItem, NewsItem.hidden.is_(False))
    briefs = changed(Brief)
    cards = changed(Card)
    alerts = changed(Alert)
    more = any(len(x) >= PAGE for x in (items, briefs, cards, alerts))
    return {
        "server_time": iso(now),
        "more": more,  # if true, call again with the newest updated_at you received
        "news_items": [ser.news_item(x) for x in items],
        "briefs": [ser.brief(x) for x in briefs],
        "cards": [ser.card(x) for x in cards],
        "alerts": [ser.alert(x) for x in alerts],
    }


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
