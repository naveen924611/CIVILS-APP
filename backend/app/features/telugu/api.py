"""Routes for Telugu practice (the tablet mostly works offline from the synced rows; these are for checking and reseeding).

GET  /telugu/today?day=YYYY-MM-DD   -> {day, minutes, quotas, items[{id, kind, level, content, done, score}], done, total}
GET  /telugu/progress               -> {today, streak, total_done, kinds{kind: {items, practised, avg_score}}, recent[{day, count}]}
POST /telugu/reseed                 -> reloads data/telugu/*.yaml -> {added, updated, removed, total}
"""
from datetime import date, datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.services import Services, get_services

from . import seed, service

router = APIRouter(prefix="/telugu", tags=["telugu"])


def _today(settings, day: str | None) -> date:
    if day:
        try:
            return date.fromisoformat(day)
        except ValueError as exc:
            raise HTTPException(400, "The day must look like 2026-09-20.") from exc
    return datetime.now(ZoneInfo(settings.timezone)).date()


@router.get("/today")
def today(day: str | None = None, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    return service.today_set(db, svc.settings, _today(svc.settings, day))


@router.get("/progress")
def progress(db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    return service.progress(db, svc.settings, _today(svc.settings, None))


@router.post("/reseed")
def reseed(db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    return seed.seed(db, seed.seed_dir(svc.settings))
