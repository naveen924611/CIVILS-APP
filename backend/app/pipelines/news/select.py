from collections import defaultdict
from datetime import datetime, timedelta, timezone

from app.pipelines.news.feeds import RawEntry


def select_entries(entries: list[RawEntry], max_n: int, window_hours: int, now: datetime | None = None) -> list[RawEntry]:
    """Recent items only, spread across sources (best-priority sources first)."""
    now = now or datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=window_hours)
    fresh = [e for e in entries if e.published is None or e.published >= cutoff]
    by_source: dict[str, list[RawEntry]] = defaultdict(list)
    for e in sorted(fresh, key=lambda x: (x.published or now), reverse=True):
        by_source[e.source].append(e)
    order = sorted(by_source, key=lambda s: (by_source[s][0].priority, s))
    picked: list[RawEntry] = []
    while len(picked) < max_n and any(by_source[s] for s in order):
        for s in order:
            if by_source[s] and len(picked) < max_n:
                picked.append(by_source[s].pop(0))
    return picked
