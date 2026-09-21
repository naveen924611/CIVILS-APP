from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.api.kv import set_kv
from app.db.models import Card
from app.db.models_v2 import (
    Attempt,
    DailyPlan,
    Exam,
    FocusSession,
    Job,
    Note,
    Pyq,
    Review,
    Sheet,
    Topic,
    WeeklyReport,
)
from app.db.models_v2 import Test as TestRow
from app.db.session import get_session_factory
from app.features.plan_hooks import run_postprocessors
from app.features.reports import jobs, pdf, sheets, weekly
from app.features.reports.plan import pick_sheets
from app.jobs.registry import JobFailed
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway


def world(db, with_note=True):
    paper = Topic(title="GS Paper 2", level=0, approved=True)
    db.add(paper)
    db.flush()
    subject = Topic(title="Polity", level=1, parent_id=paper.id, approved=True, status="studied")
    db.add(subject)
    db.flush()
    topic = Topic(title="Federalism", level=2, parent_id=subject.id, approved=True, status="studied", importance=7.0, strength=0.3)
    db.add(topic)
    db.flush()
    if with_note:
        db.add(Note(topic_id=topic.id, content_md="Federalism divides power between the Union and States.",
                    sections={"key_points": ["Article 246 has three lists", {"text": "Sarkaria Commission 1988"}],
                              "must_remember": ["Seventh Schedule has three lists"], "mains_angle": "Discuss cooperative federalism.",
                              "in_the_news": [{"title": "GST council meets", "url": "https://x.example/1"}]}))
    db.add(Pyq(exam="UPSC", year=2019, paper="GS2", question="Which list contains police?", options=["a", "b", "c", "d"],
               answer_index=1, topic_ids=[topic.id]))
    db.commit()
    return subject, topic


# ---------------------------------------------------------------- pdf


def test_clean_text_and_pdf_files(tmp_path):
    assert pdf.clean("It’s “good” – ok") == "It's \"good\" - ok"
    assert pdf.clean("Word తెలుగు end") == "Word [Telugu] end"
    assert pdf.clean("café 中") .endswith("?")
    p = pdf.render_sheet("Federalism", "Polity", {"key_facts": ["A fact"], "must_remember": ["Remember"], "past_paper_themes": ["UPSC 2019: q"],
                                                 "in_the_news": [{"title": "News"}], "memory_hooks": ["Hook"], "mains_angle": "Angle"},
                         tmp_path / "s.pdf")
    assert p.read_bytes().startswith(b"%PDF") and p.stat().st_size > 1000
    data = {"week_start": "2026-09-14", "week_end": "2026-09-20", "narrative": "A good week.", "hours": {"planned_minutes": 100, "done_minutes": 80},
            "topics_finished": [{"title": "x"}], "cards": {"revised": 12}, "mcq": {"attempted": 10, "accuracy": 0.7, "tests": [{"title": "T", "score": 6, "total": 10}]},
            "covered": [{"title": "Federalism"}], "weak_spots": {"topics": [{"title": "GST", "strength": 0.2}], "fading_cards": 3, "low_days": ["Monday"]},
            "next_week": {"changes": ["Focus on GST."]}, "last_month": {"active": True, "exam": "UPSC", "days_left": 12}}
    r = pdf.render_report(data, tmp_path / "r.pdf")
    assert r.read_bytes().startswith(b"%PDF")


# ---------------------------------------------------------------- sheets


def test_build_sheet_with_ai_and_without(env):
    settings, factory = env
    gw = FullFakeGateway([sheets.SheetOut(key_facts=["Article 246: three lists", "Sarkaria 1988"], memory_hooks=["U-S-C"])])
    with factory() as db:
        subject, topic = world(db)
        s = sheets.build_sheet(db, settings, gw, topic.id)
        db.commit()
        d = s.sections
        assert d["key_facts"][0].startswith("Article 246") and d["memory_hooks"] == ["U-S-C"] and d["ai"] is True
        assert d["must_remember"] == ["Seventh Schedule has three lists"] and d["subject"] == "Polity"
        assert d["past_paper_themes"][0].startswith("UPSC 2019") and d["in_the_news"][0]["title"] == "GST council meets"
        assert "## Key facts" in s.content_md and "## Mains angle" in s.content_md
        assert s.audio_seconds > 0 and s.pdf_path and (settings.data_dir and (sheets.pdf_file(settings, s)).exists())
        # rebuilding keeps ONE sheet per topic; without the AI the note's key points are used
        again = sheets.build_sheet(db, settings, FullFakeGateway([]), topic.id)
        db.commit()
        assert again.id == s.id and again.sections["ai"] is False
        assert again.sections["key_facts"][0] == "Article 246 has three lists"
        assert len(list(db.scalars(select(Sheet)))) == 1
        assert sheets.build_sheet(db, settings, gw, topic.id, use_ai=False).sections["ai"] is False


def test_sheet_needs_notes(env):
    settings, factory = env
    with factory() as db:
        subject, topic = world(db, with_note=False)
        with pytest.raises(JobFailed):
            sheets.build_sheet(db, settings, FullFakeGateway([]), topic.id)
        with pytest.raises(JobFailed):
            sheets.build_sheet(db, settings, FullFakeGateway([]), "missing")


def test_ai_skipped_when_budget_is_tight(env):
    settings, factory = env
    gw = FullFakeGateway([sheets.SheetOut(key_facts=["x fact here"])], level=2)
    with factory() as db:
        subject, topic = world(db)
        s = sheets.build_sheet(db, settings, gw, topic.id)
        assert s.sections["ai"] is False and gw.calls == []


def test_revision_sheet_job_and_stale_refresh(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    with factory() as db:
        subject, topic = world(db)
        assert sheets.stale_topics(db) == [topic.id]
        job = Job(type="revision_sheet", payload_json={"topic_id": topic.id})
        bad = Job(type="revision_sheet", payload_json={})
        db.add_all([job, bad])
        db.commit()
        jid, bid = job.id, bad.id
    run_pending(svc)
    with factory() as db:
        assert db.get(Job, jid).status == "done" and db.get(Job, bid).status == "failed"
        sheet = db.get(Sheet, db.get(Job, jid).result_json["sheet_id"])
        assert sheet.topic_id == topic.id
        assert sheets.stale_topics(db) == []
        note = db.scalars(select(Note)).first()
        note.content_md += " More text added later."
        db.commit()
        assert sheets.stale_topics(db) == [topic.id]
    jobs.nightly_sheets(svc)
    with factory() as db:
        assert sheets.stale_topics(db) == []


def test_sheet_pdf_route(client, auth_header):
    factory = get_session_factory()
    with factory() as db:
        subject, topic = world(db)
        s = sheets.build_sheet(db, client.app.state.services.settings, FullFakeGateway([]), topic.id)
        db.commit()
        sid = s.id
    r = client.get(f"/reports/sheets/{sid}/pdf", headers=auth_header)
    assert r.status_code == 200 and r.content.startswith(b"%PDF") and "attachment" in r.headers["content-disposition"]
    r = client.post("/reports/sheets/generate", headers=auth_header, json={"topic_id": topic.id if False else "missing"})
    assert r.status_code == 400
    assert client.get("/reports/sheets/nope/pdf", headers=auth_header).status_code == 404


# ---------------------------------------------------------------- last-month mode


def test_pick_sheets_rotation():
    items = [(f"t{i}", float(i % 3)) for i in range(12)]
    a = pick_sheets(items, 20000)
    assert len(a) == 9 and len(set(a)) == 9
    assert a == pick_sheets(list(reversed(items)), 20000)  # order of input does not matter
    assert pick_sheets(items[:4], 5) == [t for t, _ in sorted(items[:4], key=lambda p: (-p[1], p[0]))]
    assert pick_sheets([], 3) == []
    assert pick_sheets(items, 20000) != pick_sheets(items, 20001)


def test_last_month_block(env):
    settings, factory = env
    with factory() as db:
        subject, topic = world(db)
        sheets.build_sheet(db, settings, FullFakeGateway([]), topic.id)
        db.commit()
        assert not [b for b in run_postprocessors(db, "2026-10-01", [], {"last_month": False}) if b["kind"] == "sheet"]
        blocks = run_postprocessors(db, "2026-10-01", [], {"last_month": True})
        block = [b for b in blocks if b["kind"] == "sheet"][0]
        assert block["ref"] == "sheets" and block["minutes"] == 4 and "Federalism" in block["detail"]


# ---------------------------------------------------------------- weekly report


MONDAY = date(2026, 9, 14)


def stamp(day_offset, hour=10):
    return datetime(2026, 9, 14 + day_offset, hour, 0, tzinfo=timezone.utc)


def fill_week(db):
    subject, topic = world(db)
    blocks = [{"id": "b1", "kind": "study", "minutes": 60, "topic_id": topic.id}, {"id": "b2", "kind": "revision", "minutes": 40}]
    db.add(DailyPlan(date="2026-09-14", blocks_json=blocks, completion_json={}))
    db.add(DailyPlan(date="2026-09-15", blocks_json=blocks, completion_json={"b1": "done", "b2": "done"}))
    db.add(FocusSession(topic_id=topic.id, started_at=stamp(1), minutes=50))
    db.add(Review(card_id="c1", grade=3, reviewed_at=stamp(1)))
    db.add(Review(card_id="c2", grade=1, reviewed_at=stamp(2)))
    db.add(Review(card_id="c2", grade=3, reviewed_at=stamp(9)))  # another week
    for i, ok in enumerate([True, True, False, False]):
        db.add(Attempt(mcq_id=f"m{i}", chosen=0 if ok else 1, correct=ok, at=stamp(2)))
    db.add(TestRow(kind="weekly", title="Weekly mock", mcq_ids=["a", "b"], status="analysed", score=1.0, finished_at=stamp(3)))
    db.add(Card(front="f", back="b", fsrs_state_json={"stability": 1}, due_at=datetime.now(timezone.utc) - timedelta(days=5)))
    db.commit()
    return topic


def test_collect_counts_the_week(env):
    settings, factory = env
    with factory() as db:
        topic = fill_week(db)
        data = weekly.collect(db, MONDAY, now=datetime(2026, 9, 21, 15, 0, tzinfo=timezone.utc))
    assert data["hours"]["planned_minutes"] == 200 and data["hours"]["done_minutes"] == 100 and data["hours"]["focus_minutes"] == 50
    assert data["hours"]["by_day"][0] == {"date": "2026-09-14", "day": "mon", "planned": 100, "done": 0}
    assert data["cards"] == {"revised": 2, "distinct": 2, "fading": 1}
    assert data["mcq"]["attempted"] == 4 and data["mcq"]["accuracy"] == 0.5 and data["mcq"]["tests"][0]["title"] == "Weekly mock"
    assert data["covered"][0]["title"] == "Federalism" and data["covered"][0]["subject"] == "Polity"
    assert data["weak_spots"]["topics"][0]["topic_id"] == topic.id
    assert data["weak_spots"]["low_days"] == ["Monday"]  # nothing done on Monday; days without a plan are not "low"
    assert data["last_month"]["active"] is False


def test_next_week_rules():
    base = {"week_start": "2026-09-14", "hours": {"planned_minutes": 100, "done_minutes": 30},
            "weak_spots": {"topics": [{"topic_id": "t1", "title": "GST", "strength": 0.2}], "fading_cards": 25, "low_days": []},
            "mcq": {"attempted": 20, "accuracy": 0.5}, "last_month": {"active": True}}
    plan = weekly.next_week_plan(base)
    adj = plan["adjustments"]
    assert adj == {"week_start": "2026-09-21", "extra_revision_minutes": 30, "focus_topic_ids": ["t1"], "reduce_new_topics": True}
    assert any("Last-month" in c for c in plan["changes"])
    calm = {"week_start": "2026-09-14", "hours": {"planned_minutes": 100, "done_minutes": 95},
            "weak_spots": {"topics": [], "fading_cards": 0}, "mcq": {"attempted": 0, "accuracy": 0.0}, "last_month": {"active": False}}
    assert weekly.next_week_plan(calm)["changes"] == ["Keep the same plan. Your week went steadily."]
    assert weekly.next_week_plan(calm)["adjustments"]["extra_revision_minutes"] == 0


def test_last_month_info(env):
    settings, factory = env
    with factory() as db:
        soon = datetime.now(timezone.utc) + timedelta(days=12)
        db.add(Exam(name="UPSC CSE", stage="Prelims", date=soon))
        db.add(Exam(name="APPSC", stage="Mains", date=datetime.now(timezone.utc) + timedelta(days=200)))
        db.add(Exam(name="No date", stage="X"))
        db.commit()
        info = weekly.last_month_info(db, datetime.now(timezone.utc).date())
    assert info["active"] and info["exam"] == "UPSC CSE Prelims" and info["days_left"] in (11, 12)


def test_build_report_and_job(env):
    settings, factory = env
    text = "You did well this week and finished a good share of your plan. Your weakest topic is Federalism. Next week focuses on it."
    svc = FakeServices.build(settings, factory, FullFakeGateway([], texts=[text]))
    with factory() as db:
        fill_week(db)
        job = Job(type="weekly_report", payload_json={"week_start": "2026-09-16", "scheduled": True})
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        job = db.get(Job, jid)
        assert job.status == "done", job.error
        row = db.get(WeeklyReport, job.result_json["report_id"])
        assert row.week_start == "2026-09-14" and row.data_json["narrative"] == text
        assert row.data_json["next_week"]["adjustments"]["week_start"] == "2026-09-21" and "Your weekly report." in row.data_json["script"]
        assert weekly.pdf_file(settings, row).exists()
    pushed = svc.extras["pushed"]
    assert pushed and pushed[0][2]["type"] == "weekly_report" and pushed[0][2]["week_start"] == "2026-09-14"
    # a second run refreshes the same row; no AI text means the plain fallback, and notify.weekly_report off means no push
    svc.extras["pushed"].clear()
    with factory() as db:
        set_kv(db, "notify.weekly_report", False)
        db.add(Job(type="weekly_report", payload_json={"week_start": "2026-09-14", "scheduled": True}))
        db.commit()
    run_pending(svc)
    with factory() as db:
        rows = list(db.scalars(select(WeeklyReport)))
        assert len(rows) == 1 and rows[0].data_json["narrative"].startswith("You finished")
    assert svc.extras["pushed"] == []


def test_queue_weekly_report_once(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    svc.kick_jobs = lambda: None
    jobs.queue_weekly_report(svc)
    jobs.queue_weekly_report(svc)
    with factory() as db:
        rows = list(db.scalars(select(Job).where(Job.type == "weekly_report")))
        assert len(rows) == 1 and rows[0].payload_json["scheduled"] is True


def test_report_routes(client, auth_header):
    factory = get_session_factory()
    with factory() as db:
        fill_week(db)
    r = client.post("/reports/weekly/generate", headers=auth_header, json={"week_start": "2026-09-14"})
    assert r.status_code == 200 and r.json()["week_start"] == "2026-09-14"
    rid = r.json()["id"]
    r = client.get(f"/reports/weekly/{rid}/pdf", headers=auth_header)
    assert r.status_code == 200 and r.content.startswith(b"%PDF")
    assert client.get("/reports/weekly/nope/pdf", headers=auth_header).status_code == 404
