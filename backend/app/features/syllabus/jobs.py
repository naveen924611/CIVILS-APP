"""Job `syllabus_import`: the AI turns pasted or uploaded syllabus text into a topic tree (status pending, owner approves it)."""
import logging

from sqlalchemy import select

from app.db.models_v2 import DocPage, Document, SyllabusImport
from app.features.syllabus.schemas import ChunkOut
from app.features.syllabus.trees import chunk_text, clean_tree, count_nodes, merge_trees
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler
from app.llm.promptlib import load, render

log = logging.getLogger(__name__)
MAX_TEXT_CHARS = 200_000


def _document_text(db, document_id: str) -> str:
    pages = db.scalars(
        select(DocPage).where(DocPage.document_id == document_id, DocPage.deleted.is_(False)).order_by(DocPage.page)
    )
    return "\n\n".join(p.text for p in pages if p.text and p.text.strip())


def _fail(db, imp: SyllabusImport, note: str) -> None:
    imp.status, imp.note = "failed", note[:300]
    db.commit()


def _is_bad_answer(gateway) -> bool:
    return str(getattr(gateway, "last_error", "")).startswith("parse")


@job_handler("syllabus_import", feature="syllabus_import", notify="ready")
def run(ctx: JobContext) -> dict:
    db, payload = ctx.db, ctx.payload
    imp = db.get(SyllabusImport, str(payload.get("import_id") or "")) if payload.get("import_id") else None
    if imp is None:
        exam, title = str(payload.get("exam") or "").strip(), str(payload.get("title") or "").strip()
        if not exam or not title:
            raise JobFailed("Please give the exam and a name for this syllabus.")
        imp = SyllabusImport(exam=exam[:80], title=title[:200], status="processing", document_id=payload.get("document_id"))
        db.add(imp)
        db.commit()
    text = str(payload.get("text") or "").strip()
    doc_id = payload.get("document_id") or imp.document_id
    if not text and doc_id:
        if db.get(Document, str(doc_id)) is None:
            _fail(db, imp, "That document was not found in your library.")
            raise JobFailed("That document was not found in your library.")
        text = _document_text(db, str(doc_id))
    if not text:
        _fail(db, imp, "There was no text to read. Paste the syllabus text, or pick a document that has been read (OCR done).")
        raise JobFailed("There was no syllabus text to read.")
    imp.status, imp.note = "processing", "Reading the syllabus..."
    db.commit()

    chunks = chunk_text(text[:MAX_TEXT_CHARS])
    tree: list[dict] = []
    system, user_tpl = load("syllabus_import")
    for i, chunk in enumerate(chunks, start=1):
        user = render(user_tpl, exam=imp.exam, title=imp.title, part=str(i), parts=str(len(chunks)), text=chunk)
        out = ctx.gateway.generate_json(feature="syllabus_import", system=system, user=user, schema=ChunkOut,
                                        max_output_tokens=8000)
        if out is None:
            if _is_bad_answer(ctx.gateway):
                _fail(db, imp, "The AI could not read this syllabus in a clear way. Try pasting one paper at a time.")
                raise JobFailed("The AI could not read this syllabus. Try pasting one paper at a time.")
            attempts = int((ctx.job.result_json or {}).get("_attempts", 0)) + 1
            if attempts >= ctx.settings.job_max_attempts:
                _fail(db, imp, "The AI was not available. Please try the import again later.")
            else:
                imp.note = "Waiting for the AI. This will be tried again soon."
                db.commit()
            raise AiUnavailable("AI not available for the syllabus import")
        tree = merge_trees(tree, clean_tree([n.model_dump() for n in out.nodes]))

    if not tree:
        _fail(db, imp, "No syllabus items were found in that text.")
        raise JobFailed("No syllabus items were found in that text.")
    imp.tree_json = tree
    imp.status = "pending"
    imp.note = f"{count_nodes(tree)} items found. Please check them, then approve."
    db.commit()
    return {"import_id": imp.id}
