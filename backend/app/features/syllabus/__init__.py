"""Syllabus (spec 7.1, 6.20): starter outlines, AI import from pasted text or a document, owner approval, importance.

Routes (all behind login):
  POST /syllabus/import                  {exam, title, text?, document_id?}  -> {import_id, job_id}
  POST /syllabus/seed                    (re)adds any starter outline that is missing (normally done at start-up)
  POST /syllabus/recompute-importance    -> {topics, with_pyq, changed}
  GET  /syllabus/tree?exam=UPSC|APPSC|SI   the approved topics as a nested tree with coverage % and importance
  GET  /syllabus/{id}                    the import row
  PUT  /syllabus/{id}/tree               {tree, title?}  save the owner's edits to a pending import
  POST /syllabus/{id}/approve            {exam_filter?, merge_into_existing?, tree?}  -> {created, merged, total, ...}
"""
import logging

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Job, Note, SyllabusImport, Topic
from app.db.session import get_db
from app.features.syllabus import importance, service
from app.features.syllabus.schemas import ApproveIn, ImportIn, TreeIn
from app.features.syllabus.trees import add_coverage, clean_tree, count_nodes, filter_tree, nest_topics
from app.services import Services, get_services

log = logging.getLogger(__name__)
router = APIRouter(prefix="/syllabus", tags=["syllabus"])


def _row(db: Session, import_id: str) -> SyllabusImport:
    row = db.get(SyllabusImport, import_id)
    if row is None or row.deleted:
        raise HTTPException(404, "That syllabus was not found.")
    return row


@router.post("/import")
def start_import(body: ImportIn, db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    text = (body.text or "").strip()
    if not text and not body.document_id:
        raise HTTPException(400, "Paste the syllabus text or choose a document.")
    imp = SyllabusImport(exam=body.exam, title=body.title, status="processing", document_id=body.document_id,
                         note="Waiting to be read by the AI")
    db.add(imp)
    db.flush()
    job = Job(type="syllabus_import", status="queued",
              payload_json={"exam": body.exam, "title": body.title, "text": text or None,
                            "document_id": body.document_id, "import_id": imp.id})
    db.add(job)
    db.commit()
    svc.kick_jobs()
    return {"import_id": imp.id, "job_id": job.id}


@router.post("/seed")
def seed(db: Session = Depends(get_db), svc: Services = Depends(get_services)):
    return {"added": service.seed_starters(db, svc.settings)}


@router.post("/recompute-importance")
def recompute(db: Session = Depends(get_db)):
    return importance.recompute(db)


@router.get("/tree")
def topic_tree(exam: str | None = None, db: Session = Depends(get_db)):
    """The approved syllabus as one nested tree (the tablet builds the same tree itself from the synced topics)."""
    rows = [t.to_dict() for t in db.scalars(select(Topic).where(Topic.deleted.is_(False), Topic.approved.is_(True)))]
    have_note = {n.topic_id for n in db.scalars(select(Note).where(Note.deleted.is_(False))) if (n.content_md or "").strip()}
    for r in rows:
        r["has_note"] = r["id"] in have_note
    nodes = nest_topics(rows)
    if exam:
        nodes = filter_tree(nodes, exam)
    covered, leaves = add_coverage(nodes)
    return {"exam": exam, "coverage": round(100.0 * covered / leaves) if leaves else 0, "leaves": leaves, "tree": nodes}


@router.get("/{import_id}")
def get_import(import_id: str, db: Session = Depends(get_db)):
    row = _row(db, import_id)
    tree = clean_tree(row.tree_json)
    return {**row.to_dict(), "node_count": count_nodes(tree)}


@router.put("/{import_id}/tree")
def save_tree(import_id: str, body: TreeIn, db: Session = Depends(get_db)):
    row = _row(db, import_id)
    if row.status not in ("pending", "failed"):
        raise HTTPException(409, "This syllabus is not waiting for approval.")
    tree = clean_tree(body.tree)
    if not tree:
        raise HTTPException(400, "The syllabus is empty.")
    row.tree_json = tree
    row.status = "pending"
    if body.title:
        row.title = body.title[:200]
    db.commit()
    return {"import_id": row.id, "node_count": count_nodes(tree)}


@router.post("/{import_id}/approve")
def approve(import_id: str, body: ApproveIn | None = None, db: Session = Depends(get_db)):
    body = body or ApproveIn()
    row = _row(db, import_id)
    if row.status not in ("pending", "approved"):
        raise HTTPException(409, "This syllabus is not ready to approve yet.")
    if body.tree is None and not row.tree_json:
        raise HTTPException(400, "There is nothing to approve.")
    return service.approve(db, row, exam_filter=body.exam_filter, merge=body.merge_into_existing, tree=body.tree)


def _nightly(services: Services) -> None:
    """Importance moves with past papers and the news, so it is recomputed every night."""
    try:
        with services.session_factory() as db:
            importance.recompute(db)
    except Exception:  # never let a scheduled run stop the server
        log.exception("importance recompute failed")


def setup(services: Services) -> None:
    """Adds the starter outlines (as pending imports) once (safe at every start) and schedules the nightly importance update."""
    if services.settings.scheduler_enabled:
        services.scheduler.add_job(_nightly, "cron", hour=4, minute=10, id="syllabus:importance", replace_existing=True,
                                   args=[services])
    try:
        with services.session_factory() as db:
            added = service.seed_starters(db, services.settings)
        if added:
            log.info("syllabus: %d starter outline(s) added, waiting for the owner's approval", added)
    except Exception:  # never stop the server from starting
        log.exception("syllabus seeding failed")


from . import jobs  # noqa: E402,F401  (registers the job handler)
