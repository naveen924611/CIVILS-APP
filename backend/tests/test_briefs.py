from datetime import datetime, timezone
from pathlib import Path

import pytest
from pydantic import ValidationError
from sqlalchemy import select

from app.briefs.builder import BriefService, next_occurrence
from app.briefs.scheduler import BriefScheduler, prepare_cron
from app.db.models import Alert, Brief, NewsItem
from app.settings_store import DEFAULT_BRIEFS, BriefSettings, BriefSlot, get_brief_settings, set_brief_settings
from app.tts.piper import TtsError
from tests import test_pipeline
from tests.helpers import FakeGateway, out


def test_prepare_cron_lead_time_and_midnight_wrap():
    assert prepare_cron("07:00", [0, 1, 2, 3, 4, 5, 6], 30) == (6, 30, [0, 1, 2, 3, 4, 5, 6])
    assert prepare_cron("19:00", [0, 2], 30) == (18, 30, [0, 2])
    assert prepare_cron("00:10", [0, 6], 30) == (23, 40, [5, 6])


def test_next_occurrence_is_tomorrow_when_time_has_passed():
    now = datetime(2026, 9, 20, 3, 0, tzinfo=timezone.utc)  # 08:30 in India
    assert next_occurrence("07:00", "Asia/Kolkata", now) == datetime(2026, 9, 21, 1, 30, tzinfo=timezone.utc)
    assert next_occurrence("19:00", "Asia/Kolkata", now) == datetime(2026, 9, 20, 13, 30, tzinfo=timezone.utc)


def test_brief_settings_validation_and_storage(env):
    _, factory = env
    with factory() as db:
        assert get_brief_settings(db) == DEFAULT_BRIEFS
        new = BriefSettings(briefs=[BriefSlot(id="morning", time="06:30"), BriefSlot(id="evening", time="20:00", enabled=False),
                                    BriefSlot(id="extra1", time="13:00", days=[4, 0, 0])])
        assert new.briefs[0].time == "06:30" and new.briefs[2].days == [0, 4]
        set_brief_settings(db, new)
        set_brief_settings(db, new)  # update path
        assert get_brief_settings(db).briefs[1].enabled is False
    with pytest.raises(ValidationError):
        BriefSettings(briefs=[BriefSlot(id="morning", time="07:00")])
    with pytest.raises(ValidationError):
        BriefSlot(id="morning", time="25:99")
    with pytest.raises(ValidationError):
        BriefSlot(id="morning", time="07:00", days=[7])
    with pytest.raises(ValidationError):
        BriefSettings(briefs=[BriefSlot(id="morning", time="07:00")] * 2 + [BriefSlot(id="evening", time="19:00")])


def test_bad_stored_settings_fall_back_to_default(env):
    from app.db.models import Setting

    _, factory = env
    with factory() as db:
        db.add(Setting(key="briefs", value_json={"briefs": []}))
        db.commit()
        assert get_brief_settings(db) == DEFAULT_BRIEFS


def make_service(env, tmp_path, monkeypatch, answers, synth=None, notifier=None):
    settings, factory = env
    test_pipeline.setup_feeds(settings, tmp_path)
    from app.pipelines.news import run as run_mod
    from tests.helpers import feed_client, rss

    monkeypatch.setattr(run_mod, "fetch_article_text", lambda c, r, u: test_pipeline.LONG)
    client = feed_client(rss([("RBI keeps repo rate unchanged", "https://example.com/a"),
                              ("Polavaram project gets new funds", "https://example.com/b")]))

    def fake_synth(settings, text, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"ID3fake")
        return 42

    return BriefService(settings, factory, FakeGateway(answers), client, notifier=notifier, synth=synth or fake_synth), factory


def test_brief_is_built_with_audio_alert_and_push(env, tmp_path, monkeypatch):
    sent = []
    svc, factory = make_service(env, tmp_path, monkeypatch, [out("Repo rate held", 8, 5), out("Polavaram funds", 6, 8)],
                                notifier=lambda db, alert: sent.append(alert.kind))
    brief_id = svc.create_row("morning", datetime.now(timezone.utc))
    svc.run(brief_id)
    with factory() as db:
        b = db.get(Brief, brief_id)
        assert b.status == "ready" and len(b.item_ids) == 2 and b.audio_seconds_total == 84
        items = db.scalars(select(NewsItem)).all()
        assert all(i.brief_id == brief_id and i.audio_path == f"{i.id}.mp3" for i in items)
        alert = db.scalars(select(Alert)).one()
        assert alert.kind == "brief_ready" and "morning" in alert.title and "2 items · 1 min" in alert.body
    assert sent == ["brief_ready"] and not svc.busy


def test_brief_without_audio_still_works(env, tmp_path, monkeypatch):
    def broken(settings, text, path):
        raise TtsError("no voice")

    svc, factory = make_service(env, tmp_path, monkeypatch, [out(), out("Second", 7, 7)], synth=broken)
    brief_id = svc.create_row("evening", datetime.now(timezone.utc))
    svc.run(brief_id)
    with factory() as db:
        b = db.get(Brief, brief_id)
        assert b.status == "ready" and b.audio_seconds_total == 0 and "text only" in b.note


def test_brief_fails_cleanly_when_nothing_new(env, tmp_path, monkeypatch):
    svc, factory = make_service(env, tmp_path, monkeypatch, [None, None])
    brief_id = svc.create_row("morning", datetime.now(timezone.utc))
    svc.run(brief_id)
    with factory() as db:
        assert db.get(Brief, brief_id).status == "failed"
        assert db.scalars(select(Alert)).one().kind == "brief_failed"


def test_notifier_error_does_not_break_the_brief(env, tmp_path, monkeypatch):
    def bad(db, alert):
        raise RuntimeError("fcm down")

    svc, factory = make_service(env, tmp_path, monkeypatch, [out(), out("Second story", 7, 7)], notifier=bad)
    brief_id = svc.create_row("morning", datetime.now(timezone.utc))
    svc.run(brief_id)
    with factory() as db:
        assert db.get(Brief, brief_id).status == "ready"


def test_crash_marks_brief_failed(env, tmp_path, monkeypatch):
    svc, factory = make_service(env, tmp_path, monkeypatch, [])
    monkeypatch.setattr(svc, "_run", lambda db, bid: (_ for _ in ()).throw(ValueError("x")))
    brief_id = svc.create_row("morning", datetime.now(timezone.utc))
    svc.run(brief_id)
    with factory() as db:
        assert db.get(Brief, brief_id).status == "failed"
    svc._fail(factory(), "missing-id", "x")  # unknown id is ignored


def test_scheduler_builds_and_rebuilds_jobs(env):
    settings, factory = env
    sched = BriefScheduler(settings, factory, svc := object())
    sched.scheduler.start(paused=True)
    try:
        sched.reschedule()
        assert sched.job_ids() == ["brief:evening", "brief:morning"]
        with factory() as db:
            set_brief_settings(db, BriefSettings(briefs=[
                BriefSlot(id="morning", time="07:00", enabled=False), BriefSlot(id="evening", time="19:00"),
                BriefSlot(id="extra1", time="00:10", days=[0])]))
        sched.reschedule()
        assert sched.job_ids() == ["brief:evening", "brief:extra1"]
    finally:
        sched.shutdown()
    assert svc is not None


def test_scheduled_slot_creates_and_runs_a_brief(env):
    settings, factory = env
    calls = []

    class Svc:
        def create_row(self, kind, due):
            calls.append(("create", kind))
            return "id1"

        def run(self, brief_id):
            calls.append(("run", brief_id))

    BriefScheduler(settings, factory, Svc())._run_slot("morning", "07:00")
    assert calls == [("create", "morning"), ("run", "id1")]
