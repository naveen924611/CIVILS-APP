"""Job handlers `explain_feedback` {session_id} and `answer_eval` {answer_id}. Results are written to the rows."""
from app.db.models_v2 import AnswerSubmission, ExplainSession
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler

from . import evaluation, explain

MIN_WORDS = 8


def _fail(ctx: JobContext, row, message: str):
    """Marks the row failed and keeps that change (the job runner rolls back on JobFailed)."""
    row.status = "failed"
    ctx.db.commit()
    raise JobFailed(message)


@job_handler("explain_feedback", notify="feedback")
def explain_feedback(ctx: JobContext) -> dict:
    session = ctx.db.get(ExplainSession, str(ctx.payload.get("session_id") or ""))
    if session is None or session.deleted:
        raise JobFailed("That explanation was not found.")
    if len(session.transcript.split()) < MIN_WORDS:
        _fail(ctx, session, "The recording was too short to check. Please try again and speak a little longer.")
    feedback = explain.feedback_for(ctx.db, ctx.gateway, session)
    if feedback is None:
        raise AiUnavailable("The AI was busy")
    session.feedback_json = feedback
    session.status = "done"
    ctx.db.flush()
    return {"session_id": session.id}


@job_handler("answer_eval", notify="feedback")
def answer_eval(ctx: JobContext) -> dict:
    answer = ctx.db.get(AnswerSubmission, str(ctx.payload.get("answer_id") or ""))
    if answer is None or answer.deleted:
        raise JobFailed("That answer was not found.")
    try:
        feedback = evaluation.evaluate(ctx.settings, ctx.gateway, answer)
    except ValueError:
        _fail(ctx, answer, "No photos of the answer reached the server. Please add the photos again.")
        return {}  # unreachable, keeps type checkers happy
    if feedback is None:
        raise AiUnavailable("The AI was busy")
    score = float(feedback.pop("score", 0.0))
    if not feedback.get("readable", True):
        score = min(score, 2.0)
    answer.feedback_json = feedback
    answer.score = score
    answer.status = "done"
    ctx.db.flush()
    return {"answer_id": answer.id, "score": score}
