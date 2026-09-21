"""Routes for the monthly compilation.

GET  /compilation                 -> list of {id, month, title, status, has_pdf, updated_at}
POST /compilation/generate        {month?: "YYYY-MM"} -> the row (default: the month before this one)
GET  /compilation/{id}/pdf        -> the PDF file (404 when it was not made)
The tablet gets the rows (with content_md) through sync and can also queue the job `compilation_build`.
"""
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Compilation
from app.db.session import get_db
from app.files import data_path
from app.services import Services, get_services

from . import build

router = APIRouter(prefix="/compilation", tags=["compilation"])


class GenerateIn(BaseModel):
    month: str | None = None


@router.get("")
def list_all(db: Session = Depends(get_db)):
    rows = db.scalars(select(Compilation).where(Compilation.deleted.is_(False)).order_by(Compilation.month.desc()))
    return [
        {"id": r.id, "month": r.month, "title": r.title, "status": r.status, "has_pdf": bool(r.pdf_path),
         "updated_at": r.updated_at.isoformat() if r.updated_at else None}
        for r in rows
    ]


@router.post("/generate")
def generate(body: GenerateIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    month = body.month or build.previous_month(datetime.now(ZoneInfo(svc.settings.timezone)).date())
    if not build.valid_month(month):
        raise HTTPException(400, "The month must look like 2026-08.")
    row = build.build_month(db, svc.settings, month)
    return row.to_dict()


@router.get("/{compilation_id}/pdf")
def pdf(compilation_id: str, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    row = db.get(Compilation, compilation_id)
    if row is None or row.deleted or not row.pdf_path:
        raise HTTPException(404, "No PDF for this month yet.")
    try:
        path = data_path(svc.settings, row.pdf_path)
    except ValueError as exc:
        raise HTTPException(404, "No PDF for this month yet.") from exc
    if not path.is_file():
        raise HTTPException(404, "No PDF for this month yet.")
    return FileResponse(path, media_type="application/pdf", filename=f"{row.month}-current-affairs.pdf")
