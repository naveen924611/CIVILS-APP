"""Planner routes. The tablet reads plans through sync (DailyPlan rows); these routes make or change them on demand."""
from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.db.session import get_db

from . import service

router = APIRouter(prefix="/planner", tags=["planner"])


class RegenerateIn(BaseModel):
    days: int = Field(default=7, ge=1, le=service.MAX_AHEAD_DAYS)
    start: str | None = None


class CompleteIn(BaseModel):
    date: str
    block_id: str
    status: str = "done"  # done | skipped | open


def _day(text: str | None) -> date:
    if not text:
        return service.today_ist()
    try:
        return date.fromisoformat(text[:10])
    except ValueError as exc:
        raise HTTPException(400, "Date must look like 2026-09-20") from exc


@router.get("/plan")
def get_plan(request: Request, date: str | None = None, db: Session = Depends(get_db)):
    day = _day(date)
    row = service.ensure_plan(db, request.app.state.services.settings, day)
    db.commit()
    if row is None:
        return {"date": day.isoformat(), "blocks": [], "completion": {}, "summary": "", "minutes": 0}
    return service.plan_dict(row)


@router.get("/week")
def get_week(request: Request, start: str | None = None, db: Session = Depends(get_db)):
    first = _day(start)
    plans = []
    for i in range(7):
        row = service.ensure_plan(db, request.app.state.services.settings, first + timedelta(days=i))
        if row is not None:
            plans.append(service.plan_dict(row))
    db.commit()
    return {"start": first.isoformat(), "plans": plans}


@router.post("/regenerate")
def regenerate(body: RegenerateIn, request: Request, db: Session = Depends(get_db)):
    start = _day(body.start)
    rows = service.plan_range(db, request.app.state.services.settings, start, body.days)
    db.commit()
    return {"planned": len(rows), "plans": [service.plan_dict(r) for r in rows], "missed_days": service.count_missed_days(db, service.today_ist())}


@router.post("/complete")
def complete(body: CompleteIn, db: Session = Depends(get_db)):
    row = service.set_completion(db, _day(body.date), body.block_id, body.status)
    if row is None:
        raise HTTPException(404, "No plan for that day")
    db.commit()
    return service.plan_dict(row)


@router.get("/exams")
def exams(db: Session = Depends(get_db)):
    return {"exams": service.exam_countdown(db)}
