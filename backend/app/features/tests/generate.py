"""Builds tests: weekly mock, topic test, full past paper, mistakes-only retest (M8, spec 6.17).

Questions are never invented from nothing: generated ones come from the owner's own notes (the AI only sees the note
text), past-paper ones are the imported PYQ rows, and the mistakes retest reuses questions the owner already got wrong.
Every generated question is checked (four different options, one correct position, answer stated in the explanation);
bad ones are dropped and the batch is topped up once.
"""
import logging
import math
import re
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import DailyPlan, FocusSession, Mcq, Mistake, Note, Pyq, Test, Topic
from app.db.util import as_utc
from app.jobs.registry import AiUnavailable, JobFailed
from app.llm.promptlib import load, render

from .common import IST, ist_to_utc, monday_of, norm_text, parse_day, to_ist_date, today_ist
from .schemas import GenMcq, McqBatch

log = logging.getLogger(__name__)

WEEKLY_QUESTIONS = 25
WEEKLY_MINUTES = 30
MIN_QUESTIONS = 5
GROUP_SIZE = 4  # topics per AI call
MAX_MATERIAL_CHARS = 1800
STUDIED = ("in_progress", "studied", "revised", "strong")


# --------------------------------------------------------------------------- validation


def explanation_states_answer(explanation: str, option: str) -> bool:
    """True when the explanation says what the correct option is (its words appear, or 'option B' names its letter)."""
    exp, opt = norm_text(explanation), norm_text(option)
    if not exp or not opt:
        return False
    if opt in exp:
        return True
    exp_words = set(exp.split())
    words = opt.split()
    return sum(1 for w in words if w in exp_words) / len(words) >= 0.7


def validate_mcq(q: GenMcq) -> str | None:
    """None when the question is usable, otherwise a short reason (used in logs and tests)."""
    if len((q.question or "").strip()) < 10:
        return "question too short"
    options = [o.strip() for o in q.options]
    if len(options) != 4:
        return "needs exactly four options"
    if any(not o for o in options):
        return "empty option"
    if len({norm_text(o) for o in options}) != 4:
        return "options repeat"
    if not 0 <= q.answer_index <= 3:
        return "answer position out of range"
    if not explanation_states_answer(q.explanation, options[q.answer_index]):
        letter = "abcd"[q.answer_index]
        if not re.search(rf"\b(?:option|answer)\s*\(?{letter}\)?(?![a-z])", (q.explanation or "").lower()):
            return "explanation does not state the answer"
    return None


def allocate(n_topics: int, total: int) -> list[int]:
    """Questions per topic, as even as possible (the first topics get the extra ones)."""
    if n_topics <= 0:
        return []
    base, extra = divmod(total, n_topics)
    return [base + (1 if i < extra else 0) for i in range(n_topics)]


# --------------------------------------------------------------------------- material


@dataclass
class Material:
    topic_id: str
    title: str
    text: str
    note_id: str | None = None


def as_text(item) -> str:
    if isinstance(item, str):
        return item.strip()
    if isinstance(item, dict):
        q, a = item.get("q"), item.get("a")
        if q and a:
            return f"{str(q).strip()} {str(a).strip()}"
        for key in ("text", "fact", "title", "summary"):
            if item.get(key):
                return str(item[key]).strip()
    return ""


def note_text(note: Note) -> str:
    sections = note.sections or {}
    parts: list[str] = []
    if sections.get("overview"):
        parts.append(as_text(sections["overview"]))
    for key in ("key_points", "must_remember"):
        for item in sections.get(key) or []:
            text = as_text(item)
            if text:
                parts.append("- " + text)
    text = "\n".join(p for p in parts if p)
    if len(text) < 600 and (note.content_md or "").strip():
        text = (text + "\n" + note.content_md.strip()).strip()
    return text[:MAX_MATERIAL_CHARS]


def latest_note(db: Session, topic_id: str) -> Note | None:
    return db.scalars(
        select(Note).where(Note.topic_id == topic_id, Note.deleted.is_(False))
        .order_by(Note.version.desc(), Note.updated_at.desc())
    ).first()


def topic_materials(db: Session, topic_ids: list[str]) -> list[Material]:
    """Note text for each topic that has a note with something in it (others are skipped, never invented)."""
    out: list[Material] = []
    for tid in topic_ids:
        topic = db.get(Topic, tid)
        note = latest_note(db, tid)
        if topic is None or topic.deleted or note is None:
            continue
        text = note_text(note)
        if len(text) >= 40:
            out.append(Material(topic_id=tid, title=topic.title, text=text, note_id=note.id))
    return out


def descendants(db: Session, topic_id: str, limit: int = 4) -> list[str]:
    """The topic and up to `limit`-1 of its sub-topics (breadth first)."""
    found = [topic_id]
    queue = [topic_id]
    while queue and len(found) < limit:
        parent = queue.pop(0)
        for child in db.scalars(select(Topic.id).where(Topic.parent_id == parent, Topic.deleted.is_(False))
                                .order_by(Topic.position)):
            if child not in found and len(found) < limit:
                found.append(child)
                queue.append(child)
    return found


def week_topic_ids(db: Session, week_start: date) -> list[str]:
    """Topics the owner worked on that week (focus sessions, finished study blocks, status changes). Newest first.
    When nothing happened that week, falls back to the topics currently in progress or studied."""
    start, end = ist_to_utc(week_start), ist_to_utc(week_start + timedelta(days=7))
    found: dict[str, datetime] = {}

    def add(tid: str | None, when: datetime | None) -> None:
        if tid:
            stamp = as_utc(when) or start
            if tid not in found or stamp > found[tid]:
                found[tid] = stamp

    for s in db.scalars(select(FocusSession).where(FocusSession.deleted.is_(False))):
        started = as_utc(s.started_at)
        if started and start <= started < end:
            add(s.topic_id, started)
    days = {(week_start + timedelta(days=i)).isoformat() for i in range(7)}
    for plan in db.scalars(select(DailyPlan).where(DailyPlan.date.in_(days), DailyPlan.deleted.is_(False))):
        done = plan.completion_json or {}
        for block in plan.blocks_json or []:
            if isinstance(block, dict) and done.get(block.get("id")) == "done":
                add(block.get("topic_id"), ist_to_utc(parse_day(plan.date) or week_start))
    topics = list(db.scalars(select(Topic).where(Topic.deleted.is_(False), Topic.status.in_(STUDIED))))
    for t in topics:
        when = as_utc(t.updated_at)
        if when and start <= when < end and t.status in ("in_progress", "studied"):
            add(t.id, when)
    ids = sorted(found, key=lambda k: found[k], reverse=True)
    if ids:
        return ids
    topics.sort(key=lambda t: as_utc(t.updated_at) or start, reverse=True)
    return [t.id for t in topics[:8]]


def style_examples(db: Session, topic_ids: list[str], limit: int = 3) -> list[str]:
    """Question stems from imported past papers (only their style is shown to the AI)."""
    rows = list(db.scalars(select(Pyq).where(Pyq.deleted.is_(False))))
    wanted = set(topic_ids)
    rows.sort(key=lambda r: 0 if wanted & set(r.topic_ids or []) else 1)
    return [r.question.strip().replace("\n", " ")[:220] for r in rows[:limit]]


# --------------------------------------------------------------------------- the AI part


def _ask(gateway, feature: str, group: list[Material], count: int, style: list[str], avoid: list[str]) -> list[GenMcq] | None:
    system, user_tpl = load("tests_mcq")
    material = "\n\n".join(f"[{i}] Topic: {m.title}\n{m.text}" for i, m in enumerate(group, 1))
    style_txt = ""
    if style:
        style_txt = "Style examples from past papers (copy the style only, not the content):\n" + "\n".join(
            f"- {s}" for s in style)
    avoid_txt = ""
    if avoid:
        avoid_txt = "Do not repeat these questions:\n" + "\n".join(f"- {a[:100]}" for a in avoid[:30])
    user = render(user_tpl, count=count, style=style_txt, avoid=avoid_txt, material=material)
    out = gateway.generate_json(feature=feature, system=system, user=user, schema=McqBatch, max_output_tokens=4096)
    return None if out is None else list(out.questions)


def _accept(items: list[GenMcq], group: list[Material], seen: set[str]) -> list[tuple[GenMcq, str]]:
    kept: list[tuple[GenMcq, str]] = []
    for q in items:
        reason = validate_mcq(q)
        key = norm_text(q.question)
        if reason is None and key in seen:
            reason = "repeated question"
        if reason:
            log.info("dropped a generated question: %s", reason)
            continue
        seen.add(key)
        q.question = q.question.strip()
        q.options = [o.strip() for o in q.options]
        ref = q.topic_ref if 1 <= q.topic_ref <= len(group) else 1
        kept.append((q, group[ref - 1].topic_id))
    return kept


def generate_mcqs(gateway, feature: str, materials: list[Material], total: int,
                  style: list[str] | None = None) -> list[tuple[GenMcq, str]]:
    """Up to `total` valid questions as (question, topic_id). Asks per group of topics, then tops up once.
    Raises AiUnavailable when the AI never answered or gave too few usable questions."""
    mats = materials[:total]
    if not mats:
        raise JobFailed("There are no notes to make questions from yet. Study a topic and write or generate its notes first.")
    counts = allocate(len(mats), total)
    seen: set[str] = set()
    good: list[tuple[GenMcq, str]] = []
    spare: list[tuple[GenMcq, str]] = []
    answered = 0
    for i in range(0, len(mats), GROUP_SIZE):
        group = mats[i:i + GROUP_SIZE]
        need = sum(counts[i:i + GROUP_SIZE])
        items = _ask(gateway, feature, group, math.ceil(need * 1.25), style or [], [])
        if items is None:
            continue
        answered += 1
        picked = _accept(items, group, seen)
        good.extend(picked[:need])
        spare.extend(picked[need:])
    if len(good) < total and answered:
        take = spare[: total - len(good)]
        good.extend(take)
    if len(good) < total and answered:  # one top-up call over everything
        missing = total - len(good)
        items = _ask(gateway, feature, mats[:GROUP_SIZE * 2], missing + 2, style or [], [q.question for q, _ in good])
        if items is not None:
            good.extend(_accept(items, mats[:GROUP_SIZE * 2], seen)[:missing])
    if answered == 0:
        raise AiUnavailable("The AI is busy right now. The test will be made later.")
    if len(good) < MIN_QUESTIONS:
        raise AiUnavailable("The AI gave too few good questions. Trying again later.")
    return good[:total]


# --------------------------------------------------------------------------- creating rows


def _mcq_rows(db: Session, generated: list[tuple[GenMcq, str]], source_type: str, source_id_for) -> list[Mcq]:
    rows = []
    for q, topic_id in generated:
        row = Mcq(source_type=source_type, source_id=source_id_for(topic_id), question=q.question, options=q.options,
                  answer_index=q.answer_index, explanation=q.explanation.strip(), topic_id=topic_id)
        db.add(row)
        rows.append(row)
    db.flush()
    return rows


def _finish_test(db: Session, test_id: str, kind: str, title: str, mcq_ids: list[str], duration: int,
                 scheduled_for: datetime | None) -> Test:
    test = Test(id=test_id, kind=kind, title=title, scheduled_for=scheduled_for, mcq_ids=mcq_ids,
                duration_min=duration, negative_marking=False, status="ready")
    db.add(test)
    db.flush()
    return test


def find_weekly(db: Session, target: date) -> Test | None:
    for t in db.scalars(select(Test).where(Test.kind == "weekly", Test.deleted.is_(False))):
        if to_ist_date(t.scheduled_for) == target:
            return t
    return None


def next_sunday(day: date) -> date:
    return day + timedelta(days=(6 - day.weekday()) % 7)


def generate_weekly(db: Session, gateway, *, target: date | None = None, week_start: date | None = None,
                    count: int = WEEKLY_QUESTIONS) -> Test:
    """The weekly mock: `count` questions about the topics studied that week, in past-paper style."""
    if target is None:
        target = week_start + timedelta(days=6) if week_start else next_sunday(today_ist())
    week_start = week_start or monday_of(target)
    existing = find_weekly(db, target)
    if existing is not None:
        return existing
    ids = week_topic_ids(db, week_start)
    mats = topic_materials(db, ids)[:12]
    generated = generate_mcqs(gateway, "mock_test", mats, count, style_examples(db, [m.topic_id for m in mats]))
    test_id = str(uuid.uuid4())
    rows = _mcq_rows(db, generated, "mock", lambda _tid: test_id)
    title = f"Weekly mock test, {target.strftime('%d %b')}"
    return _finish_test(db, test_id, "weekly", title, [r.id for r in rows], WEEKLY_MINUTES, ist_to_utc(target, 9, 0))


def generate_topic_test(db: Session, gateway, topic_id: str, count: int = 10) -> Test:
    topic = db.get(Topic, topic_id)
    if topic is None or topic.deleted:
        raise JobFailed("That topic was not found.")
    count = max(MIN_QUESTIONS, min(int(count or 10), 30))
    mats = topic_materials(db, descendants(db, topic_id))
    if not mats:
        raise JobFailed("This topic has no notes yet. Write or generate its notes first.")
    generated = generate_mcqs(gateway, "test_generate", mats, count, style_examples(db, [m.topic_id for m in mats]))
    notes = {m.topic_id: m.note_id for m in mats}
    rows = _mcq_rows(db, generated, "note", lambda tid: notes.get(tid))
    return _finish_test(db, str(uuid.uuid4()), "topic", f"Topic test: {topic.title}"[:200], [r.id for r in rows],
                        max(10, round(len(rows) * 1.2)), None)


def _valid_pyq(p: Pyq) -> bool:
    return len(p.options or []) == 4 and 0 <= (p.answer_index if p.answer_index is not None else -1) <= 3


def generate_past_paper(db: Session, exam: str, year: int, paper: str | None = None) -> Test:
    """A full past paper from the imported PYQ rows. Only questions with four options and a known answer are used."""
    rows = [p for p in db.scalars(select(Pyq).where(Pyq.deleted.is_(False), Pyq.year == int(year)).order_by(
        Pyq.paper, Pyq.updated_at)) if p.exam.strip().lower() == (exam or "").strip().lower() and _valid_pyq(p)
        and (not paper or p.paper.strip().lower() == paper.strip().lower())]
    if not rows:
        raise JobFailed(f"No past-paper questions with answers found for {exam} {year}. Import the paper first.")
    ids: list[str] = []
    for p in rows:
        mcq = db.scalars(select(Mcq).where(Mcq.source_type == "pyq", Mcq.source_id == p.id, Mcq.deleted.is_(False))).first()
        if mcq is None:
            answer = p.options[p.answer_index]
            label = " ".join(x for x in (p.exam, str(p.year), p.paper) if x)
            mcq = Mcq(source_type="pyq", source_id=p.id, question=p.question, options=list(p.options),
                      answer_index=p.answer_index, topic_id=(p.topic_ids or [None])[0],
                      explanation=f"Answer key: {'ABCD'[p.answer_index]}) {answer}. Source: {label}.")
            db.add(mcq)
            db.flush()
        ids.append(mcq.id)
    label = " ".join(x for x in (exam, str(year), paper or "") if x)
    return _finish_test(db, str(uuid.uuid4()), "past_paper", f"Past paper: {label}"[:200], ids,
                        max(10, math.ceil(len(ids) * 1.2)), None)


def generate_mistakes_test(db: Session, limit: int = 20) -> Test:
    """Mistakes-only retest built from the mistake book (no AI): due ones first, then the rest."""
    now = datetime.now(timezone.utc)
    due, later = [], []
    for m in db.scalars(select(Mistake).where(Mistake.deleted.is_(False), Mistake.resolved.is_(False))):
        if db.get(Mcq, m.mcq_id) is None:
            continue
        (due if m.next_due_at is None or as_utc(m.next_due_at) <= now else later).append(m)
    key = lambda m: as_utc(m.next_due_at) or now  # noqa: E731
    ordered = sorted(due, key=key) + sorted(later, key=key)
    ids: list[str] = []
    for m in ordered:
        if m.mcq_id not in ids:
            ids.append(m.mcq_id)
    ids = ids[:limit]
    if not ids:
        raise JobFailed("Your mistake book is empty, so there is nothing to retest.")
    return _finish_test(db, str(uuid.uuid4()), "mistakes", "Mistakes retest", ids, max(5, len(ids)), None)


def generate_test(db: Session, gateway, payload: dict) -> Test:
    """Entry point for the jobs and the API. payload: {kind, topic_id?, week_start?, date?, exam?, year?, paper?, count?}"""
    kind = str(payload.get("kind") or "weekly")
    if kind == "weekly":
        return generate_weekly(db, gateway, target=parse_day(payload.get("date")),
                               week_start=parse_day(payload.get("week_start")),
                               count=int(payload.get("count") or WEEKLY_QUESTIONS))
    if kind == "topic":
        if not payload.get("topic_id"):
            raise JobFailed("Choose a topic for the test.")
        return generate_topic_test(db, gateway, str(payload["topic_id"]), int(payload.get("count") or 10))
    if kind == "past_paper":
        try:
            year = int(payload.get("year"))
        except (TypeError, ValueError):
            raise JobFailed("Choose the exam year.") from None
        return generate_past_paper(db, str(payload.get("exam") or ""), year, payload.get("paper") or None)
    if kind == "mistakes":
        return generate_mistakes_test(db, int(payload.get("count") or 20))
    raise JobFailed(f"Unknown test kind '{kind}'.")


_ = IST  # (re-exported for callers that build India-time dates)
