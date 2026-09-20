"""Runs the brief jobs at the times saved in Settings (default 7:00 and 19:00 IST, prepared 30 min earlier)."""
import logging
from collections.abc import Callable

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from sqlalchemy.orm import Session

from app.briefs.builder import BriefService, next_occurrence
from app.config import Settings
from app.settings_store import get_brief_settings

log = logging.getLogger(__name__)
DAY_NAMES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def prepare_cron(time_str: str, days: list[int], lead_minutes: int) -> tuple[int, int, list[int]]:
    """Clock time and weekdays for the *preparation* job, `lead_minutes` before the brief.
    A 00:10 brief prepared 30 minutes early is prepared at 23:40 the day before."""
    hh, mm = (int(x) for x in time_str.split(":"))
    minutes = hh * 60 + mm - lead_minutes
    shift = 0
    if minutes < 0:
        minutes += 24 * 60
        shift = -1
    return minutes // 60, minutes % 60, sorted({(d + shift) % 7 for d in days})


class BriefScheduler:
    def __init__(
        self,
        settings: Settings,
        session_factory: Callable[[], Session],
        service: BriefService,
        scheduler: BackgroundScheduler | None = None,
    ):
        self.settings = settings
        self._session_factory = session_factory
        self.service = service
        self._owns = scheduler is None
        self.scheduler = scheduler or BackgroundScheduler(timezone=settings.timezone)

    def start(self) -> None:
        if not self.scheduler.running:
            self.scheduler.start()
        self.reschedule()

    def shutdown(self) -> None:
        if self._owns and self.scheduler.running:
            self.scheduler.shutdown(wait=False)

    def reschedule(self) -> None:
        """Rebuild all jobs from the saved settings (called at start and whenever settings change)."""
        for job in self.scheduler.get_jobs():
            if job.id.startswith("brief:"):
                job.remove()
        with self._session_factory() as db:
            slots = get_brief_settings(db).briefs
        for slot in slots:
            if not slot.enabled or not slot.days:
                continue
            hour, minute, days = prepare_cron(slot.time, slot.days, self.settings.brief_lead_minutes)
            self.scheduler.add_job(
                self._run_slot, CronTrigger(day_of_week=",".join(DAY_NAMES[d] for d in days), hour=hour,
                                            minute=minute, timezone=self.settings.timezone),
                args=[slot.id, slot.time], id=f"brief:{slot.id}", replace_existing=True,
                misfire_grace_time=3600, coalesce=True, max_instances=1,
            )
            log.info("scheduled %s brief for %s (prepared at %02d:%02d)", slot.id, slot.time, hour, minute)

    def job_ids(self) -> list[str]:
        return sorted(j.id for j in self.scheduler.get_jobs())

    def _run_slot(self, slot_id: str, time_str: str) -> None:
        due = next_occurrence(time_str, self.settings.timezone)
        brief_id = self.service.create_row(slot_id, due)
        self.service.run(brief_id)
