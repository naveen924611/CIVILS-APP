"""Routes of the reports feature (login is already required).

GET  /reports/sheets/{sheet_id}/pdf     the revision sheet as a PDF (made now if missing)
GET  /reports/weekly/{report_id}/pdf    the weekly report as a PDF (made now if missing)
POST /reports/sheets/generate           {topic_id}  -> the sheet row (do-it-now version of the revision_sheet job)
POST /reports/weekly/generate           {week_start?} -> the report row (do-it-now version of the weekly_report job)
"""
from datetime import date

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.db.models_v2 import Sheet, Topic, WeeklyReport
from app.db.session import get_db
from app.features.tests.common import parse_day
from app.jobs.registry import JobFailed
from app.services import Services, get_services

from . import pdf, sheets, weekly

router = APIRouter(prefix="/reports", tags=["reports"])


class SheetIn(BaseModel):
    topic_id: str


class WeekIn(BaseModel):
    week_start: str | None = None


def _safe_name(text: str) -> str:
    keep = "".join(c if c.isalnum() else "_" for c in text)[:40].strip("_")
    return keep or "sheet"


@router.get("/sheets/{sheet_id}/pdf")
def sheet_pdf(sheet_id: str, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    sheet = db.get(Sheet, sheet_id)
    if sheet is None or sheet.deleted:
        raise HTTPException(404, "No such sheet")
    path = sheets.pdf_file(svc.settings, sheet)
    topic = db.get(Topic, sheet.topic_id)
    title = (sheet.sections or {}).get("topic_title") or (topic.title if topic else "Revision sheet")
    if not path.exists():
        try:
            pdf.render_sheet(title, (sheet.sections or {}).get("subject", ""), sheet.sections or {}, path)
        except Exception as exc:
            raise HTTPException(500, "The PDF could not be made.") from exc
    return FileResponse(path, media_type="application/pdf", filename=f"sheet_{_safe_name(title)}.pdf")


@router.get("/weekly/{report_id}/pdf")
def report_pdf(report_id: str, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    row = db.get(WeeklyReport, report_id)
    if row is None or row.deleted:
        raise HTTPException(404, "No such report")
    path = weekly.pdf_file(svc.settings, row)
    if not path.exists():
        try:
            pdf.render_report(row.data_json or {}, path)
        except Exception as exc:
            raise HTTPException(500, "The PDF could not be made.") from exc
    return FileResponse(path, media_type="application/pdf", filename=f"weekly_report_{row.week_start}.pdf")


@router.post("/sheets/generate")
def generate_sheet(body: SheetIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    try:
        sheet = sheets.build_sheet(db, svc.settings, svc.gateway, body.topic_id)
    except JobFailed as exc:
        db.rollback()
        raise HTTPException(400, str(exc)) from exc
    db.commit()
    return sheet.to_dict()


@router.post("/weekly/generate")
def generate_report(body: WeekIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    week = parse_day(body.week_start)
    row = weekly.build_report(db, svc.settings, svc.gateway, week if isinstance(week, date) else None)
    db.commit()
    return row.to_dict()
