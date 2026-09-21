"""Builds and saves the daily plans (spec 7.6). Reads the database and the owner's settings, then lets `engine.py` decide.

`plan_range(db, settings, start, days)` plans several days in a row (a topic is not planned twice, subjects rotate) and
writes one DailyPlan row per day, so the plans reach the tablet through sync. Blocks the owner already ticked "done" on
that day are kept as they are. Other features add their own blocks through `plan_hooks.plan_postprocessor`.
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.kv import get_kv
from app.db.models_v2 import DailyPlan, Exam, FocusSession, LibraryItem, Review, Topic
from app.db.util import as_utc
from app.features.plan_hooks import run_postprocessors
from app.features.revision import service as rev
from app.settings_store import get_brief_settings

from . import engine as eng

log = logging.getLogger(__name__)
IST = ZoneInfo("Asia/Kolkata")

KV_HOURS = "study.hours"
KV_TELUGU = "study.telugu_minutes"
KV_LIBRARY = "study.library_day"
KV_PRIORITY = "exam.priority"
KV_ADJUST = "plan.adjustments"
HISTORY_DAYS = 7
MAX_AHEAD_DAYS = 21
LAST_MONTH_DAYS = 30
STUDY_MINUTES_DONE = ("done",)


def today_ist(now: datetime | None = None) -> date:
    return (now or datetime.now(timezone.utc)).astimezone(IST).date()


# ---------------------------------------------------------------------------------------------- reading the settings
def _hours(db: Session) -> dict:
    raw = get_kv(db, KV_HOURS, None)
    out = dict(eng.DEFAULT_HOURS)
    if isinstance(raw, dict):
        for k, v in raw.items():
            if k in out:
                try:
                    out[k] = max(0.0, min(float(v), 16.0))
                except (TypeError, ValueError):
                    pass
    return out


def _priority_for(name: str, priority: dict) -> float:
    for key, value in priority.items():
        if eng.has_word(name, str(key)):
            try:
                return max(float(value), 0.0)
            except (TypeError, ValueError):
                return 1.0
    return 1.0


def load_exams(db: Session) -> list[eng.ExamInfo]:
    priority = get_kv(db, KV_PRIORITY, None)
    priority = priority if isinstance(priority, dict) else {}
    out = []
    for e in db.scalars(select(Exam).where(Exam.deleted.is_(False)).order_by(Exam.name)):
        on = as_utc(e.date).astimezone(IST).date() if e.date is not None else None
        out.append(eng.ExamInfo(name=e.name, stage=e.stage or "", on=on, weight=(e.weight or 1.0) * _priority_for(e.name, priority)))
    return out


def load_topics(db: Session) -> tuple[list[eng.TopicInfo], dict[str, Topic]]:
    """Approved topics without children (the things that can actually be studied)."""
    rows = list(db.scalars(select(Topic).where(Topic.deleted.is_(False))))
    by_id = {t.id: t for t in rows}
    parents = {t.parent_id for t in rows if t.parent_id}
    leaves = [t for t in rows if t.approved and t.id not in parents and t.level >= 1]
    infos = [
        eng.TopicInfo(
            id=t.id, title=t.title, subject=rev.subject_of(t, by_id), paper=t.paper or "", exam_tags=list(t.exam_tags or []),
            est_hours=float(t.est_hours or 1.0), status=t.status or "not_started", importance=float(t.importance or 0.0),
            position=int(t.position or 0),
        )
        for t in leaves
    ]
    return infos, by_id


def pace(db: Session, today: date) -> float:
    since = rev.start_of_day(today - timedelta(days=21))
    rows = db.scalars(select(FocusSession).where(FocusSession.deleted.is_(False), FocusSession.started_at >= since))
    return eng.pace_factor([(float(r.minutes or 0), float(r.completion_pct or 100)) for r in rows])


def card_minutes(db: Session, today: date) -> float:
    since = rev.start_of_day(today - timedelta(days=30))
    rows = db.scalars(select(Review.reviewed_at).where(Review.deleted.is_(False), Review.reviewed_at >= since))
    return eng.minutes_per_card([as_utc(r) for r in rows if r is not None])


def brief_slots(db: Session, day: date) -> tuple[tuple[str, bool] | None, tuple[str, bool] | None]:
    slots = {b.id: b for b in get_brief_settings(db).briefs}

    def one(name: str):
        b = slots.get(name)
        if b is None or not b.enabled or day.weekday() not in b.days:
            return None
        return (b.time, True)

    return one("morning"), one("evening")


def library_for(db: Session, day: date) -> dict | None:
    raw = get_kv(db, KV_LIBRARY, None)
    if not isinstance(raw, dict) or not raw.get("enabled") or str(raw.get("date") or "")[:10] != day.isoformat():
        return None
    items = list(db.scalars(select(LibraryItem).where(LibraryItem.deleted.is_(False), LibraryItem.done.is_(False)).limit(3)))
    parts = []
    for it in items:
        ch = ", ".join(str(c) for c in (it.chapters or [])[:2])
        parts.append(f"{it.book}" + (f" ({ch})" if ch else ""))
    detail = "Read: " + "; ".join(parts) if parts else "Library visit: read your chosen chapters and photograph key pages"
    return {"detail": detail[:200]}


def adjustments_for(db: Session, day: date) -> dict:
    """plan.adjustments if `day` falls in its week, else neutral."""
    raw = get_kv(db, KV_ADJUST, None)
    if not isinstance(raw, dict):
        return {}
    try:
        start = date.fromisoformat(str(raw.get("week_start", ""))[:10])
    except ValueError:
        return {}
    return raw if 0 <= (day - start).days <= 6 else {}


# ---------------------------------------------------------------------------------------------- history
def _plans_by_date(db: Session, start: date, end: date) -> dict[str, DailyPlan]:
    rows = db.scalars(
        select(DailyPlan).where(DailyPlan.deleted.is_(False), DailyPlan.date >= start.isoformat(), DailyPlan.date <= end.isoformat())
        .order_by(DailyPlan.updated_at)
    )
    return {p.date: p for p in rows}  # the newest row of a date wins


def _is_done(plan: DailyPlan, block_id: str) -> bool:
    return (plan.completion_json or {}).get(block_id) in STUDY_MINUTES_DONE


def count_missed_days(db: Session, today: date) -> int:
    """Days in a row (counting back from yesterday) that had a plan with study hours and nothing ticked."""
    plans = _plans_by_date(db, today - timedelta(days=HISTORY_DAYS), today - timedelta(days=1))
    missed = 0
    for i in range(1, HISTORY_DAYS + 1):
        p = plans.get((today - timedelta(days=i)).isoformat())
        if p is None or not p.blocks_json:
            break
        if any(_is_done(p, b.get("id", "")) for b in p.blocks_json):
            break
        missed += 1
    return missed


def build_queue(
    db: Session, topics: list[eng.TopicInfo], today: date, pace_factor: float, adjust_topics: list[str],
) -> tuple[list[eng.Work], list[dict], dict[date, set[str]]]:
    """Work still to do, the unfinished study blocks of the last 7 days, and the subjects studied on each past day."""
    queue: dict[str, eng.Work] = {}
    for t in topics:
        if t.status in ("not_started", "in_progress"):
            queue[t.id] = eng.Work(topic=t, remaining=eng.estimate_minutes(t, pace_factor))
    by_id = {t.id: t for t in topics}
    for tid in adjust_topics:
        if tid in by_id and tid not in queue:  # weak topic the owner wants again: a short extra session
            queue[tid] = eng.Work(topic=by_id[tid], remaining=45.0)
        if tid in queue:
            queue[tid].focus = True

    plans = _plans_by_date(db, today - timedelta(days=HISTORY_DAYS), today - timedelta(days=1))
    missed: list[dict] = []
    studied: dict[date, set[str]] = {}
    for key in sorted(plans):
        plan = plans[key]
        day = date.fromisoformat(key)
        for b in plan.blocks_json or []:
            if b.get("kind") != "study":
                continue
            tid = b.get("topic_id")
            if _is_done(plan, b.get("id", "")):
                if tid in by_id:
                    studied.setdefault(day, set()).add(by_id[tid].subject)
                if tid in queue:
                    queue[tid].remaining -= float(b.get("minutes", 0))
                    if queue[tid].remaining <= 5:
                        del queue[tid]
            elif tid:
                missed.append(b)
    return list(queue.values()), missed, studied


# ---------------------------------------------------------------------------------------------- one or many days
def _save_plan(db: Session, day: date, blocks: list[dict], summary: str) -> DailyPlan:
    row = db.scalar(select(DailyPlan).where(DailyPlan.date == day.isoformat(), DailyPlan.deleted.is_(False)).order_by(DailyPlan.updated_at.desc()))
    ids = {b["id"] for b in blocks}
    if row is None:
        row = DailyPlan(date=day.isoformat(), blocks_json=blocks, completion_json={}, summary=summary[:300])
        db.add(row)
    else:
        row.blocks_json = blocks
        row.summary = summary[:300]
        row.completion_json = {k: v for k, v in (row.completion_json or {}).items() if k in ids}
    db.flush()
    return row


def plan_range(db: Session, settings, start: date, days: int = 7, now: datetime | None = None) -> list[DailyPlan]:
    """Plans `days` days from `start` (never before today) and saves them. Returns the rows."""
    now = now or datetime.now(timezone.utc)
    today = today_ist(now)
    start = max(start, today)
    days = max(1, min(days, MAX_AHEAD_DAYS))
    dates = [start + timedelta(days=i) for i in range(days)]
    # the simulation always begins today, so a day further ahead knows what earlier days already covered
    sim_dates = [today + timedelta(days=i) for i in range((dates[-1] - today).days + 1)]

    hours = _hours(db)
    exams = load_exams(db)
    weights_cache: dict[date, dict[str, float]] = {}
    topics, _ = load_topics(db)
    pace_factor = pace(db, today)
    per_card = card_minutes(db, today)
    slot = str(get_kv(db, rev.KV_SLOT, "18:00"))
    max_cards = rev.load_config(db).max_cards
    sunday_review = bool(get_kv(db, rev.KV_SUNDAY, True))
    telugu = get_kv(db, KV_TELUGU, 15)
    try:
        telugu_min = max(int(telugu), 0)
    except (TypeError, ValueError):
        telugu_min = 15
    due = rev.due_counts(db, sim_dates, now)
    existing = _plans_by_date(db, today, sim_dates[-1])

    focus_ids = sorted({str(t) for d in sim_dates for t in (adjustments_for(db, d).get("focus_topic_ids") or [])})
    queue, missed, studied = build_queue(db, topics, today, pace_factor, focus_ids)
    carried = eng.catch_up_queue(missed, queue)
    missed_days = count_missed_days(db, today)

    exam_days_all = eng.exam_days(exams, today)
    saved: list[DailyPlan] = []
    history = {d: set(s) for d, s in studied.items()}
    for i, day in enumerate(sim_dates):
        adj = adjustments_for(db, day)
        preserved = []
        cur = existing.get(day.isoformat())
        if cur is not None:
            preserved = [b for b in (cur.blocks_json or []) if _is_done(cur, b.get("id", ""))]
        # Adjustments only steer the days of their week: focus flags apply that week only
        for w in queue:
            w.focus = bool(adj) and w.topic.id in (adj.get("focus_topic_ids") or [])
        morning, evening = brief_slots(db, day)
        n_cards, groups = due.get(day, (0, []))
        last_month = any(d is not None and 0 <= d <= LAST_MONTH_DAYS for d in exam_days_all.values())
        extra = int(adj.get("extra_revision_minutes", 0) or 0) + (30 if last_month else 0)
        d_in = eng.DayInput(
            day=day, hours=eng.hours_for(day, hours), morning=morning, evening=evening, revision_slot=slot,
            telugu_minutes=telugu_min, library=library_for(db, day), due_cards=n_cards, groups=[(g, n) for g, n in groups[:3]],
            minutes_per_card=per_card, max_cards=max_cards, sunday_review=sunday_review, extra_revision=extra,
            reduce_new=bool(adj.get("reduce_new_topics")), preserved=preserved,
        )
        blocked = _blocked(history, day)
        weights = weights_cache.setdefault(day, eng.exam_weights(exams, day))
        base, studied_topics = eng.plan_day(d_in, queue, blocked, day, exams, weights)
        history.setdefault(day, set()).update(t.subject for t in studied_topics)
        base_ids = {b["id"] for b in base}
        ctx = {"exam_days": {k: v for k, v in exam_days_all.items() if v is not None}, "last_month": last_month, "settings": settings}
        blocks = run_postprocessors(db, day.isoformat(), list(base), ctx)
        protected = {b["id"] for b in blocks if b["id"] not in base_ids} | {b.get("id") for b in preserved}
        blocks, _over = eng.fit_to_hours(blocks, int(round(d_in.hours * 60)), protected)
        blocks.sort(key=lambda b: (b.get("start", "99:99"), b.get("id", "")))
        notes: list[str] = []
        if i == 0 and carried:
            notes.append(f"{carried} unfinished topic{'s' if carried != 1 else ''} from earlier days come first.")
        if i == 0 and missed_days >= 3:
            notes.append(f"You missed {missed_days} days in a row, so this week is planned again from today. Start gently, one block at a time.")
        if last_month:
            notes.append("Last month before an exam: extra revision time is added.")
        summary = eng.summary_text(int(d_in.hours * 60), eng.counted_minutes(blocks), studied_topics, n_cards, notes, d_in.hours)
        if day in dates:
            saved.append(_save_plan(db, day, blocks, summary))
        for w in queue:
            w.carried = False
        queue[:] = [w for w in queue if w.remaining > 5]
    return saved


def _blocked(history: dict[date, set[str]], day: date) -> set[str]:
    """Subjects studied on BOTH of the two days before `day` (they rest today while other subjects exist)."""
    a = history.get(day - timedelta(days=1), set())
    b = history.get(day - timedelta(days=2), set())
    return a & b


def ensure_plan(db: Session, settings, day: date, now: datetime | None = None) -> DailyPlan | None:
    """The stored plan of a day; a day from today on that has none yet is planned (with the days before it)."""
    key = day.isoformat()
    row = db.scalar(select(DailyPlan).where(DailyPlan.date == key, DailyPlan.deleted.is_(False)).order_by(DailyPlan.updated_at.desc()))
    today = today_ist(now)
    if row is not None or day < today or (day - today).days > MAX_AHEAD_DAYS:
        return row
    plan_range(db, settings, today, (day - today).days + 1, now)
    return db.scalar(select(DailyPlan).where(DailyPlan.date == key, DailyPlan.deleted.is_(False)).order_by(DailyPlan.updated_at.desc()))


def set_completion(db: Session, day: date, block_id: str, status: str) -> DailyPlan | None:
    row = db.scalar(select(DailyPlan).where(DailyPlan.date == day.isoformat(), DailyPlan.deleted.is_(False)).order_by(DailyPlan.updated_at.desc()))
    if row is None:
        return None
    comp = dict(row.completion_json or {})
    if status in ("done", "skipped"):
        comp[block_id] = status
    else:
        comp.pop(block_id, None)
    row.completion_json = comp
    db.flush()
    return row


def plan_dict(row: DailyPlan) -> dict:
    return {
        "id": row.id, "date": row.date, "blocks": row.blocks_json or [], "completion": row.completion_json or {},
        "summary": row.summary or "", "minutes": eng.counted_minutes(row.blocks_json or []),
    }


def exam_countdown(db: Session, now: datetime | None = None) -> list[dict]:
    today = today_ist(now)
    out = []
    for e in load_exams(db):
        days = None if e.on is None else (e.on - today).days
        out.append({"name": e.name, "stage": e.stage, "date": e.on.isoformat() if e.on else None, "days_left": days})
    return out


__all__ = ["plan_range", "ensure_plan", "set_completion", "plan_dict", "exam_countdown", "count_missed_days", "today_ist"]
