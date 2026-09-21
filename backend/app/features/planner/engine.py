"""The planning rules (spec 7.6) as plain functions: no database, no clock, easy to test.

The service (service.py) reads the database, calls `plan_day` once per date and saves the result.

Day template (share of the day's hours): morning brief 12 %, core study 50 %, revision 19 %, practice 13 %,
evening brief 6 %.  Sunday is lighter on new study: brief 12, core 30, revision 32 (full-week review), practice 20, evening 6.
Telugu minutes and the library-day block are taken out of core study.  Revision minutes follow the number of due cards
(bounded); what revision does not need goes to core study, what it needs beyond its share is cut from core study first,
then practice.  Revision and briefs are never cut.
"""
from __future__ import annotations

import statistics
from dataclasses import dataclass, field
from datetime import date, datetime

from app.features.examnames import has_word
from app.features.plan_hooks import counts_toward_hours

WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
DEFAULT_HOURS = {"mon": 4, "tue": 4, "wed": 4, "thu": 4, "fri": 4, "sat": 4, "sun": 3}
SHARES = {"brief_m": 0.12, "core": 0.50, "revision": 0.19, "practice": 0.13, "brief_e": 0.06}
SUNDAY_SHARES = {"brief_m": 0.12, "core": 0.30, "revision": 0.32, "practice": 0.20, "brief_e": 0.06}
DEFAULT_HORIZON_DAYS = 180  # used when an exam has no date yet
DEFAULT_MINUTES_PER_CARD = 0.7
MAX_TOPICS_PER_DAY = 3
LATEST_START = 22 * 60 + 30
KNOWN_RANK = {"not_started": 0, "in_progress": 1}


# ---------------------------------------------------------------------------------------------- small helpers
def hhmm(minutes: int) -> str:
    minutes = max(0, min(minutes, 23 * 60 + 59))
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def to_minutes(text: str, default: int) -> int:
    try:
        h, m = text.split(":")
        return int(h) * 60 + int(m)
    except (ValueError, AttributeError):
        return default


def round5(x: float) -> int:
    return int(round(x / 5.0)) * 5


def weekday_key(d: date) -> str:
    return WEEKDAYS[d.weekday()]


def hours_for(d: date, hours: dict) -> float:
    try:
        return max(0.0, float(hours.get(weekday_key(d), DEFAULT_HOURS[weekday_key(d)])))
    except (TypeError, ValueError):
        return float(DEFAULT_HOURS[weekday_key(d)])


# ---------------------------------------------------------------------------------------------- inputs
@dataclass
class ExamInfo:
    name: str
    stage: str
    on: date | None  # None = "Date not announced"
    weight: float = 1.0  # from KV exam.priority (and Exam.weight)


@dataclass
class TopicInfo:
    id: str
    title: str
    subject: str = "General"
    paper: str = ""
    exam_tags: list[str] = field(default_factory=list)
    est_hours: float = 1.0
    status: str = "not_started"
    importance: float = 0.0
    position: int = 0


@dataclass
class Work:
    """A topic still to be studied, with the minutes left in the simulation."""

    topic: TopicInfo
    remaining: float
    carried: bool = False
    focus: bool = False


@dataclass
class DayInput:
    day: date
    hours: float
    morning: tuple[str, bool] | None  # (time "HH:MM", brief exists that day)
    evening: tuple[str, bool] | None
    revision_slot: str = "18:00"
    telugu_minutes: int = 0
    library: dict | None = None  # {"detail": str} when today is the owner's library day
    due_cards: int = 0
    groups: list[tuple[str, int]] = field(default_factory=list)
    minutes_per_card: float = DEFAULT_MINUTES_PER_CARD
    max_cards: int = 80
    sunday_review: bool = True
    extra_revision: int = 0
    reduce_new: bool = False
    preserved: list[dict] = field(default_factory=list)  # blocks the owner already ticked; kept as they are
    used_ids: set[str] = field(default_factory=set)


# ---------------------------------------------------------------------------------------------- exam weights
def exam_days(exams: list[ExamInfo], today: date) -> dict[str, int | None]:
    """{exam name: days left or None}. Exams in the past are left out."""
    out: dict[str, int | None] = {}
    for e in exams:
        if e.on is None:
            out[e.name] = None
        elif (e.on - today).days >= 0:
            out[e.name] = (e.on - today).days
    return out


def exam_weights(exams: list[ExamInfo], today: date) -> dict[str, float]:
    """Relative weight per exam (sums to 1). Equal by default. In the last 8 weeks before the nearer prelims the
    nearer exam gets 70 % and the other 30 % (spec 7.6)."""
    live = [e for e in exams if e.on is None or e.on >= today]
    if not live:
        return {}
    raw = {e.name: max(e.weight, 0.0) or 1.0 for e in live}
    total = sum(raw.values())
    w = {k: v / total for k, v in raw.items()}
    dated = sorted((e for e in live if e.on is not None and "prelim" in e.stage.lower()), key=lambda e: e.on)
    if not dated:
        dated = sorted((e for e in live if e.on is not None), key=lambda e: e.on)
    if len(live) >= 2 and dated and (dated[0].on - today).days <= 56:
        near = dated[0].name
        others = [e.name for e in live if e.name != near]
        w = {near: 0.7, **{n: 0.3 / len(others) for n in others}}
    return w


def matching_exams(topic: TopicInfo, exams: list[ExamInfo]) -> list[ExamInfo]:
    if not topic.exam_tags:
        return exams
    hit = [e for e in exams if any(has_word(e.name, t) for t in topic.exam_tags)]
    return hit or exams


def topic_score(topic: TopicInfo, exams: list[ExamInfo], weights: dict[str, float], today: date) -> float:
    """0..~1.4: importance (0-10, unknown counts as 3) and how soon the exams that need it are."""
    imp = (topic.importance if topic.importance > 0 else 3.0) / 10.0
    live = [e for e in exams if e.on is None or e.on >= today]
    urgency = 0.0
    n = max(len(live), 1)
    for e in matching_exams(topic, live):
        left = DEFAULT_HORIZON_DAYS if e.on is None else max((e.on - today).days, 0)
        urgency = max(urgency, weights.get(e.name, 1.0 / n) * n / (1 + left / 90.0))
    return 0.5 * imp + 0.4 * urgency


def exam_label(topic: TopicInfo) -> str:
    return " + ".join(topic.exam_tags) if topic.exam_tags else "both exams"


# ---------------------------------------------------------------------------------------------- pace
def pace_factor(sessions: list[tuple[float, float]]) -> float:
    """sessions = [(minutes, completion_pct)] of recent focus sessions. If the owner usually finishes 60 % of what a
    session was for, topics take 1/0.6 as long as estimated. Between 1.0 and 1.75; 1.0 without enough data (< 60 min)."""
    total = sum(m for m, _ in sessions if m > 0)
    if total < 60:
        return 1.0
    done = sum(m * min(max(p, 5.0), 100.0) for m, p in sessions if m > 0) / total / 100.0
    return min(max(1.0 / done, 1.0), 1.75)


def minutes_per_card(review_times: list[datetime]) -> float:
    """Median seconds between consecutive reviews inside one sitting (gaps under 3 minutes) -> minutes per card."""
    ts = sorted(review_times)
    gaps = [(b - a).total_seconds() for a, b in zip(ts, ts[1:], strict=False) if 0 < (b - a).total_seconds() <= 180]
    if len(gaps) < 20:
        return DEFAULT_MINUTES_PER_CARD
    return min(max(statistics.median(gaps) / 60.0, 0.25), 2.0)


# ---------------------------------------------------------------------------------------------- one day
def _pick(
    queue: list[Work], minutes: int, blocked: set[str], today: date, exams: list[ExamInfo], weights: dict[str, float]
) -> list[tuple[Work, int]]:
    """Chooses topics for `minutes` of core study. Order: focus topics, carried-over items, in-progress topics, then
    score (importance x exam proximity). Subjects studied on both of the last two days are skipped while others exist."""

    def key(w: Work):
        t = w.topic
        return (
            not w.focus, not w.carried, KNOWN_RANK.get(t.status, 0) == 0,
            -topic_score(t, exams, weights, today), t.position, t.title,
        )

    ordered = sorted(queue, key=key)
    preferred = [w for w in ordered if w.topic.subject not in blocked]
    rest = [w for w in ordered if w.topic.subject in blocked]
    picks: list[tuple[Work, int]] = []
    left = minutes
    for w in preferred + rest:
        if left < 15 or len(picks) >= MAX_TOPICS_PER_DAY:
            break
        take = int(min(w.remaining, left))
        if take < 15:
            take = int(min(left, 15))
        if left - take < 20:
            take = left  # absorb a small leftover instead of leaving a tiny gap
        w.remaining -= take
        picks.append((w, take))
        left -= take
    return picks


def _allocate(d: DayInput) -> dict[str, int]:
    """Minutes per kind for the day. Keys: brief_m, brief_e, core, library, telugu, revision, practice."""
    total = int(round(d.hours * 60))
    shares = SUNDAY_SHARES if d.day.weekday() == 6 else SHARES
    brief_m = round5(total * shares["brief_m"]) if d.morning and d.morning[1] else 0
    brief_e = round5(total * shares["brief_e"]) if d.evening and d.evening[1] else 0
    core = total * shares["core"] + (total * shares["brief_m"] - brief_m) + (total * shares["brief_e"] - brief_e)
    practice = total * shares["practice"]
    base_rev = total * shares["revision"]

    need = d.due_cards * d.minutes_per_card
    rev = min(max(need, 10 if d.due_cards else 0), 0.40 * total)
    if d.day.weekday() == 6 and d.sunday_review and d.due_cards:
        rev = max(rev, base_rev)  # Sunday full-week review keeps its whole slot
    rev += d.extra_revision
    if rev < base_rev:
        core += base_rev - rev
    elif rev > base_rev:
        extra = rev - base_rev
        first = min(extra, max(core - 0.3 * total * shares["core"], 0))
        core -= first
        extra -= first
        second = min(extra, practice)
        practice -= second
        extra -= second
        core = max(core - extra, 0)
    if d.reduce_new:
        cut = core * 0.25
        core -= cut
        rev += cut
    telugu = min(d.telugu_minutes, int(core)) if d.telugu_minutes > 0 else 0
    core -= telugu
    library = 0
    if d.library:
        library = round5(core * 0.6)
        core -= library
    # things the owner already ticked stay as they are; their minutes are taken off the day's budget
    ids = {b.get("id", "") for b in d.preserved}
    kinds = {b.get("kind") for b in d.preserved}
    budget = total - sum(int(b.get("minutes", 0)) for b in d.preserved)
    out = {
        "brief_m": 0 if any(i.endswith("morning-brief") for i in ids) else brief_m,
        "brief_e": 0 if any(i.endswith("evening-brief") for i in ids) else brief_e,
        "telugu": 0 if "telugu" in kinds else round5(telugu),
        "library": 0 if "library" in kinds else library,
        "revision": 0 if "revision" in kinds else round5(rev),
        "practice": 0 if "practice" in kinds else round5(practice),
    }
    out["core"] = max(budget - sum(out.values()), 0)  # whatever is left, so the day adds up to its hours
    # a practice or library block shorter than 15 minutes is not worth a slot: give it to core study
    for k in ("practice", "library"):
        if 0 < out[k] < 15:
            out["core"] += out[k]
            out[k] = 0
    return out


def _uid(d: DayInput, base: str) -> str:
    bid, n = base, 2
    while bid in d.used_ids:
        bid, n = f"{base}-{n}", n + 1
    d.used_ids.add(bid)
    return bid


def plan_day(
    d: DayInput, queue: list[Work], blocked: set[str], today: date, exams: list[ExamInfo], weights: dict[str, float],
) -> tuple[list[dict], list[TopicInfo]]:
    """Base blocks for one day (before other features add theirs). Returns (blocks, topics studied that day)."""
    date_s = d.day.isoformat()
    d.used_ids |= {b.get("id", "") for b in d.preserved}
    if d.hours <= 0:
        return list(d.preserved), []
    mins = _allocate(d)
    blocks: list[dict] = []

    def block(kind: str, slug: str, minutes: int, title: str, detail: str, topic_id: str | None, ref: str | None, start: int):
        return {
            "id": _uid(d, f"{date_s}-{slug}"), "kind": kind, "start": hhmm(start), "minutes": int(minutes),
            "title": title, "detail": detail, "topic_id": topic_id, "ref": ref,
        }

    morning_start = to_minutes(d.morning[0], 7 * 60) if d.morning else 7 * 60
    evening_start = to_minutes(d.evening[0], 19 * 60) if d.evening else 19 * 60
    rev_start = to_minutes(d.revision_slot, 18 * 60)

    if mins["brief_m"]:
        blocks.append(block("brief", "morning-brief", mins["brief_m"], "Morning brief",
                            "Today's current affairs · listen or read · a short quiz", None, "briefs", morning_start))
    if mins["brief_e"]:
        blocks.append(block("brief", "evening-brief", mins["brief_e"], "Evening brief",
                            "Recap of the day + quiz · you'll get a notification", None, "briefs", evening_start))

    # revision
    if mins["revision"]:
        n = d.due_cards
        if n:
            names = ", ".join(g for g, _ in d.groups[:3])
            detail = f"{n} cards due" + (f" · {names}" if names else "")
        else:
            detail = "Go through your weakest notes and cards"
        if d.day.weekday() == 6 and d.sunday_review:
            detail = "Full-week review · " + detail
        blocks.append(block("revision", "revision", mins["revision"], "Revision", detail, None, "revise/session", rev_start))

    # daytime blocks in order: library, core topics, Telugu, practice
    pending: list[dict] = []
    picks: list[tuple[Work, int]] = []
    if mins["library"]:
        pending.append(block("library", "library", mins["library"], "Library day",
                             (d.library or {}).get("detail", "Read the chapters on your library list"), None, "library", 0))
    if mins["core"] >= 15:
        picks = _pick(queue, mins["core"], blocked, today, exams, weights)
        if picks:
            for w, m in picks:
                t = w.topic
                extra = " · carried over from an earlier day" if w.carried else ""
                pending.append(block("study", f"study-{t.id[:8]}", m, t.title,
                                     f"Core study · {t.paper or t.subject} · {exam_label(t)}{extra}", t.id, f"notes/{t.id}", 0))
        else:
            pending.append(block("study", "study", mins["core"], "Core study",
                                 "Pick a topic from the syllabus map and read or listen", None, "syllabus", 0))
    leftover = mins["core"] - sum(m for _, m in picks)
    if picks and leftover >= 15:  # the list of topics ran short: keep the time, but say so honestly
        pending.append(block("study", "study-extra", leftover, "Extra study time",
                             "Not many topics are left to plan: read your notes on a weak topic, or add topics in the syllabus map",
                             None, "syllabus", 0))
    if mins["telugu"]:
        pending.append(block("telugu", "telugu", mins["telugu"], "Telugu practice",
                             "Vocabulary, reading and one short writing task", None, "telugu", 0))
    if mins["practice"]:
        q = max(5, round5(mins["practice"] / 1.5))
        main_topic = picks[0][0].topic if picks else None
        what = f" on {main_topic.subject}" if main_topic else ""
        pending.append(block("practice", "practice", mins["practice"], "Practice",
                             f"About {q} past-paper style questions{what}", main_topic.id if main_topic else None, "tests", 0))

    # start times: from the end of the morning brief, stepping around the revision slot
    rev_min = mins["revision"]
    cursor = morning_start + mins["brief_m"] if mins["brief_m"] else max(morning_start, 7 * 60 + 30)
    for b in pending:
        m = b["minutes"]
        if rev_min and cursor < rev_start + rev_min and cursor + m > rev_start:
            cursor = rev_start + rev_min
        b["start"] = hhmm(min(cursor, LATEST_START))
        cursor += m
    blocks.extend(pending)
    blocks = list(d.preserved) + blocks
    blocks.sort(key=lambda b: (b.get("start", "99:99"), b.get("id", "")))
    return blocks, [w.topic for w, _ in picks]


def counted_minutes(blocks: list[dict]) -> int:
    """Minutes of the blocks that count toward the day's study hours (physical training does not)."""
    return sum(int(b.get("minutes", 0)) for b in blocks if counts_toward_hours(b))


def fit_to_hours(blocks: list[dict], total_minutes: int, protected: set[str]) -> tuple[list[dict], int]:
    """After other features added blocks: if the day is over its hours, cut new study first, then practice.
    Revision, briefs, the Telugu block and blocks of other features are never cut. Returns (blocks, minutes still over).
    Blocks whose id starts with "phys-" (physical training) are not counted toward the hours."""
    over = counted_minutes(blocks) - total_minutes
    if over <= 0:
        return blocks, 0
    for kind, floor in (("study", 15), ("practice", 10)):
        for b in reversed(blocks):
            if over <= 0:
                break
            if b.get("kind") != kind or b.get("id") in protected:
                continue
            cut = min(over, max(int(b["minutes"]) - floor, 0))
            b["minutes"] = int(b["minutes"]) - cut
            over -= cut
    kept = [b for b in blocks if not (b.get("kind") in ("study", "practice") and b["minutes"] < 10 and b.get("id") not in protected)]
    return kept, max(over, 0)


def catch_up_queue(missed: list[dict], queue: list[Work]) -> int:
    """Marks topics of unfinished study blocks from earlier days as carried over (they go first). Returns how many."""
    by_id = {w.topic.id: w for w in queue}
    count = 0
    for b in missed:
        w = by_id.get(b.get("topic_id") or "")
        if w is None:
            continue
        if not w.carried:
            w.remaining = min(max(float(b.get("minutes", 30)), 30.0), max(w.remaining, 30.0))
            count += 1
        else:
            w.remaining = min(w.remaining + float(b.get("minutes", 0)), max(w.remaining, 30.0))
        w.carried = True
    return count


def summary_text(
    total_min: int, planned_min: int, topics: list[TopicInfo], due_cards: int, notes: list[str], hours: float
) -> str:
    if hours <= 0:
        return "Rest day: no study planned. " + " ".join(notes)
    names = ", ".join(dict.fromkeys(t.title for t in topics[:3]))
    bits = [f"{planned_min / 60:.1f} h planned"]
    if names:
        bits.append(f"study: {names}")
    if due_cards:
        bits.append(f"{due_cards} cards due")
    text = " · ".join(bits)
    if notes:
        text += ". " + " ".join(notes)
    return text[:300]


def estimate_minutes(topic: TopicInfo, pace: float) -> float:
    base = max(topic.est_hours, 0.25) * 60.0 * pace
    return base * (0.5 if topic.status == "in_progress" else 1.0)
