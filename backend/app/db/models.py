import uuid
from datetime import datetime, timezone

from sqlalchemy import JSON, Boolean, DateTime, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


class RefreshToken(Base):
    """Stored hashed so a database leak does not leak usable tokens."""

    __tablename__ = "refresh_tokens"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    fcm_token: Mapped[str] = mapped_column(String(512), unique=True)
    label: Mapped[str] = mapped_column(String(100), default="")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)


# --- M2: news, briefs, cards, alerts, settings, LLM usage ------------------------------------
# Every synced table has id (UUID), updated_at and deleted (soft delete), as the spec requires.


class NewsItem(Base):
    """Only the summary, metadata and link are stored. Full articles are never kept."""

    __tablename__ = "news_items"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    url: Mapped[str] = mapped_column(String(1000), unique=True)
    source: Mapped[str] = mapped_column(String(100), default="")
    title: Mapped[str] = mapped_column(String(500))
    summary: Mapped[str] = mapped_column(Text, default="")
    relevance_upsc: Mapped[int] = mapped_column(Integer, default=0)
    relevance_appsc: Mapped[int] = mapped_column(Integer, default=0)
    papers: Mapped[list] = mapped_column(JSON, default=list)
    topic_ids: Mapped[list] = mapped_column(JSON, default=list)  # filled once the syllabus exists (M4)
    prelims_facts: Mapped[list] = mapped_column(JSON, default=list)  # [{"q": ..., "a": ...}]
    mains_angle: Mapped[str] = mapped_column(Text, default="")
    keywords: Mapped[list] = mapped_column(JSON, default=list)
    is_ap_specific: Mapped[bool] = mapped_column(Boolean, default=False)
    mcqs: Mapped[list] = mapped_column(JSON, default=list)
    hidden: Mapped[bool] = mapped_column(Boolean, default=False)  # relevance < 5 for both exams
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    audio_path: Mapped[str | None] = mapped_column(String(300), nullable=True)
    audio_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    brief_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, index=True
    )
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)


class Brief(Base):
    __tablename__ = "briefs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    kind: Mapped[str] = mapped_column(String(20))  # morning | evening | extra1 | extra2
    scheduled_for: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(String(20), default="preparing")  # preparing | ready | failed
    item_ids: Mapped[list] = mapped_column(JSON, default=list)
    audio_seconds_total: Mapped[int] = mapped_column(Integer, default=0)
    note: Mapped[str] = mapped_column(String(300), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, index=True
    )
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)


class Card(Base):
    """Flashcards. The spaced-repetition state (fsrs_state_json) is filled in from M5."""

    __tablename__ = "cards"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    front: Mapped[str] = mapped_column(Text)
    back: Mapped[str] = mapped_column(Text)
    topic_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    source_type: Mapped[str] = mapped_column(String(30), default="news")
    source_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    group: Mapped[str] = mapped_column(String(60), default="Current affairs")
    fsrs_state_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    due_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, index=True
    )
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)


class Alert(Base):
    """Notification history shown on the Alerts screen."""

    __tablename__ = "alerts"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    kind: Mapped[str] = mapped_column(String(30))  # brief_ready | brief_failed | ...
    title: Mapped[str] = mapped_column(String(200))
    body: Mapped[str] = mapped_column(String(500), default="")
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    read: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, index=True
    )
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)


class Setting(Base):
    __tablename__ = "settings"

    key: Mapped[str] = mapped_column(String(80), primary_key=True)
    value_json: Mapped[dict | list | str | int | float | bool | None] = mapped_column(JSON)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )


class LlmUsage(Base):
    __tablename__ = "llm_usage"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    provider: Mapped[str] = mapped_column(String(20))
    model: Mapped[str] = mapped_column(String(80))
    feature: Mapped[str] = mapped_column(String(40))
    tokens_in: Mapped[int] = mapped_column(Integer, default=0)
    tokens_out: Mapped[int] = mapped_column(Integer, default=0)
    ok: Mapped[bool] = mapped_column(Boolean, default=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, index=True)
