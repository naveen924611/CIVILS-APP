"""Keeps AI usage inside the free limits and decides what to drop first (spec section 9)."""
from collections.abc import Callable
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import LlmUsage

# The two daily briefs and queued tutor questions are always attempted.
PROTECTED_FEATURES = {"brief_item", "tutor_answer", "check"}
# These wait until tomorrow when the day's budget is nearly used.
DEFERRABLE_FEATURES = {"note_merge", "revision_sheet", "weekly_report", "mock_test"}


class BudgetGuard:
    def __init__(self, settings: Settings, session_factory: Callable[[], Session]):
        self.settings = settings
        self._session_factory = session_factory

    def _day_start_utc(self) -> datetime:
        tz = ZoneInfo(self.settings.timezone)
        local_midnight = datetime.combine(datetime.now(tz).date(), time.min, tzinfo=tz)
        return local_midnight.astimezone(timezone.utc)

    def limits(self) -> dict[str, int]:
        out = {}
        if self.settings.gemini_api_key:
            out["gemini"] = self.settings.gemini_daily_requests
        if self.settings.groq_api_key:
            out["groq"] = self.settings.groq_daily_requests
        return out

    def requests_today(self, provider: str | None = None) -> int:
        start = self._day_start_utc()
        with self._session_factory() as db:
            q = select(func.count(LlmUsage.id)).where(LlmUsage.at >= start)
            if provider:
                q = q.where(LlmUsage.provider == provider)
            return db.scalar(q) or 0

    def provider_full(self, provider: str) -> bool:
        limit = self.limits().get(provider, 0)
        return limit > 0 and self.requests_today(provider) >= limit

    def fraction(self) -> float:
        limits = self.limits()
        total = sum(limits.values())
        if total == 0:
            return 1.0
        used = sum(min(self.requests_today(p), lim) for p, lim in limits.items())
        return used / total

    def level(self) -> int:
        """0 normal | 1 skip MCQs | 2 shorter summaries | 3 defer non-essential work."""
        f = self.fraction()
        s = self.settings
        if f >= s.degrade_defer_at:
            return 3
        if f >= s.degrade_shorten_at:
            return 2
        if f >= s.degrade_skip_mcq_at:
            return 1
        return 0

    def allow(self, feature: str) -> bool:
        if feature in PROTECTED_FEATURES:
            return True
        if feature in DEFERRABLE_FEATURES:
            return self.level() < 3
        return True

    def resets_at(self) -> datetime:
        return self._day_start_utc() + timedelta(days=1)
