"""Collects one month of the owner's material into a markdown digest (no AI needed, so it always works).

Sources: news items that went into the briefs of that month (the AI-summarised current affairs), notes changed in the
month, and mistakes still to revisit. The result is stored in `compilations` (content_md, syncs to the tablet).
"""
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import Brief, NewsItem
from app.db.models_v2 import Compilation, Mcq, Mistake, Note, Topic

MAX_STORIES = 80
MAX_FACTS = 60
MAX_NOTES = 15
MAX_MISTAKES = 20
SUMMARY_CHARS = 420

MONTH_NAMES = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
               "November", "December"]


def valid_month(month: str) -> bool:
    try:
        datetime.strptime(month, "%Y-%m")
    except (TypeError, ValueError):
        return False
    return len(month) == 7


def month_title(month: str) -> str:
    year, num = month.split("-")
    return f"Current affairs digest: {MONTH_NAMES[int(num) - 1]} {year}"


def previous_month(today: date) -> str:
    last = today.replace(day=1) - timedelta(days=1)
    return f"{last.year:04d}-{last.month:02d}"


def bounds(month: str, tz_name: str) -> tuple[datetime, datetime]:
    """[start, end) of the month in India time, as UTC datetimes."""
    tz = ZoneInfo(tz_name)
    year, num = (int(x) for x in month.split("-"))
    start = datetime.combine(date(year, num, 1), time.min, tzinfo=tz)
    nxt = date(year + (num == 12), num % 12 + 1, 1)
    end = datetime.combine(nxt, time.min, tzinfo=tz)
    return start.astimezone(timezone.utc), end.astimezone(timezone.utc)


def _clip(text: str, limit: int) -> str:
    text = " ".join((text or "").split())
    return text if len(text) <= limit else text[: limit - 3].rstrip() + "..."


def _rel(item: NewsItem) -> int:
    return max(item.relevance_upsc or 0, item.relevance_appsc or 0)


def collect(db: Session, settings: Settings, month: str) -> dict:
    start, end = bounds(month, settings.timezone)
    ids: list[str] = []
    for brief in db.scalars(select(Brief).where(
        Brief.deleted.is_(False), Brief.status == "ready", Brief.scheduled_for >= start, Brief.scheduled_for < end,
    )):
        ids += [str(i) for i in brief.item_ids or []]
    query = select(NewsItem).where(NewsItem.deleted.is_(False))
    if ids:
        query = query.where(NewsItem.id.in_(set(ids)))
    else:  # no briefs that month: use what was collected
        query = query.where(NewsItem.hidden.is_(False), or_(
            (NewsItem.published_at >= start) & (NewsItem.published_at < end),
            NewsItem.published_at.is_(None) & (NewsItem.fetched_at >= start) & (NewsItem.fetched_at < end),
        ))
    stories = sorted(db.scalars(query), key=lambda n: (-_rel(n), n.title))[:MAX_STORIES]

    notes = []
    for note in db.scalars(select(Note).where(
        Note.deleted.is_(False), Note.updated_at >= start, Note.updated_at < end, Note.status == "ready",
    ).order_by(Note.updated_at.desc()).limit(MAX_NOTES)):
        topic = db.get(Topic, note.topic_id)
        sections = note.sections or {}
        points = [p if isinstance(p, str) else str((p or {}).get("text", "")) for p in sections.get("key_points") or []]
        notes.append({
            "topic": topic.title if topic else "Notes",
            "overview": _clip(str(sections.get("overview") or ""), 300),
            "points": [_clip(p, 200) for p in points if p.strip()][:4],
        })

    mistakes = []
    for m in db.scalars(select(Mistake).where(
        Mistake.deleted.is_(False), Mistake.resolved.is_(False), Mistake.updated_at >= start, Mistake.updated_at < end,
    ).order_by(Mistake.updated_at.desc()).limit(MAX_MISTAKES)):
        q = db.get(Mcq, m.mcq_id)
        if q is None or q.deleted:
            continue
        opts = q.options or []
        answer = str(opts[q.answer_index]) if 0 <= q.answer_index < len(opts) else ""
        mistakes.append({"question": _clip(q.question, 300), "answer": _clip(answer, 200),
                         "explanation": _clip(q.explanation, 300)})
    return {"stories": stories, "notes": notes, "mistakes": mistakes}


def _paper(item: NewsItem) -> str:
    papers = [str(p).strip() for p in item.papers or [] if str(p).strip()]
    return papers[0] if papers else "Other"


def render(month: str, data: dict, tz_name: str) -> str:
    tz = ZoneInfo(tz_name)
    stories: list[NewsItem] = data["stories"]
    lines = [f"# {month_title(month)}", ""]
    lines.append(
        f"{len(stories)} stories, {len(data['notes'])} notes updated, {len(data['mistakes'])} mistakes to revisit."
    )
    lines.append("")
    if stories:
        groups: dict[str, list[NewsItem]] = {}
        for s in stories:
            groups.setdefault(_paper(s), []).append(s)
        lines += ["## Top stories by paper", ""]
        for paper in sorted(groups, key=lambda p: (p == "Other", p)):
            lines += [f"### {paper}", ""]
            for s in groups[paper]:
                when = ""
                if s.published_at:
                    stamp = s.published_at if s.published_at.tzinfo else s.published_at.replace(tzinfo=timezone.utc)
                    when = stamp.astimezone(tz).strftime("%d %b")
                lines.append(f"- **{_clip(s.title, 160)}**" + (" (AP)" if s.is_ap_specific else ""))
                if s.summary:
                    lines.append(f"  {_clip(s.summary, SUMMARY_CHARS)}")
                if s.mains_angle:
                    lines.append(f"  Mains angle: {_clip(s.mains_angle, 240)}")
                source = " ".join(x for x in (s.source, when) if x)
                if source:
                    lines.append(f"  Source: {source}")
            lines.append("")
        facts = []
        for s in stories:
            for f in s.prelims_facts or []:
                if isinstance(f, dict) and f.get("q") and f.get("a"):
                    facts.append((str(f["q"]), str(f["a"])))
        if facts:
            lines += ["## Prelims quick facts", ""]
            lines += [f"- {_clip(q, 200)} **{_clip(a, 160)}**" for q, a in facts[:MAX_FACTS]]
            lines.append("")
        ap = [s for s in stories if s.is_ap_specific]
        if ap:
            lines += ["## Andhra Pradesh this month", ""]
            lines += [f"- {_clip(s.title, 160)}" for s in ap]
            lines.append("")
    if data["notes"]:
        lines += ["## From your notes this month", ""]
        for n in data["notes"]:
            lines.append(f"### {n['topic']}")
            if n["overview"]:
                lines.append(n["overview"])
            lines += [f"- {p}" for p in n["points"]]
            lines.append("")
    if data["mistakes"]:
        lines += ["## Mistakes to revisit", ""]
        for m in data["mistakes"]:
            lines.append(f"- {m['question']}")
            if m["answer"]:
                lines.append(f"  Right answer: {m['answer']}")
            if m["explanation"]:
                lines.append(f"  Why: {m['explanation']}")
        lines.append("")
    if not stories and not data["notes"] and not data["mistakes"]:
        lines += ["Nothing was collected for this month yet.", ""]
    return "\n".join(lines).rstrip() + "\n"


def is_empty(data: dict) -> bool:
    return not (data["stories"] or data["notes"] or data["mistakes"])


def build_month(db: Session, settings: Settings, month: str, allow_empty: bool = True) -> Compilation | None:
    """Creates or refreshes the compilation of `month` (text and PDF). None when empty and not allowed."""
    from . import pdf

    data = collect(db, settings, month)
    if is_empty(data) and not allow_empty:
        return None
    md = render(month, data, settings.timezone)
    row = db.scalar(select(Compilation).where(Compilation.month == month, Compilation.deleted.is_(False)))
    if row is None:
        row = Compilation(month=month)
        db.add(row)
    row.title, row.content_md = month_title(month), md
    row.pdf_path = pdf.write_pdf(settings, month, row.title, md)
    row.status = "ready"
    db.commit()
    return row
