"""Puts a brief together: run the news pipeline, pick the best items, make audio, notify."""
import logging
import subprocess
import threading
from collections.abc import Callable
from datetime import datetime, time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import Alert, Brief, NewsItem
from app.llm.gateway import LlmGateway
from app.pipelines.news.run import recent_unbriefed, run_news_pipeline
from app.settings_store import get_brief_settings
from app.tts import piper

log = logging.getLogger(__name__)
LABELS = {"morning": "morning", "evening": "evening", "extra1": "extra", "extra2": "extra"}


def next_occurrence(time_str: str, tz_name: str, now: datetime | None = None) -> datetime:
    """The next moment (UTC) when a brief with this clock time is due."""
    tz = ZoneInfo(tz_name)
    now = (now or datetime.now(timezone.utc)).astimezone(tz)
    hh, mm = (int(x) for x in time_str.split(":"))
    due = datetime.combine(now.date(), time(hh, mm), tzinfo=tz)
    if due < now - timedelta(minutes=5):
        due += timedelta(days=1)
    return due.astimezone(timezone.utc)


class BriefService:
    def __init__(
        self,
        settings: Settings,
        session_factory: Callable[[], Session],
        gateway: LlmGateway,
        client: httpx.Client,
        notifier: Callable[[Session, Alert], None] | None = None,
        synth: Callable[[Settings, str, Path], int] = piper.synthesize_mp3,
    ):
        self.settings = settings
        self._session_factory = session_factory
        self.gateway = gateway
        self.client = client
        self.notifier = notifier
        self.synth = synth
        self._lock = threading.Lock()

    @property
    def busy(self) -> bool:
        return self._lock.locked()

    def create_row(self, kind: str, scheduled_for: datetime) -> str:
        with self._session_factory() as db:
            brief = Brief(kind=kind, scheduled_for=scheduled_for, status="preparing")
            db.add(brief)
            db.commit()
            return brief.id

    def run(self, brief_id: str) -> None:
        """Runs in a background thread. Never raises; a failure marks the brief as failed."""
        with self._lock:
            with self._session_factory() as db:
                try:
                    self._run(db, brief_id)
                except Exception as exc:  # keep the scheduler alive whatever happens
                    log.exception("brief %s crashed", brief_id)
                    db.rollback()
                    self._fail(db, brief_id, f"Unexpected error: {type(exc).__name__}")

    # ---------------------------------------------------------------- internals

    def _run(self, db: Session, brief_id: str) -> None:
        brief = db.get(Brief, brief_id)
        try:
            stats = run_news_pipeline(db, self.gateway, self.client, self.settings)
            log.info("pipeline: %s", stats)
        except Exception:
            log.exception("news pipeline failed; using items from earlier runs")
        items = recent_unbriefed(db, self.settings.brief_max_items)
        if not items:
            self._fail(db, brief_id, "No new items were found")
            return

        total = 0
        tts_ok = True
        if self.synth is piper.synthesize_mp3:
            # Fetch the reading voice the first time, so the owner does not have to remember a manual step.
            try:
                piper.ensure_voice(self.settings, self.settings.piper_voice)
            except (piper.TtsError, subprocess.TimeoutExpired, OSError) as exc:
                log.warning("reading voice unavailable (%s); the brief will be text only", exc)
                tts_ok = False
        for item in items:
            if tts_ok:
                try:
                    total += self._make_audio(item)
                except piper.TtsError as exc:
                    log.warning("audio unavailable (%s); the brief will be text only", exc)
                    tts_ok = False
            item.brief_id = brief.id
        brief.item_ids = [i.id for i in items]
        brief.audio_seconds_total = total
        brief.status = "ready"
        brief.note = "" if tts_ok else "Audio could not be made; text only."
        db.commit()

        minutes = max(1, round(total / 60)) if total else 0
        body = f"{len(items)} items" + (f" · {minutes} min" if minutes else "")
        alert = Alert(
            kind="brief_ready",
            title=f"Your {LABELS.get(brief.kind, 'daily')} brief is ready",
            body=body,
            payload={"brief_id": brief.id, "kind": brief.kind},
        )
        db.add(alert)
        db.commit()
        if self.notifier:
            try:
                self.notifier(db, alert)
            except Exception:
                log.exception("push notification failed (the tablet will still get it on next sync)")

    def _make_audio(self, item: NewsItem) -> int:
        text = piper.script_for_item(item.title, item.summary, item.prelims_facts or [], item.mains_angle or "")
        out = piper.audio_dir(self.settings) / f"{item.id}.mp3"
        secs = self.synth(self.settings, text, out)
        item.audio_path = out.name
        item.audio_seconds = secs
        return secs

    def _fail(self, db: Session, brief_id: str, reason: str) -> None:
        brief = db.get(Brief, brief_id)
        if brief is None:
            return
        brief.status = "failed"
        brief.note = reason[:300]
        db.add(Alert(kind="brief_failed", title="A brief could not be prepared", body=reason[:300],
                     payload={"brief_id": brief_id}))
        db.commit()


def slot_for(db: Session, slot_id: str):
    for slot in get_brief_settings(db).briefs:
        if slot.id == slot_id:
            return slot
    return None
