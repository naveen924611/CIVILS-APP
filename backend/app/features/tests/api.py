"""Routes of the tests feature (login is already required). Most work goes through jobs (works offline); these are the
"do it now" versions and a few helpers.

POST /tests/generate         {kind, topic_id?, exam?, year?, paper?, count?, date?}  -> the new test row
GET  /tests/{id}/analysis    -> the analysis of a finished test (made now when the answers are all here)
POST /tests/pyq/map          -> {questions, mapped, unmapped, importance}  maps past-paper questions to topics
GET  /tests/pyq/stats        -> {total, with_answer, mapped, papers: [{exam, year, paper, count}]}
"""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Pyq, Test
from app.db.session import get_db
from app.jobs.registry import AiUnavailable, JobFailed
from app.services import Services, get_services

from . import pyq
from .generate import generate_test
from .scoring import maybe_analyse

router = APIRouter(prefix="/tests", tags=["tests"])


class GenerateIn(BaseModel):
    kind: str = "topic"
    topic_id: str | None = None
    exam: str | None = None
    year: int | None = None
    paper: str | None = None
    count: int | None = None
    date: str | None = None


@router.post("/generate")
def generate(body: GenerateIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    try:
        test = generate_test(db, svc.gateway, body.model_dump(exclude_none=True))
    except JobFailed as exc:
        db.rollback()
        raise HTTPException(400, str(exc)) from exc
    except AiUnavailable as exc:
        db.rollback()
        raise HTTPException(503, str(exc)) from exc
    db.commit()
    return test.to_dict()


@router.get("/pyq/stats")
def stats(db: Session = Depends(get_db)):
    rows = list(db.scalars(select(Pyq).where(Pyq.deleted.is_(False))))
    papers: dict[tuple, int] = {}
    for r in rows:
        key = (r.exam, r.year, r.paper)
        papers[key] = papers.get(key, 0) + 1
    return {
        "total": len(rows), "with_answer": sum(1 for r in rows if r.answer_index >= 0),
        "mapped": sum(1 for r in rows if r.topic_ids),
        "papers": [{"exam": e, "year": y, "paper": p, "count": n} for (e, y, p), n in sorted(papers.items())],
    }


@router.post("/pyq/map")
def map_pyqs(db: Session = Depends(get_db)):
    return pyq.map_all(db)


@router.get("/{test_id}/analysis")
def analysis(test_id: str, db: Session = Depends(get_db)):
    test = db.get(Test, test_id)
    if test is None or test.deleted:
        raise HTTPException(404, "No such test")
    if maybe_analyse(db, test):
        db.commit()
    return {"id": test.id, "status": test.status, "score": test.score, "analysis": test.analysis_json}
