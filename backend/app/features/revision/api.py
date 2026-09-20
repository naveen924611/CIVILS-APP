"""Revision routes (the tablet grades cards offline itself; these serve the server copy, tests and other features)."""
from __future__ import annotations

from datetime import date, datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.db.models import Card
from app.db.session import get_db

from . import cardgen, service

router = APIRouter(prefix="/revision", tags=["revision"])


class GenerateIn(BaseModel):
    topic_id: str | None = None


class ReviewIn(BaseModel):
    card_id: str
    grade: int = Field(ge=1, le=4)
    at: datetime | None = None


class OrderIn(BaseModel):
    date: str | None = None
    group_order: list[str] = Field(default_factory=list)


class SnoozeIn(BaseModel):
    topic_id: str


def _day(text: str | None) -> date:
    if not text:
        return service.study_day()
    try:
        return date.fromisoformat(text[:10])
    except ValueError as exc:
        raise HTTPException(400, "Date must look like 2026-09-20") from exc


@router.get("/queue")
def queue(smart: bool = False, db: Session = Depends(get_db)):
    return service.build_queue(db, smart=smart)


@router.get("/sunday-review")
def sunday_review(db: Session = Depends(get_db)):
    return service.sunday_review(db)


@router.post("/generate")
def generate(body: GenerateIn, db: Session = Depends(get_db)):
    out = cardgen.generate_cards(db, body.topic_id)
    db.commit()
    return out


@router.post("/review")
def review(body: ReviewIn, db: Session = Depends(get_db)):
    card = db.get(Card, body.card_id)
    if card is None or card.deleted:
        raise HTTPException(404, "No such card")
    at = body.at or datetime.now(timezone.utc)
    state = service.grade_card(db, card, body.grade, at)
    if card.topic_id:
        service.update_strength(db, at, {card.topic_id})
    db.commit()
    return {"card_id": card.id, "state": state, "due_at": state["due"], "intervals": service.intervals_for(db, card, at)}


@router.get("/cards/{card_id}/intervals")
def intervals(card_id: str, db: Session = Depends(get_db)):
    card = db.get(Card, card_id)
    if card is None or card.deleted:
        raise HTTPException(404, "No such card")
    return {"card_id": card_id, "intervals": service.intervals_for(db, card)}


@router.put("/order")
def put_order(body: OrderIn, db: Session = Depends(get_db)):
    row = service.save_manual_order(db, _day(body.date), body.group_order)
    db.commit()
    return {"date": row.date, "group_order": row.group_order}


@router.delete("/order")
def delete_order(date: str | None = None, db: Session = Depends(get_db)):
    n = service.clear_manual_order(db, _day(date))
    db.commit()
    return {"cleared": n}


@router.post("/snooze")
def snooze(body: SnoozeIn, db: Session = Depends(get_db)):
    out = service.snooze_topic(db, body.topic_id)
    db.commit()
    return out


@router.post("/recompute")
def recompute(db: Session = Depends(get_db)):
    out = service.update_strength(db)
    db.commit()
    return out
