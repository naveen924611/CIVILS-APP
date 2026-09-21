"""The weekly report (spec 6.13): hours planned vs done, topics finished, cards revised, question accuracy, what was
covered, weak spots, and how next week adjusts. Everything is counted from the owner's own data (no AI needed); the AI
only turns the numbers into a short spoken summary, with a plain fallback text when it is not available.

data_json shape (also read by the tablet, see ui/report):
  week_start, week_end, generated_at, narrative, script,
  hours {planned_minutes, done_minutes, focus_minutes, by_day [{date, day, planned, done}]},
  topics_finished [{topic_id, title}], covered [{topic_id, title, subject}],
  cards {revised, distinct, fading},
  mcq {attempted, correct, accuracy, tests [{title, score, total}]},
  weak_spots {topics [{topic_id, title, strength}], fading_cards, low_days [names]},
  next_week {week_start, adjustments {week_start, extra_revision_minutes, focus_topic_ids, reduce_new_topics}, changes [text]},
  last_month {active, exam, days_left}
"""
import logging
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import Card
from app.db.models_v2 import Attempt, DailyPlan, Exam, FocusSession, Review, Test, Topic, WeeklyReport
from app.db.util import as_utc
from app.features.tests.common import DAY_NAMES, IST, ist_to_utc, monday_of, parse_day, subject_of, to_ist_date
from app.llm.promptlib import load, render

from . import pdf

log = logging.getLogger(__name__)
LAST_MONTH_DAYS = 30
DONE_STATES = ("studied", "revised", "strong")
WEAK_BELOW = 0.5
FADING_DAYS = 2
FULL_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def _in_week(dt: datetime | None, start: datetime, end: datetime) -> bool:
    d = as_utc(dt)
    return d is not None and start <= d < end


def last_month_info(db: Session, today: date) -> dict:
    """Active when an exam with a date is 0..30 days away. The nearest one is named."""
    best: tuple[int, str] | None = None
    for e in db.scalars(select(Exam).where(Exam.deleted.is_(False))):
        if e.date is None:
            continue
        left = (to_ist_date(e.date) - today).days
        if 0 <= left <= LAST_MONTH_DAYS and (best is None or left < best[0]):
            best = (left, f"{e.name} {e.stage}".strip())
    if best is None:
        return {"active": False, "exam": "", "days_left": None}
    return {"active": True, "exam": best[1], "days_left": best[0]}


def collect(db: Session, week_start: date, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    start, end = ist_to_utc(week_start), ist_to_utc(week_start + timedelta(days=7))
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False)))}

    # hours: plan blocks planned vs ticked done, and focus-timer minutes
    by_day = []
    planned_total = done_total = 0
    covered_ids: list[str] = []
    for i in range(7):
        day = week_start + timedelta(days=i)
        plan = db.scalars(select(DailyPlan).where(DailyPlan.date == day.isoformat(), DailyPlan.deleted.is_(False))
                          .order_by(DailyPlan.updated_at.desc())).first()
        planned = done = 0
        if plan is not None:
            comp = plan.completion_json or {}
            for b in plan.blocks_json or []:
                if not isinstance(b, dict):
                    continue
                minutes = int(b.get("minutes") or 0)
                planned += minutes
                if comp.get(b.get("id")) == "done":
                    done += minutes
                    if b.get("topic_id") and b["topic_id"] not in covered_ids:
                        covered_ids.append(b["topic_id"])
        planned_total += planned
        done_total += done
        by_day.append({"date": day.isoformat(), "day": DAY_NAMES[i], "planned": planned, "done": done})
    focus = 0
    for s in db.scalars(select(FocusSession).where(FocusSession.deleted.is_(False))):
        if _in_week(s.started_at, start, end):
            focus += int(s.minutes or 0)
            if s.topic_id and s.topic_id not in covered_ids:
                covered_ids.append(s.topic_id)

    finished = [t for t in topics.values() if t.status in DONE_STATES and _in_week(t.updated_at, start, end)]
    for t in finished:
        if t.id not in covered_ids:
            covered_ids.append(t.id)

    reviews = [r for r in db.scalars(select(Review).where(Review.deleted.is_(False))) if _in_week(r.reviewed_at, start, end)]
    cutoff = now - timedelta(days=FADING_DAYS)
    fading = sum(1 for c in db.scalars(select(Card).where(Card.deleted.is_(False)))
                 if c.fsrs_state_json and c.due_at is not None and as_utc(c.due_at) < cutoff)

    attempts = [a for a in db.scalars(select(Attempt).where(Attempt.deleted.is_(False))) if _in_week(a.at, start, end)]
    answered = [a for a in attempts if a.chosen >= 0]
    correct = sum(1 for a in answered if a.correct)
    tests = []
    for t in db.scalars(select(Test).where(Test.deleted.is_(False), Test.status.in_(("done", "analysed")))):
        if _in_week(t.finished_at or t.updated_at, start, end):
            tests.append({"title": t.title, "score": t.score, "total": len(t.mcq_ids or [])})

    weak = sorted((t for t in topics.values() if t.status != "not_started" and t.level >= 1 and t.strength < WEAK_BELOW
                   and t.strength > 0), key=lambda t: t.strength)[:6]
    low_days = [FULL_NAMES[i] for i, d in enumerate(by_day) if d["planned"] > 0 and d["done"] / d["planned"] < 0.5
                and parse_day(d["date"]) <= now.astimezone(IST).date()]
    return {
        "week_start": week_start.isoformat(), "week_end": (week_start + timedelta(days=6)).isoformat(),
        "hours": {"planned_minutes": planned_total, "done_minutes": done_total, "focus_minutes": focus, "by_day": by_day},
        "topics_finished": [{"topic_id": t.id, "title": t.title} for t in finished],
        "covered": [{"topic_id": i, "title": topics[i].title, "subject": subject_of(i, topics)} for i in covered_ids if i in topics],
        "cards": {"revised": len(reviews), "distinct": len({r.card_id for r in reviews}), "fading": fading},
        "mcq": {"attempted": len(answered), "correct": correct,
                "accuracy": round(correct / len(answered), 3) if answered else 0.0, "tests": tests},
        "weak_spots": {"topics": [{"topic_id": t.id, "title": t.title, "strength": round(t.strength, 2)} for t in weak],
                       "fading_cards": fading, "low_days": low_days},
        "last_month": last_month_info(db, now.astimezone(IST).date()),
    }


def next_week_plan(data: dict) -> dict:
    """Simple rules (no AI): weak topics get focus, low completion means a lighter load, poor accuracy or fading cards
    mean more revision. `adjustments` is exactly what the tablet stores in KV plan.adjustments when the owner accepts."""
    h, weak, mcq = data["hours"], data["weak_spots"], data["mcq"]
    week = parse_day(data["week_start"]) or date.today()
    ratio = h["done_minutes"] / h["planned_minutes"] if h["planned_minutes"] else None
    extra = 0
    changes: list[str] = []
    focus = [t["topic_id"] for t in weak["topics"][:5]]
    if focus:
        changes.append("Focus on: " + ", ".join(t["title"] for t in weak["topics"][:5]) + ".")
    if weak["fading_cards"] >= 20 or (mcq["attempted"] >= 10 and mcq["accuracy"] < 0.6):
        extra = 30
    elif weak["fading_cards"] >= 5 or (mcq["attempted"] >= 10 and mcq["accuracy"] < 0.75):
        extra = 15
    if extra:
        changes.append(f"{extra} more minutes of revision each day.")
    reduce = ratio is not None and ratio < 0.6
    if reduce:
        changes.append("Fewer new topics, so you can finish what is already started.")
    if data["last_month"]["active"]:
        changes.append("Last-month mode: revise from revision sheets plus due cards.")
    if not changes:
        changes.append("Keep the same plan. Your week went steadily.")
    return {
        "week_start": (week + timedelta(days=7)).isoformat(),
        "adjustments": {"week_start": (week + timedelta(days=7)).isoformat(), "extra_revision_minutes": extra,
                        "focus_topic_ids": focus, "reduce_new_topics": bool(reduce)},
        "changes": changes,
    }


def fallback_narrative(data: dict) -> str:
    h, mcq, weak = data["hours"], data["mcq"], data["weak_spots"]
    parts = []
    if h["planned_minutes"]:
        parts.append(f"You finished {h['done_minutes']} of {h['planned_minutes']} planned study minutes this week.")
    else:
        parts.append("No plan was recorded this week.")
    if data["topics_finished"]:
        parts.append(f"You completed {len(data['topics_finished'])} topic{'s' if len(data['topics_finished']) != 1 else ''}.")
    if data["cards"]["revised"]:
        parts.append(f"You revised {data['cards']['revised']} cards.")
    if mcq["attempted"]:
        parts.append(f"Your question accuracy was {round(100 * mcq['accuracy'])} percent.")
    if weak["topics"]:
        parts.append("Your weakest topics are " + ", ".join(t["title"] for t in weak["topics"][:3]) + ".")
    return " ".join(parts)


def narrative(gateway, data: dict) -> str:
    guard = getattr(gateway, "guard", None)
    if guard is not None and hasattr(guard, "level") and guard.level() >= 2:
        return fallback_narrative(data)
    facts = "\n".join([
        f"- Study minutes done {data['hours']['done_minutes']} of {data['hours']['planned_minutes']} planned; focus timer {data['hours']['focus_minutes']} min",
        f"- Topics finished: {', '.join(t['title'] for t in data['topics_finished']) or 'none'}",
        f"- Cards revised: {data['cards']['revised']}; fading cards: {data['cards']['fading']}",
        f"- Questions answered: {data['mcq']['attempted']}, accuracy {round(100 * data['mcq']['accuracy'])} percent",
        f"- Weak topics: {', '.join(t['title'] for t in data['weak_spots']['topics']) or 'none'}",
        f"- Low days: {', '.join(data['weak_spots']['low_days']) or 'none'}",
        f"- Next week: {' '.join(data['next_week']['changes'])}",
    ])
    system, user_tpl = load("reports_weekly")
    text = gateway.generate_text(feature="weekly_report", system=system, user=render(user_tpl, week_start=data["week_start"], facts=facts),
                                 max_output_tokens=400)
    text = (text or "").strip()
    return text[:1200] if len(text) >= 40 else fallback_narrative(data)


def script_for(data: dict) -> str:
    parts = ["Your weekly report.", data.get("narrative", "")]
    if data["covered"]:
        parts.append("This week you covered " + ", ".join(c["title"] for c in data["covered"][:6]) + ".")
    parts.append("Next week. " + " ".join(data["next_week"]["changes"]))
    return " ".join(p for p in parts if p)


def report_for_week(db: Session, week_start: date) -> WeeklyReport | None:
    return db.scalars(select(WeeklyReport).where(WeeklyReport.week_start == week_start.isoformat(), WeeklyReport.deleted.is_(False))
                      .order_by(WeeklyReport.updated_at.desc())).first()


def pdf_file(settings: Settings, report: WeeklyReport) -> Path:
    return Path(settings.data_dir) / "reports" / "weekly" / f"{report.id}.pdf"


def build_report(db: Session, settings: Settings, gateway, week_start: date | None = None,
                 now: datetime | None = None) -> WeeklyReport:
    """Creates or refreshes the report of a week (default: the current Monday to Sunday week)."""
    now = now or datetime.now(timezone.utc)
    week_start = monday_of(week_start or now.astimezone(IST).date())
    data = collect(db, week_start, now)
    data["next_week"] = next_week_plan(data)
    data["narrative"] = narrative(gateway, data)
    data["script"] = script_for(data)
    data["generated_at"] = now.isoformat().replace("+00:00", "Z")
    row = report_for_week(db, week_start)
    if row is None:
        row = WeeklyReport(week_start=week_start.isoformat())
        db.add(row)
    row.data_json = data
    row.status = "ready"
    db.flush()
    try:
        pdf.render_report(data, pdf_file(settings, row))
    except Exception:
        log.exception("report PDF failed")
    db.flush()
    return row
