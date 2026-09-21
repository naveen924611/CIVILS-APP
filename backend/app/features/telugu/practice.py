"""Rules for the daily Telugu practice (pure functions, no database).

The tablet uses the SAME rules on its own copy of the data (ui/telugu/TeluguPlan.kt), so practice also works offline.
Keep the two in step when you change anything here.

Daily set for `minutes` (default 15, KV `study.telugu_minutes`):
    vocab        (minutes + 1) // 2 words, at least 4       (about 30 seconds each)
    passage      1 when minutes >= 10                       (about 5 minutes)
    translation  1 when minutes >= 10, 2 when minutes >= 20
    template     1 on Wednesday and Saturday when minutes >= 15 (a letter or essay to write)
Which items: those already done today come first (so the list does not change during the day). Then, per kind, with
`room` free places: up to room // 2 go to items that need work (the last score was below 0.6), oldest practice first;
the rest go to never-done items in file order, then to well-done items (oldest first), then to any leftover
needs-work items.
"""
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

NEEDS_WORK_BELOW = 0.6
KIND_ORDER = ("vocab", "passage", "translation", "template")


@dataclass(frozen=True)
class Item:
    id: str
    kind: str
    position: int


@dataclass(frozen=True)
class Done:
    item_id: str
    score: float | None
    on: date  # study day (India) of the practice
    at: str  # ISO time, used to find the latest


def quotas(minutes: int, weekday: int) -> dict[str, int]:
    """weekday: Monday = 0 ... Sunday = 6."""
    minutes = max(0, int(minutes))
    if minutes <= 0:
        return {k: 0 for k in KIND_ORDER}
    return {
        "vocab": max(4, (minutes + 1) // 2),
        "passage": 1 if minutes >= 10 else 0,
        "translation": 2 if minutes >= 20 else (1 if minutes >= 10 else 0),
        "template": 1 if minutes >= 15 and weekday in (2, 5) else 0,
    }


def study_day(moment: datetime, tz: str) -> date:
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=ZoneInfo("UTC"))
    return moment.astimezone(ZoneInfo(tz)).date()


def latest_by_item(history: list[Done]) -> dict[str, Done]:
    latest: dict[str, Done] = {}
    for h in sorted(history, key=lambda d: d.at):
        latest[h.item_id] = h
    return latest


def pick_today(items: list[Item], history: list[Done], today: date, minutes: int) -> list[Item]:
    """The items for `today`, grouped by kind in KIND_ORDER. Items already done today are always included."""
    want = quotas(minutes, today.weekday())
    latest = latest_by_item(history)
    done_today = {h.item_id for h in history if h.on == today}
    out: list[Item] = []
    for kind in KIND_ORDER:
        pool = sorted((i for i in items if i.kind == kind), key=lambda i: (i.position, i.id))
        chosen = [i for i in pool if i.id in done_today]
        room = want[kind] - len(chosen)
        if room > 0:
            rest = [i for i in pool if i.id not in done_today]
            needs = [i for i in rest if i.id in latest and (latest[i.id].score or 0.0) < NEEDS_WORK_BELOW]
            needs.sort(key=lambda i: (latest[i.id].at, i.position, i.id))
            take = needs[: room // 2]
            taken = {i.id for i in take}
            never = [i for i in rest if i.id not in latest]
            need_ids = {i.id for i in needs}
            good = [i for i in rest if i.id in latest and i.id not in need_ids]
            good.sort(key=lambda i: (latest[i.id].at, i.position, i.id))
            leftovers = [i for i in needs if i.id not in taken]
            for i in never + good + leftovers:
                if len(take) >= room:
                    break
                take.append(i)
            chosen += take
        out += chosen
    return out


def streak(days: set[date], today: date) -> int:
    """Days in a row with practice, counting back from today (or from yesterday when today has none yet)."""
    day = today if today in days else today - timedelta(days=1)
    count = 0
    while day in days:
        count += 1
        day -= timedelta(days=1)
    return count
