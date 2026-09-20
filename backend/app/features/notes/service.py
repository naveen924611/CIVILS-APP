"""Creating, merging and fixing topic notes (spec 7.3).

Rules that matter (the tests check each one):
  * The AI only sees the owner's own material; when there is none the note says so ("Not found in your material").
  * A note the owner wrote or edited is NEVER overwritten. New material goes under a marked "Suggested additions" block at the
    END of the note, and the owner moves what he wants (see text.append_suggestions).
  * Every change to the text keeps the old text in `note_versions` (the last MAX_VERSIONS).
  * Cards and MCQs made from a note are never duplicated.
"""
import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Card
from app.db.models_v2 import Document, Mcq, Note, NoteVersion, Topic
from app.features.notes.schemas import NoteOut
from app.features.notes.text import (
    NO_MATERIAL_TEXT,
    append_suggestions,
    norm,
    render_content_md,
    short,
    unique_new,
)
from app.llm.promptlib import load, render
from app.rag.search import search

log = logging.getLogger(__name__)
MAX_MATERIAL_CHARS = 9000
MAX_VERSIONS = 30
MODES = ("merge", "generate", "fix")


class AiFailed(Exception):
    """The AI gave no usable answer. `bad_answer` = it answered but not in the right shape (do not retry)."""

    def __init__(self, bad_answer: bool = False):
        super().__init__("bad answer" if bad_answer else "AI not available")
        self.bad_answer = bad_answer


# ---------------------------------------------------------------- rows

def get_note(db: Session, topic_id: str) -> Note | None:
    return db.scalars(
        select(Note).where(Note.topic_id == topic_id, Note.deleted.is_(False)).order_by(Note.id)
    ).first()


def ensure_note(db: Session, topic_id: str) -> Note:
    """The one Note row of a topic. Created empty when missing (the tablet cannot create note rows: it edits these)."""
    note = get_note(db, topic_id)
    if note is None:
        note = Note(topic_id=topic_id, content_md="", sections={}, sources=[], status="no_material",
                    owner_edited=False, version=1)
        db.add(note)
        db.flush()
    return note


def ensure_notes(db: Session, topic_ids: list[str]) -> int:
    """Creates the missing empty notes for many topics at once. Returns how many were created."""
    have: set[str] = set()
    for start in range(0, len(topic_ids), 500):  # SQLite allows only so many values in one IN (...)
        part = topic_ids[start:start + 500]
        have |= set(db.scalars(select(Note.topic_id).where(Note.deleted.is_(False), Note.topic_id.in_(part))))
    made = 0
    for tid in topic_ids:
        if tid not in have:
            have.add(tid)
            db.add(Note(topic_id=tid, content_md="", sections={}, sources=[], status="no_material",
                        owner_edited=False, version=1))
            made += 1
    if made:
        db.flush()
    return made


def subject_name(db: Session, topic: Topic) -> str:
    """The subject (level 1) above a topic: the revision group its cards belong to."""
    cur: Topic | None = topic
    top = topic
    for _ in range(6):
        if cur is None:
            break
        if cur.level == 1:
            return cur.title
        top = cur
        cur = db.get(Topic, cur.parent_id) if cur.parent_id else None
    return top.title


def child_titles(db: Session, topic: Topic, limit: int = 20) -> list[str]:
    rows = db.scalars(
        select(Topic.title).where(Topic.parent_id == topic.id, Topic.deleted.is_(False)).order_by(Topic.position)
    )
    return list(rows)[:limit]


def snapshot(db: Session, note: Note) -> None:
    """Keeps the current text as an earlier version (only when there is text)."""
    if not (note.content_md or "").strip():
        return
    db.add(NoteVersion(note_id=note.id, version=note.version, content_md=note.content_md))
    db.flush()
    old = list(db.scalars(
        select(NoteVersion).where(NoteVersion.note_id == note.id).order_by(NoteVersion.version.desc(), NoteVersion.created_at.desc())
    ))
    for extra in old[MAX_VERSIONS:]:
        db.delete(extra)


def set_content(db: Session, note: Note, new_md: str) -> bool:
    """Changes the text, keeping the old one as a version. Returns True when the text changed."""
    if new_md == (note.content_md or ""):
        return False
    snapshot(db, note)
    if (note.content_md or "").strip():
        note.version = (note.version or 1) + 1
    note.content_md = new_md
    return True


# ---------------------------------------------------------------- material

def gather_material(db: Session, gateway: Any, topic: Topic, children: list[str], extra: str = "",
                    only_extra: bool = False) -> tuple[str, list[dict]]:
    """Returns (material text, sources). `extra` is text the owner captured; only_extra skips the library search."""
    parts: list[str] = []
    sources: list[dict] = []
    if extra.strip():
        parts.append("[Text from the aspirant]\n" + extra.strip())
    if not only_extra:
        query = " ".join([topic.title] + children[:6])
        try:
            hits = search(db, gateway, query, topic_ids=[topic.id], k=8)
        except Exception:  # a broken index must not stop notes; the next attempt will try again
            log.exception("note material search failed")
            hits = []
        seen = set()
        for h in hits:
            parts.append(f"[{h.document_title}, page {h.page}]\n{h.text.strip()}")
            if (h.document_id, h.page) not in seen:
                seen.add((h.document_id, h.page))
                sources.append({"document_id": h.document_id, "title": h.document_title, "page": h.page})
    material = "\n\n".join(parts)
    return material[:MAX_MATERIAL_CHARS], sources


def source_entry(db: Session, source: Any) -> list[dict]:
    """The payload's `source` ({document_id, page}) as a note source entry (empty list when it is not usable)."""
    if not isinstance(source, dict) or not source.get("document_id"):
        return []
    doc = db.get(Document, str(source["document_id"]))
    try:
        page = int(source.get("page") or 1)
    except (TypeError, ValueError):
        page = 1
    return [{"document_id": str(source["document_id"]), "title": doc.title if doc else "", "page": page}]


def merge_sources(old: list, new: list[dict]) -> list[dict]:
    out = [s for s in (old or []) if isinstance(s, dict)]
    seen = {(s.get("document_id"), s.get("page")) for s in out}
    for s in new:
        key = (s.get("document_id"), s.get("page"))
        if key not in seen:
            seen.add(key)
            out.append(s)
    return out[:60]


def existing_text(note: Note) -> str:
    text = (note.content_md or "").strip() or render_content_md(note.sections or {})
    return (text or "(the note is empty)")[:6000]


# ---------------------------------------------------------------- the AI call

def ask_ai(gateway: Any, mode: str, topic: Topic, subject: str, children: list[str], note: Note, material: str,
           mcq_count: int, comment: str = "") -> NoteOut:
    """One AI call for generate / merge / fix. Raises AiFailed."""
    system, tpl = load({"generate": "notes_generate", "merge": "notes_merge", "fix": "notes_fix"}[mode])
    user = render(
        tpl, topic=topic.title, subject=subject, children="; ".join(children) or "(none)", mcq_count=str(mcq_count),
        existing=existing_text(note), material=material, comment=comment,
    )
    out = gateway.generate_json(feature="note_merge", system=system, user=user, schema=NoteOut, max_output_tokens=3500)
    if out is None:
        raise AiFailed(bad_answer=str(getattr(gateway, "last_error", "")).startswith("parse"))
    return out


# ---------------------------------------------------------------- cards and MCQs

def add_cards(db: Session, note: Note, topic: Topic, cards: list, group: str) -> list[dict]:
    have = {norm(c) for c in db.scalars(select(Card.front).where(Card.topic_id == topic.id, Card.deleted.is_(False)))}
    now = datetime.now(timezone.utc)
    made = []
    for c in cards:
        key = norm(c.front)
        if not key or key in have:
            continue
        have.add(key)
        row = Card(id=str(uuid.uuid4()), front=c.front.strip(), back=c.back.strip(), topic_id=topic.id, source_type="note",
                   source_id=note.id, group=group, fsrs_state_json=None, due_at=now)
        db.add(row)
        made.append({"id": row.id, "front": row.front, "back": row.back})
    return made


def add_mcqs(db: Session, note: Note, topic: Topic, mcqs: list) -> list[dict]:
    have = {norm(q) for q in db.scalars(select(Mcq.question).where(Mcq.topic_id == topic.id, Mcq.deleted.is_(False)))}
    made = []
    for q in mcqs:
        key = norm(q.question)
        if not key or key in have:
            continue
        have.add(key)
        row = Mcq(id=str(uuid.uuid4()), source_type="note", source_id=note.id, question=q.question.strip(),
                  options=[o.strip() for o in q.options], answer_index=q.answer_index, explanation=q.explanation.strip(),
                  topic_id=topic.id)
        db.add(row)
        made.append({"id": row.id, "question": row.question, "options": row.options, "answer_index": row.answer_index,
                     "explanation": row.explanation})
    return made


# ---------------------------------------------------------------- applying the answer

def _has_content(out: NoteOut) -> bool:
    return bool(out.found and (out.overview.strip() or out.key_points or out.must_remember or out.mains_angle.strip()))


def is_protected(note: Note) -> bool:
    """True when the owner wrote or edited text that must not be touched."""
    return bool(note.owner_edited) and bool((note.content_md or "").strip())


def apply_output(db: Session, note: Note, topic: Topic, out: NoteOut, mode: str, sources: list[dict]) -> dict:
    """Writes the AI answer into the note. Returns {"added": int, "summary": str, "changed_text": bool}."""
    group = subject_name(db, topic)
    old = dict(note.sections or {})
    protected = is_protected(note)
    old_kp, old_mr = list(old.get("key_points") or []), list(old.get("must_remember") or [])

    if not _has_content(out):
        if not (note.content_md or "").strip() and not old.get("key_points"):
            note.status = "no_material"
        else:
            note.status = "ready"
        why = out.correction.strip() if mode == "fix" and out.correction.strip() else NO_MATERIAL_TEXT
        return {"added": 0, "summary": short(why, 300), "changed_text": False}

    kp_new = unique_new(old_kp, out.key_points)
    mr_new = unique_new(old_mr, out.must_remember)
    replace = mode in ("generate", "fix") and not protected
    if replace:
        sections = dict(old)
        sections.update({
            "overview": out.overview.strip() or old.get("overview", ""),
            "key_points": list(out.key_points),
            "must_remember": list(out.must_remember),
            "mains_angle": out.mains_angle.strip() or old.get("mains_angle", ""),
        })
        added = len(out.key_points) + len(out.must_remember)
    elif mode == "fix":  # protected text: only tell the owner what to check
        sections = dict(old)
        added = 0
    else:
        sections = dict(old)
        sections["overview"] = old.get("overview") or out.overview.strip()
        sections["mains_angle"] = old.get("mains_angle") or out.mains_angle.strip()
        sections["key_points"] = old_kp + kp_new
        sections["must_remember"] = old_mr + mr_new
        added = len(kp_new) + len(mr_new)
    sections["keywords"] = list(dict.fromkeys([*(old.get("keywords") or []), *out.keywords]))[:15]

    new_cards = add_cards(db, note, topic, out.cards, group)
    new_mcqs = add_mcqs(db, note, topic, out.mcqs)
    sections["cards"] = (list(old.get("cards") or []) + new_cards)[-60:]
    sections["mcqs"] = (list(old.get("mcqs") or []) + new_mcqs)[-40:]
    note.sections = sections
    note.sources = merge_sources(note.sources, sources)

    changed_text = False
    if protected:
        items = [f"Correction: {out.correction.strip()}"] if mode == "fix" and out.correction.strip() else []
        if mode != "fix":
            items += kp_new + [f"Must remember: {m}" for m in mr_new]
        if items:
            changed_text = set_content(db, note, append_suggestions(note.content_md, items))
            added = len(items)
    else:
        changed_text = set_content(db, note, render_content_md(sections))
    note.status = "ready"
    if mode == "fix":
        summary = out.correction.strip() or "Your note was checked."
    else:
        summary = out.summary.strip() or (f"Added {added} new point{'s' if added != 1 else ''}." if added else
                                          "Nothing new: your note already has this.")
    return {"added": added, "summary": short(summary, 300), "changed_text": changed_text}


# ---------------------------------------------------------------- one job

def process(db: Session, gateway: Any, topic: Topic, note: Note, mode: str, text: str, source: Any,
            mcq_count: int, comment: str = "") -> dict:
    """Runs generate / merge / fix for one topic. Returns {"added", "summary", ...}. Raises AiFailed."""
    children = child_titles(db, topic)
    subject = subject_name(db, topic)
    only_extra = mode == "merge"
    material, sources = gather_material(db, gateway, topic, children, extra=text if mode != "fix" else "", only_extra=only_extra)
    sources = source_entry(db, source) + sources if mode != "fix" else sources
    if not material.strip():
        if not (note.content_md or "").strip():
            note.status = "no_material"
        return {"added": 0, "summary": NO_MATERIAL_TEXT, "changed_text": False}
    out = ask_ai(gateway, mode, topic, subject, children, note, material, mcq_count, comment=comment or (text if mode == "fix" else ""))
    return apply_output(db, note, topic, out, mode, sources)


def mcq_count_for(gateway: Any, mode: str, requested: Any) -> int:
    """How many MCQs to ask for. The budget guard (spec 9) drops MCQs first."""
    default = {"generate": 5, "merge": 2, "fix": 0}[mode]
    try:
        n = int(requested) if requested is not None else default
    except (TypeError, ValueError):
        n = default
    guard = getattr(gateway, "guard", None)
    level = guard.level() if guard is not None and hasattr(guard, "level") else 0
    if level >= 1:
        return 0
    return max(0, min(n, 8))
