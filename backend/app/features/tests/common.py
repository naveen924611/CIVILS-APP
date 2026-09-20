"""Small helpers shared by the tests and reports features (dates in India time, subjects, scoring)."""
import re
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from app.db.util import as_utc

IST = ZoneInfo("Asia/Kolkata")
DAY_NAMES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
NEGATIVE_FRACTION = 1 / 3  # marks lost for each wrong answer when negative marking is on


def today_ist() -> date:
    return datetime.now(IST).date()


def to_ist_date(dt: datetime | None) -> date | None:
    """A stored UTC time (naive from SQLite or aware) as a date in India."""
    d = as_utc(dt)
    return d.astimezone(IST).date() if d else None


def ist_to_utc(day: date, hour: int = 0, minute: int = 0) -> datetime:
    return datetime.combine(day, time(hour, minute), tzinfo=IST).astimezone(timezone.utc)


def monday_of(day: date) -> date:
    return day - timedelta(days=day.weekday())


def parse_day(text: str | None) -> date | None:
    try:
        return date.fromisoformat(str(text)[:10])
    except (TypeError, ValueError):
        return None


def day_name_to_index(name: str | None, default: int = 6) -> int:
    key = (name or "").strip().lower()[:3]
    return DAY_NAMES.index(key) if key in DAY_NAMES else default


def norm_text(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (text or "").lower()).strip()


def subject_of(topic_id: str | None, topics: dict) -> str:
    """The subject (level 1 ancestor) a topic belongs to; 'Other' when unknown."""
    if not topic_id or topic_id not in topics:
        return "Other"
    chain = []
    node = topics.get(topic_id)
    seen = set()
    while node is not None and node.id not in seen:
        seen.add(node.id)
        chain.append(node)
        node = topics.get(node.parent_id) if node.parent_id else None
    for n in chain:
        if n.level == 1:
            return n.title
    below_root = [n for n in chain if n.level != 0]
    return (below_root[-1] if below_root else chain[0]).title


def marks(correct: int, wrong: int, negative: bool) -> float:
    """Score of a test: 1 mark per right answer, minus 1/3 per wrong answer when negative marking is on."""
    return round(correct - (wrong * NEGATIVE_FRACTION if negative else 0.0), 2)
