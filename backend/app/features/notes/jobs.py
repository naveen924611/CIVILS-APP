"""Job `note_merge` (spec 7.3, 9.3): payload {topic_id?, text?, source?:{document_id,page}, title?, mode?, mcq_count?}.

  mode "merge" (default)  the aspirant's captured / highlighted `text` is merged into the topic note
  mode "generate"         the note is written from the material in the Library (RAG); `text` is optional extra material
  mode "fix"              "Report error": `text` is the aspirant's comment; the note is checked against the material

Result: {note_id, added, summary}. Owner-edited notes are never overwritten (see service.py).
"""
import logging

from app.db.models_v2 import Topic
from app.features.notes import news, service
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler

log = logging.getLogger(__name__)


def _restore_status(note) -> None:
    """After the last failed attempt the note must not stay on 'generating'."""
    if note.status == "generating":
        note.status = "ready" if (note.content_md or note.sections) else "failed"


@job_handler("note_merge", feature="note_merge", notify="ready")
def run(ctx: JobContext) -> dict:
    db, payload = ctx.db, ctx.payload
    mode = str(payload.get("mode") or "merge").lower()
    if mode not in service.MODES:
        raise JobFailed("This note request was not understood.")
    text = str(payload.get("text") or "").strip()
    topic = db.get(Topic, str(payload["topic_id"])) if payload.get("topic_id") else None
    if topic is not None and topic.deleted:
        topic = None
    if topic is None and payload.get("topic_id"):
        raise JobFailed("That topic is not in your syllabus any more.")
    if topic is None:
        if not text or mode != "merge":
            raise JobFailed("Please choose a topic first.")
        topic = news.best_topic_for_text(db, text)
        if topic is None:
            raise JobFailed("I could not tell which topic this belongs to. Open the topic in Notes and use Add to notes.")
    if mode == "merge" and not text:
        raise JobFailed("There was no text to add.")
    if mode == "fix" and not text:
        raise JobFailed("Please tell me what looks wrong in the note.")

    note = service.ensure_note(db, topic.id)
    if mode == "generate" and note.status in ("no_material", "failed") and not (note.content_md or "").strip():
        note.status = "generating"
        db.commit()
    mcq_count = service.mcq_count_for(ctx.gateway, mode, payload.get("mcq_count"))
    try:
        result = service.process(db, ctx.gateway, topic, note, mode, text, payload.get("source"), mcq_count)
    except service.AiFailed as exc:
        attempts = int((ctx.job.result_json or {}).get("_attempts", 0)) + 1
        if exc.bad_answer or attempts >= ctx.settings.job_max_attempts:
            _restore_status(note)
            db.commit()
        if exc.bad_answer:
            raise JobFailed("The AI could not read this in a clear way. Please try again later.") from exc
        raise AiUnavailable("AI not available for the note") from exc
    if note.status == "generating":
        note.status = "ready"
    db.commit()
    try:  # in-the-news and topic keywords change with the note; a failure here must not lose the note
        news.refresh(db)
    except Exception:
        log.exception("in-the-news refresh failed")
        db.rollback()
    return {"note_id": note.id, "added": int(result["added"]), "summary": result["summary"]}
