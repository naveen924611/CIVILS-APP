"""Tables added for milestones M3-M12 (library, syllabus, notes, revision, planner, tutor, tests, ...).

Naming follows section 8 of the spec. Column names are also the JSON keys sent to the tablet.
"""
import uuid
from datetime import datetime, timezone

from sqlalchemy import JSON, Boolean, DateTime, Float, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.db.sync_mixin import SyncMixin


def _uuid() -> str:
    return str(uuid.uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


def pk() -> Mapped[str]:
    return mapped_column(String(36), primary_key=True, default=_uuid)


def stamp() -> Mapped[datetime]:
    return mapped_column(DateTime(timezone=True), default=_now, onupdate=_now, index=True)


def flag(default: bool = False) -> Mapped[bool]:
    return mapped_column(Boolean, default=default)


def opt_dt() -> Mapped[datetime | None]:
    return mapped_column(DateTime(timezone=True), nullable=True)


def ref() -> Mapped[str | None]:
    return mapped_column(String(36), nullable=True, index=True)


def req_dt() -> Mapped[datetime]:
    return mapped_column(DateTime(timezone=True), default=_now)


class Exam(SyncMixin, Base):
    __tablename__ = "exams"
    sync_name = "exams"
    push_fields = frozenset({"name", "stage", "date", "is_tentative", "weight"})
    id: Mapped[str] = pk()
    name: Mapped[str] = mapped_column(String(80))  # "APPSC Group-I", "UPSC CSE"
    stage: Mapped[str] = mapped_column(String(40), default="Prelims")
    date: Mapped[datetime | None] = opt_dt()  # None = "Date not announced"
    is_tentative: Mapped[bool] = flag(True)
    weight: Mapped[float] = mapped_column(Float, default=1.0)
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class SyllabusImport(SyncMixin, Base):
    """A syllabus turned into a topic tree by the AI. Nothing is used until the owner approves it."""

    __tablename__ = "syllabus_imports"
    sync_name = "syllabus_imports"
    id: Mapped[str] = pk()
    exam: Mapped[str] = mapped_column(String(80))
    title: Mapped[str] = mapped_column(String(200))
    status: Mapped[str] = mapped_column(String(20), default="processing")  # processing|pending|approved|failed
    tree_json: Mapped[list | dict | None] = mapped_column(JSON, nullable=True)
    document_id: Mapped[str | None] = ref()
    note: Mapped[str] = mapped_column(String(300), default="")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Topic(SyncMixin, Base):
    __tablename__ = "topics"
    sync_name = "topics"
    push_fields = frozenset(
        {"parent_id", "title", "level", "paper", "exam_tags", "est_hours", "status", "strength", "approved", "position"}
    )
    id: Mapped[str] = pk()
    parent_id: Mapped[str | None] = ref()
    syllabus_id: Mapped[str | None] = ref()
    title: Mapped[str] = mapped_column(String(300))
    level: Mapped[int] = mapped_column(Integer, default=0)  # 0 paper, 1 subject, 2 topic, 3 subtopic
    paper: Mapped[str] = mapped_column(String(120), default="")
    exam_tags: Mapped[list] = mapped_column(JSON, default=list)  # ["UPSC","APPSC"]
    est_hours: Mapped[float] = mapped_column(Float, default=1.0)
    status: Mapped[str] = mapped_column(String(20), default="not_started")  # not_started|in_progress|studied|revised|strong
    importance: Mapped[float] = mapped_column(Float, default=0.0)  # 0-10
    strength: Mapped[float] = mapped_column(Float, default=0.0)  # 0-1
    approved: Mapped[bool] = flag()
    position: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Document(SyncMixin, Base):
    __tablename__ = "documents"
    sync_name = "documents"
    push_fields = frozenset({"title", "reading_position", "topic_id"})
    id: Mapped[str] = pk()
    title: Mapped[str] = mapped_column(String(300))
    type: Mapped[str] = mapped_column(String(20), default="pdf")  # pdf|image|scan|note|recommended
    source_url: Mapped[str] = mapped_column(String(1000), default="")
    file_path: Mapped[str] = mapped_column(String(400), default="")  # server-side, relative to DATA_DIR/uploads
    pages: Mapped[int] = mapped_column(Integer, default=0)
    processing_status: Mapped[str] = mapped_column(String(30), default="uploaded")
    status_detail: Mapped[str] = mapped_column(String(300), default="")
    reading_position: Mapped[dict] = mapped_column(JSON, default=dict)  # {"page":3,"sentence":12}
    topic_id: Mapped[str | None] = ref()
    size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    language: Mapped[str] = mapped_column(String(10), default="en")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class DocPage(SyncMixin, Base):
    """Text of one page (extracted, OCR'd on the server, or OCR'd on the tablet)."""

    __tablename__ = "doc_pages"
    sync_name = "doc_pages"
    push_fields = frozenset({"document_id", "page", "text", "source"})
    id: Mapped[str] = pk()
    document_id: Mapped[str] = mapped_column(String(36), index=True)
    page: Mapped[int] = mapped_column(Integer, default=1)
    text: Mapped[str] = mapped_column(Text, default="")
    source: Mapped[str] = mapped_column(String(20), default="text")  # text|ocr|gemini|device_ocr
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Chunk(Base):
    """Server-only: a piece of a document for retrieval (RAG)."""

    __tablename__ = "chunks"
    id: Mapped[str] = pk()
    document_id: Mapped[str] = mapped_column(String(36), index=True)
    page: Mapped[int] = mapped_column(Integer, default=1)
    idx: Mapped[int] = mapped_column(Integer, default=0)
    text: Mapped[str] = mapped_column(Text)
    topic_ids: Mapped[list] = mapped_column(JSON, default=list)
    embedding: Mapped[list | None] = mapped_column(JSON, nullable=True)  # list of floats, or None (lexical only)
    created_at: Mapped[datetime] = req_dt()


class Note(SyncMixin, Base):
    __tablename__ = "notes"
    sync_name = "notes"
    push_fields = frozenset({"content_md", "owner_edited"})
    id: Mapped[str] = pk()
    topic_id: Mapped[str] = mapped_column(String(36), index=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    content_md: Mapped[str] = mapped_column(Text, default="")
    # sections: {"overview","key_points":[],"must_remember":[],"mains_angle","mcqs":[],"cards":[]}
    sections: Mapped[dict] = mapped_column(JSON, default=dict)
    sources: Mapped[list] = mapped_column(JSON, default=list)  # [{"document_id","title","page"}]
    owner_edited: Mapped[bool] = flag()
    status: Mapped[str] = mapped_column(String(20), default="ready")  # ready|generating|failed|no_material
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class NoteVersion(Base):
    __tablename__ = "note_versions"
    id: Mapped[str] = pk()
    note_id: Mapped[str] = mapped_column(String(36), index=True)
    version: Mapped[int] = mapped_column(Integer)
    content_md: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = req_dt()


class Highlight(SyncMixin, Base):
    __tablename__ = "highlights"
    sync_name = "highlights"
    push_fields = frozenset({"document_id", "page", "text", "kind", "topic_id"})
    id: Mapped[str] = pk()
    document_id: Mapped[str | None] = ref()
    page: Mapped[int] = mapped_column(Integer, default=1)
    text: Mapped[str] = mapped_column(Text)
    kind: Mapped[str] = mapped_column(String(10), default="point")  # point|must|card
    topic_id: Mapped[str | None] = ref()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Review(SyncMixin, Base):
    __tablename__ = "reviews"
    sync_name = "reviews"
    push_fields = frozenset({"card_id", "grade", "reviewed_at", "state_json"})
    id: Mapped[str] = pk()
    card_id: Mapped[str] = mapped_column(String(36), index=True)
    grade: Mapped[int] = mapped_column(Integer)  # 1 again, 2 hard, 3 good, 4 easy
    reviewed_at: Mapped[datetime] = req_dt()
    state_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)  # card state before the review
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class RevisionRule(SyncMixin, Base):
    __tablename__ = "revision_rules"
    sync_name = "revision_rules"
    push_fields = frozenset({"type", "params_json", "enabled"})
    id: Mapped[str] = pk()
    type: Mapped[str] = mapped_column(String(40))  # slot|pinned_subject|daily_group|max_cards|sunday_review|last_month
    params_json: Mapped[dict] = mapped_column(JSON, default=dict)
    enabled: Mapped[bool] = flag(True)
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class RevisionOrder(SyncMixin, Base):
    __tablename__ = "revision_order"
    sync_name = "revision_order"
    push_fields = frozenset({"date", "group_order"})
    id: Mapped[str] = pk()
    date: Mapped[str] = mapped_column(String(10), index=True)  # YYYY-MM-DD
    group_order: Mapped[list] = mapped_column(JSON, default=list)
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Mcq(SyncMixin, Base):
    __tablename__ = "mcqs"
    sync_name = "mcqs"
    id: Mapped[str] = pk()
    source_type: Mapped[str] = mapped_column(String(20), default="note")  # news|note|pyq|mock
    source_id: Mapped[str | None] = ref()
    question: Mapped[str] = mapped_column(Text)
    options: Mapped[list] = mapped_column(JSON, default=list)
    answer_index: Mapped[int] = mapped_column(Integer, default=0)
    explanation: Mapped[str] = mapped_column(Text, default="")
    topic_id: Mapped[str | None] = ref()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Attempt(SyncMixin, Base):
    __tablename__ = "attempts"
    sync_name = "attempts"
    push_fields = frozenset({"mcq_id", "test_id", "chosen", "correct", "confidence", "mistake_type", "at"})
    id: Mapped[str] = pk()
    mcq_id: Mapped[str] = mapped_column(String(36), index=True)
    test_id: Mapped[str | None] = ref()
    chosen: Mapped[int] = mapped_column(Integer, default=-1)  # -1 = skipped
    correct: Mapped[bool] = flag()
    confidence: Mapped[str] = mapped_column(String(10), default="")  # sure|unsure|guess
    mistake_type: Mapped[str] = mapped_column(String(20), default="")  # didnt_know|confused|silly
    at: Mapped[datetime] = req_dt()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Mistake(SyncMixin, Base):
    """The mistake book: one row per wrong MCQ; leaves after two correct answers in a row, spaced apart."""

    __tablename__ = "mistakes"
    sync_name = "mistakes"
    push_fields = frozenset({"mcq_id", "mistake_type", "streak", "last_answered_at", "next_due_at", "resolved", "your_answer"})
    id: Mapped[str] = pk()
    mcq_id: Mapped[str] = mapped_column(String(36), index=True)
    mistake_type: Mapped[str] = mapped_column(String(20), default="didnt_know")
    your_answer: Mapped[int] = mapped_column(Integer, default=-1)
    streak: Mapped[int] = mapped_column(Integer, default=0)
    last_answered_at: Mapped[datetime | None] = opt_dt()
    next_due_at: Mapped[datetime | None] = opt_dt()
    resolved: Mapped[bool] = flag()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Test(SyncMixin, Base):
    __tablename__ = "tests"
    sync_name = "tests"
    push_fields = frozenset({"status", "score", "started_at", "finished_at", "negative_marking"})
    id: Mapped[str] = pk()
    kind: Mapped[str] = mapped_column(String(20), default="weekly")  # weekly|topic|past_paper|mistakes|aptitude
    title: Mapped[str] = mapped_column(String(200), default="")
    scheduled_for: Mapped[datetime | None] = opt_dt()
    mcq_ids: Mapped[list] = mapped_column(JSON, default=list)
    duration_min: Mapped[int] = mapped_column(Integer, default=30)
    negative_marking: Mapped[bool] = flag()
    status: Mapped[str] = mapped_column(String(20), default="ready")  # generating|ready|in_progress|done|analysed
    score: Mapped[float | None] = mapped_column(Float, nullable=True)
    started_at: Mapped[datetime | None] = opt_dt()
    finished_at: Mapped[datetime | None] = opt_dt()
    analysis_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Pyq(SyncMixin, Base):
    __tablename__ = "pyqs"
    sync_name = "pyqs"
    push_fields = frozenset({"topic_ids"})
    id: Mapped[str] = pk()
    exam: Mapped[str] = mapped_column(String(40))
    year: Mapped[int] = mapped_column(Integer, default=0)
    paper: Mapped[str] = mapped_column(String(80), default="")
    question: Mapped[str] = mapped_column(Text)
    options: Mapped[list] = mapped_column(JSON, default=list)
    answer_index: Mapped[int] = mapped_column(Integer, default=-1)
    topic_ids: Mapped[list] = mapped_column(JSON, default=list)
    document_id: Mapped[str | None] = ref()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class ExplainSession(SyncMixin, Base):
    __tablename__ = "explain_sessions"
    sync_name = "explain_sessions"
    push_fields = frozenset({"topic_id", "transcript", "status", "duration_sec"})
    id: Mapped[str] = pk()
    topic_id: Mapped[str | None] = ref()
    transcript: Mapped[str] = mapped_column(Text, default="")
    duration_sec: Mapped[int] = mapped_column(Integer, default=0)
    feedback_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="recorded")  # recorded|queued|done|failed
    created_at: Mapped[datetime] = req_dt()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class AnswerSubmission(SyncMixin, Base):
    __tablename__ = "answers"
    sync_name = "answers"
    push_fields = frozenset({"question", "topic_id", "word_limit", "status", "kind"})
    id: Mapped[str] = pk()
    question: Mapped[str] = mapped_column(Text)
    topic_id: Mapped[str | None] = ref()
    word_limit: Mapped[int] = mapped_column(Integer, default=250)
    kind: Mapped[str] = mapped_column(String(10), default="mains")
    image_paths: Mapped[list] = mapped_column(JSON, default=list)  # server-side files
    feedback_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    score: Mapped[float | None] = mapped_column(Float, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="draft")  # draft|queued|done|failed
    created_at: Mapped[datetime] = req_dt()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Video(SyncMixin, Base):
    __tablename__ = "videos"
    sync_name = "videos"
    push_fields = frozenset({"topic_id", "youtube_id", "title", "channel", "duration_seconds", "embeddable", "watched"})
    id: Mapped[str] = pk()
    topic_id: Mapped[str | None] = ref()
    youtube_id: Mapped[str] = mapped_column(String(20), index=True)
    title: Mapped[str] = mapped_column(String(300), default="")
    channel: Mapped[str] = mapped_column(String(200), default="")
    duration_seconds: Mapped[int] = mapped_column(Integer, default=0)
    embeddable: Mapped[bool] = flag(True)
    watched: Mapped[bool] = flag()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class VideoNote(SyncMixin, Base):
    __tablename__ = "video_notes"
    sync_name = "video_notes"
    push_fields = frozenset({"video_id", "seconds", "text"})
    id: Mapped[str] = pk()
    video_id: Mapped[str] = mapped_column(String(36), index=True)
    seconds: Mapped[int] = mapped_column(Integer, default=0)
    text: Mapped[str] = mapped_column(Text, default="")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class DailyPlan(SyncMixin, Base):
    __tablename__ = "daily_plans"
    sync_name = "daily_plans"
    push_fields = frozenset({"completion_json"})
    id: Mapped[str] = pk()
    date: Mapped[str] = mapped_column(String(10), index=True)
    # blocks: [{"id","kind","start","minutes","title","detail","topic_id","ref"}]
    blocks_json: Mapped[list] = mapped_column(JSON, default=list)
    completion_json: Mapped[dict] = mapped_column(JSON, default=dict)  # {"block_id": "done"|"skipped"}
    summary: Mapped[str] = mapped_column(String(300), default="")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class FocusSession(SyncMixin, Base):
    __tablename__ = "focus_sessions"
    sync_name = "focus_sessions"
    push_fields = frozenset({"topic_id", "started_at", "minutes", "completion_pct", "style", "block_id"})
    id: Mapped[str] = pk()
    topic_id: Mapped[str | None] = ref()
    block_id: Mapped[str | None] = mapped_column(String(40), nullable=True)
    started_at: Mapped[datetime] = req_dt()
    minutes: Mapped[int] = mapped_column(Integer, default=0)
    completion_pct: Mapped[int] = mapped_column(Integer, default=100)
    style: Mapped[str] = mapped_column(String(20), default="50+10")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class WeeklyReport(SyncMixin, Base):
    __tablename__ = "weekly_reports"
    sync_name = "weekly_reports"
    id: Mapped[str] = pk()
    week_start: Mapped[str] = mapped_column(String(10), index=True)
    data_json: Mapped[dict] = mapped_column(JSON, default=dict)
    audio_path: Mapped[str | None] = mapped_column(String(300), nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="ready")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Sheet(SyncMixin, Base):
    """Revision sheet: one page per topic."""

    __tablename__ = "sheets"
    sync_name = "sheets"
    id: Mapped[str] = pk()
    topic_id: Mapped[str] = mapped_column(String(36), index=True)
    content_md: Mapped[str] = mapped_column(Text, default="")
    sections: Mapped[dict] = mapped_column(JSON, default=dict)
    audio_path: Mapped[str | None] = mapped_column(String(300), nullable=True)
    pdf_path: Mapped[str | None] = mapped_column(String(300), nullable=True)
    audio_seconds: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(20), default="ready")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Compilation(SyncMixin, Base):
    """Monthly current-affairs compilation."""

    __tablename__ = "compilations"
    sync_name = "compilations"
    id: Mapped[str] = pk()
    month: Mapped[str] = mapped_column(String(7), index=True)  # YYYY-MM
    title: Mapped[str] = mapped_column(String(200), default="")
    content_md: Mapped[str] = mapped_column(Text, default="")
    pdf_path: Mapped[str | None] = mapped_column(String(300), nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="ready")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class LibraryItem(SyncMixin, Base):
    """The optional library-day list (books to read when the owner visits a library)."""

    __tablename__ = "library_list"
    sync_name = "library_list"
    push_fields = frozenset({"book", "why", "chapters", "done", "visit_date"})
    id: Mapped[str] = pk()
    book: Mapped[str] = mapped_column(String(200))
    why: Mapped[str] = mapped_column(String(300), default="")
    chapters: Mapped[list] = mapped_column(JSON, default=list)
    visit_date: Mapped[str | None] = mapped_column(String(10), nullable=True)
    done: Mapped[bool] = flag()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Material(SyncMixin, Base):
    """Recommended free official material and optional books (from data/sources.yaml)."""

    __tablename__ = "materials"
    sync_name = "materials"
    id: Mapped[str] = pk()
    key: Mapped[str] = mapped_column(String(80), unique=True)
    kind: Mapped[str] = mapped_column(String(10), default="official")  # official|book
    title: Mapped[str] = mapped_column(String(300))
    why: Mapped[str] = mapped_column(String(400), default="")
    needed_for: Mapped[str] = mapped_column(String(200), default="")
    url: Mapped[str] = mapped_column(String(1000), default="")
    subject: Mapped[str] = mapped_column(String(80), default="")
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class Job(SyncMixin, Base):
    """The offline job queue. The tablet creates a row (status queued); the server runs it and fills the result."""

    __tablename__ = "jobs"
    sync_name = "jobs"
    push_fields = frozenset({"type", "payload_json", "status"})
    id: Mapped[str] = pk()
    type: Mapped[str] = mapped_column(String(40))  # tutor_question|note_merge|explain_feedback|answer_eval|ocr_page|...
    payload_json: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(20), default="queued")  # queued|running|done|failed
    result_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    error: Mapped[str] = mapped_column(String(300), default="")
    notified: Mapped[bool] = flag()
    created_at: Mapped[datetime] = req_dt()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class ChatMessage(SyncMixin, Base):
    __tablename__ = "chat_messages"
    sync_name = "chat_messages"
    push_fields = frozenset({"conversation_id", "role", "content", "sources", "at", "via", "job_id"})
    id: Mapped[str] = pk()
    conversation_id: Mapped[str] = mapped_column(String(36), index=True)
    role: Mapped[str] = mapped_column(String(10))  # user|assistant
    content: Mapped[str] = mapped_column(Text)
    sources: Mapped[list] = mapped_column(JSON, default=list)
    via: Mapped[str] = mapped_column(String(10), default="text")  # text|voice
    job_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    at: Mapped[datetime] = req_dt()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class TeluguItem(SyncMixin, Base):
    __tablename__ = "telugu_items"
    sync_name = "telugu_items"
    id: Mapped[str] = pk()
    kind: Mapped[str] = mapped_column(String(20))  # vocab|passage|translation|template
    level: Mapped[int] = mapped_column(Integer, default=1)
    content_json: Mapped[dict] = mapped_column(JSON, default=dict)
    position: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()


class TeluguProgress(SyncMixin, Base):
    __tablename__ = "telugu_progress"
    sync_name = "telugu_progress"
    push_fields = frozenset({"item_id", "done", "score", "at", "answer"})
    id: Mapped[str] = pk()
    item_id: Mapped[str] = mapped_column(String(36), index=True)
    done: Mapped[bool] = flag()
    score: Mapped[float | None] = mapped_column(Float, nullable=True)
    answer: Mapped[str] = mapped_column(Text, default="")
    at: Mapped[datetime] = req_dt()
    updated_at: Mapped[datetime] = stamp()
    deleted: Mapped[bool] = flag()
