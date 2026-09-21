"""A short "what to listen for" for a saved video, made from its title, channel, topic and the owner's own notes.

The video is never downloaded, heard or transcribed, so the text is always labelled as based on the title only.
The result is stored under the shared setting `video.summary.<video id>` (the tablet reads it like any other setting)
and is also returned as the job result.
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.kv import get_kv, set_kv
from app.db.models_v2 import Topic, Video, VideoNote
from app.jobs.registry import AiUnavailable, JobContext, JobFailed, job_handler
from app.llm import promptlib

NOTE = "Made from the title, the channel and your notes only. The video itself was not watched, so check it against the video."
MAX_NOTES = 20


def summary_key(video_id: str) -> str:
    return f"video.summary.{video_id.lower()}"


def _length(seconds: int) -> str:
    if seconds <= 0:
        return "not known"
    minutes = max(1, round(seconds / 60))
    return f"about {minutes} minutes"


def _topic_title(db: Session, topic_id: str | None) -> str:
    topic = db.get(Topic, topic_id) if topic_id else None
    return topic.title if topic is not None and not topic.deleted else "not sorted under a topic"


def _notes_text(db: Session, video_id: str) -> str:
    rows = db.scalars(select(VideoNote).where(VideoNote.video_id == video_id, VideoNote.deleted.is_(False))
                      .order_by(VideoNote.seconds).limit(MAX_NOTES))
    lines = [f"{r.seconds // 60}:{r.seconds % 60:02d} {r.text.strip()}" for r in rows if r.text.strip()]
    return "\n".join(lines) or "none yet"


def summarize(ctx: JobContext, video: Video) -> str | None:
    system, user = promptlib.load("videos_summary")
    user = promptlib.render(user, title=video.title or "unknown", channel=video.channel or "unknown",
                            topic=_topic_title(ctx.db, video.topic_id), length=_length(video.duration_seconds),
                            notes=_notes_text(ctx.db, video.id))
    text = ctx.gateway.generate_text(feature="video_summary", system=system, user=user, max_output_tokens=400)
    return text.strip() if text and text.strip() else None


@job_handler("video_summary", feature="video_summary")
def video_summary(ctx: JobContext) -> dict:
    video = ctx.db.get(Video, str(ctx.payload.get("video_id") or ""))
    if video is None or video.deleted:
        raise JobFailed("That video was not found.")
    if not (video.title or "").strip():
        raise JobFailed("This video has no title yet, so there is nothing to summarise.")
    text = summarize(ctx, video)
    if text is None:
        raise AiUnavailable("The AI was busy")
    value = {"text": text, "note": NOTE}
    set_kv(ctx.db, summary_key(video.id), value)
    return {"video_id": video.id, **value}


def read_summary(db: Session, video_id: str) -> dict | None:
    value = get_kv(db, summary_key(video_id))
    return value if isinstance(value, dict) else None
