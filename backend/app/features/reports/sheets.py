"""Revision sheets (spec 6.18): one page per topic built from the owner's own notes.

Key facts and Must remember come from the note; past-paper themes are the real past-paper questions mapped to the topic;
"in the news" and the mains angle are the note's own sections. The AI (optional, skipped when the budget is tight or
unavailable) only tightens the note into short lines and suggests memory hooks; without it the note's key points are used.
A sheet is rebuilt when the note changes (nightly refresh).
"""
import logging
from datetime import datetime, timezone
from pathlib import Path

from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models_v2 import Note, Pyq, Sheet, Topic
from app.db.util import as_utc, iso
from app.features.tests.common import subject_of
from app.features.tests.generate import as_text, descendants, latest_note
from app.jobs.registry import JobFailed
from app.llm.promptlib import load, render

from . import pdf

log = logging.getLogger(__name__)
MAX_FACTS = 10
MAX_THEMES = 5
MAX_NEWS = 4
WORDS_PER_SECOND = 2.5  # about 150 words a minute


class SheetOut(BaseModel):
    key_facts: list[str] = Field(default_factory=list)
    memory_hooks: list[str] = Field(default_factory=list)


def _lines(items, limit: int) -> list[str]:
    out = []
    for item in items or []:
        text = as_text(item)
        if text:
            out.append(" ".join(text.split()))
        if len(out) >= limit:
            break
    return out


def _themes(db: Session, topic_id: str) -> list[str]:
    ids = set(descendants(db, topic_id, limit=12))
    rows = [q for q in db.scalars(select(Pyq).where(Pyq.deleted.is_(False))) if ids & set(q.topic_ids or [])]
    rows.sort(key=lambda q: -int(q.year or 0))
    out = []
    for q in rows[:MAX_THEMES]:
        stem = " ".join((q.question or "").split())
        out.append(f"{q.exam} {q.year}: {stem[:150]}{'...' if len(stem) > 150 else ''}")
    return out


def _ai_lines(gateway, title: str, note_text: str) -> SheetOut | None:
    guard = getattr(gateway, "guard", None)
    if guard is not None and hasattr(guard, "level") and guard.level() >= 2:
        return None
    system, user_tpl = load("reports_sheet")
    out = gateway.generate_json(feature="revision_sheet", system=system, user=render(user_tpl, title=title, note=note_text[:6000]),
                                schema=SheetOut, max_output_tokens=1024)
    if out is None or not [f for f in out.key_facts if f.strip()]:
        return None
    return out


def script_for(title: str, sections: dict) -> str:
    """What is read aloud (about 3 minutes at most)."""
    parts = [f"Revision sheet. {title}."]
    for label, key in (("Key facts.", "key_facts"), ("Must remember.", "must_remember"), ("Memory hooks.", "memory_hooks")):
        items = sections.get(key) or []
        if items:
            parts.append(label)
            parts.extend(str(i).rstrip(".") + "." for i in items)
    if sections.get("past_paper_themes"):
        parts.append("Seen in past papers.")
        parts.extend(str(i).rstrip(".") + "." for i in sections["past_paper_themes"][:3])
    if str(sections.get("mains_angle") or "").strip():
        parts.append("Mains angle. " + str(sections["mains_angle"]).strip())
    words = " ".join(parts).split()
    return " ".join(words[: int(180 * WORDS_PER_SECOND)])  # 180 seconds


def content_md(title: str, subject: str, s: dict) -> str:
    out = [f"# {title}"]
    if subject:
        out.append(f"*{subject}*")

    def block(name: str, items) -> None:
        items = [str(i) for i in items or [] if str(i).strip()]
        if items:
            out.append(f"## {name}")
            out.extend(f"- {i}" for i in items)

    block("Key facts", s.get("key_facts"))
    block("Must remember", s.get("must_remember"))
    block("Past-paper themes", s.get("past_paper_themes"))
    block("In the news", [n.get("title", "") for n in s.get("in_the_news") or [] if isinstance(n, dict)])
    block("Memory hooks", s.get("memory_hooks"))
    if str(s.get("mains_angle") or "").strip():
        out.append("## Mains angle")
        out.append(str(s["mains_angle"]).strip())
    return "\n\n".join(out)


def sheet_for_topic(db: Session, topic_id: str) -> Sheet | None:
    return db.scalars(select(Sheet).where(Sheet.topic_id == topic_id, Sheet.deleted.is_(False))
                      .order_by(Sheet.updated_at.desc())).first()


def pdf_file(settings: Settings, sheet: Sheet) -> Path:
    return Path(settings.data_dir) / "reports" / "sheets" / f"{sheet.id}.pdf"


def write_pdf(settings: Settings, sheet: Sheet, title: str, subject: str) -> None:
    try:
        path = pdf.render_sheet(title, subject, sheet.sections or {}, pdf_file(settings, sheet))
        sheet.pdf_path = str(path.relative_to(Path(settings.data_dir)))
    except Exception:  # a PDF problem must never lose the sheet itself
        log.exception("sheet PDF failed")


def build_sheet(db: Session, settings: Settings, gateway, topic_id: str, use_ai: bool = True) -> Sheet:
    """Creates or rebuilds the sheet of a topic. Raises JobFailed when the topic has no notes."""
    topic = db.get(Topic, topic_id)
    if topic is None or topic.deleted:
        raise JobFailed("That topic was not found.")
    note = latest_note(db, topic_id)
    sections = dict(note.sections or {}) if note else {}
    text = ((note.content_md or "").strip() if note else "")
    if note is None or not (sections.get("key_points") or sections.get("must_remember") or len(text) >= 60):
        raise JobFailed("This topic has no notes yet. Write or generate its notes first, then make the sheet.")
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False)))}
    subject = subject_of(topic_id, topics)
    facts = _lines(sections.get("key_points"), MAX_FACTS)
    hooks: list[str] = []
    used_ai = False
    if use_ai:
        tidy = _ai_lines(gateway, topic.title, "\n".join(["- " + f for f in _lines(sections.get("key_points"), 30)]) + "\n" + text)
        if tidy is not None:
            facts = _lines(tidy.key_facts, 8)
            hooks = _lines(tidy.memory_hooks, 3)
            used_ai = True
    if not facts and text:  # notes written by hand: their first lines
        facts = _lines([ln.lstrip("-*# ").strip() for ln in text.splitlines() if len(ln.strip()) > 20], 8)
    news = [{"title": str(n.get("title", ""))[:160], "url": str(n.get("url", ""))}
            for n in sections.get("in_the_news") or [] if isinstance(n, dict)][:MAX_NEWS]
    data = {
        "topic_title": topic.title, "subject": subject, "key_facts": facts,
        "must_remember": _lines(sections.get("must_remember"), 10), "past_paper_themes": _themes(db, topic_id),
        "in_the_news": news, "mains_angle": as_text(sections.get("mains_angle")) if sections.get("mains_angle") else "",
        "memory_hooks": hooks, "note_id": note.id, "note_version": note.version,
        "note_updated_at": iso(note.updated_at), "ai": used_ai,
    }
    data["script"] = script_for(topic.title, data)
    sheet = sheet_for_topic(db, topic_id)
    if sheet is None:
        sheet = Sheet(topic_id=topic_id)
        db.add(sheet)
    sheet.sections = data
    sheet.content_md = content_md(topic.title, subject, data)
    sheet.audio_seconds = int(round(len(data["script"].split()) / WORDS_PER_SECOND))
    sheet.status = "ready"
    db.flush()
    write_pdf(settings, sheet, topic.title, subject)
    db.flush()
    return sheet


def stale_topics(db: Session, limit: int = 10) -> list[str]:
    """Topics whose note is newer than their sheet (or that have notes but no sheet), most important first."""
    sheets = {s.topic_id: s for s in db.scalars(select(Sheet).where(Sheet.deleted.is_(False)))}
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False), Topic.approved.is_(True)))}
    out: list[tuple[float, str]] = []
    for note in db.scalars(select(Note).where(Note.deleted.is_(False))):
        topic = topics.get(note.topic_id)
        if topic is None or topic.status == "not_started":
            continue
        sheet = sheets.get(note.topic_id)
        n_updated = as_utc(note.updated_at) or datetime.now(timezone.utc)
        if sheet is None or (as_utc(sheet.updated_at) or n_updated) < n_updated:
            out.append((-(topic.importance or 0.0), note.topic_id))
    out.sort()
    seen: list[str] = []
    for _, tid in out:
        if tid not in seen:
            seen.append(tid)
    return seen[:limit]
