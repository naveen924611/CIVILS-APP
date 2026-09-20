"""Job handler `tutor_question`: payload {conversation_id, question, topic_ids?, via} -> assistant ChatMessage."""
from sqlalchemy import select

from app.db.models_v2 import ChatMessage
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler

from . import service


@job_handler("tutor_question", feature="tutor_answer", notify="answer")
def tutor_question(ctx: JobContext) -> dict:
    question = str(ctx.payload.get("question") or "").strip()
    conversation_id = str(ctx.payload.get("conversation_id") or "").strip()
    if not question:
        raise JobFailed("The question was empty.")
    if not conversation_id:
        raise JobFailed("The question has no conversation.")
    topic_ids = [str(t) for t in (ctx.payload.get("topic_ids") or []) if t]
    # a retry after a crash must not create a second answer
    existing = ctx.db.scalar(
        select(ChatMessage).where(ChatMessage.job_id == ctx.job.id, ChatMessage.role == "assistant")
    )
    if existing is not None:
        return {"message_id": existing.id, "answer": existing.content, "sources": existing.sources or []}
    result = service.answer(ctx.db, ctx.gateway, question, topic_ids or None)
    if result is None:
        raise AiUnavailable("The AI was busy")
    text, sources = result
    via = "voice" if ctx.payload.get("via") == "voice" else "text"
    msg = ChatMessage(conversation_id=conversation_id, role="assistant", content=text, sources=sources,
                      via=via, job_id=ctx.job.id)
    ctx.db.add(msg)
    ctx.db.flush()
    return {"message_id": msg.id, "answer": text, "sources": sources}
