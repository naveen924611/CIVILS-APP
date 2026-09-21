"""Job `telugu_feedback` {item_id, answer, progress_id?} -> {item_id, feedback, score}.

feedback = {summary, strengths[], corrections[{said, better, why}], model_answer}; score is 0 to 10.
When the payload has `progress_id`, that TeluguProgress row gets score = score / 10 (0 to 1) as well.
"""
from app.db.models_v2 import TeluguItem, TeluguProgress
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler
from app.llm import promptlib

from .schemas import FeedbackOut

PROMPT = "telugu_feedback_v1"
MAX_ANSWER = 4000
CHECKABLE = ("translation", "template")


def _task_text(kind: str, content: dict) -> tuple[str, str, str]:
    """(task, reference, checklist) for the prompt."""
    if kind == "translation":
        reference = str(content.get("reference", ""))
        if content.get("direction") == "te_to_en":
            return f"Translate into English: {content.get('te', '')}", reference, "Correct meaning, natural English."
        return f"Translate into Telugu: {content.get('en', '')}", reference, "Correct meaning, correct Telugu words and spelling."
    parts = " / ".join(str(s.get("en", "")) for s in content.get("structure") or [] if isinstance(s, dict))
    return (
        f"{content.get('title_en', '')}. {content.get('task_en', '')} (about {content.get('min_words', 100)} words, in Telugu)",
        str(content.get("sample_te") or "(none)"),
        parts or "Clear structure, polite tone, correct Telugu.",
    )


@job_handler("telugu_feedback", notify="feedback")
def telugu_feedback(ctx: JobContext) -> dict:
    item = ctx.db.get(TeluguItem, str(ctx.payload.get("item_id") or ""))
    if item is None or item.deleted:
        raise JobFailed("That practice item was not found. Please sync and try again.")
    if item.kind not in CHECKABLE:
        raise JobFailed("Only translations and letters or essays are checked by the tutor.")
    answer = str(ctx.payload.get("answer") or "").strip()
    if len(answer) < 3:
        raise JobFailed("There was no answer to check. Please write it and send again.")
    task, reference, checklist = _task_text(item.kind, item.content_json or {})
    system, user = promptlib.load(PROMPT)
    out = ctx.gateway.generate_json(
        feature="telugu_feedback",
        system=system,
        user=promptlib.render(
            user, kind=item.kind, task=task, reference=reference, checklist=checklist,
            answer=answer[:MAX_ANSWER].replace("</answer>", ""),
        ),
        schema=FeedbackOut,
        max_output_tokens=1500,
    )
    if out is None:
        raise AiUnavailable("The AI was busy")
    assert isinstance(out, FeedbackOut)
    progress_id = str(ctx.payload.get("progress_id") or "")
    if progress_id:
        row = ctx.db.get(TeluguProgress, progress_id)
        if row is not None and row.item_id == item.id:
            row.score = round(out.score / 10.0, 3)
    ctx.db.flush()
    return {
        "item_id": item.id,
        "score": out.score,
        "feedback": {
            "summary": out.summary,
            "strengths": out.strengths,
            "corrections": [c.model_dump() for c in out.corrections],
            "model_answer": out.model_answer,
        },
    }
