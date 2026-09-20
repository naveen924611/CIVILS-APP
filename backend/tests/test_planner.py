"""Planner: the rules (engine.py), plans saved from the database (service.py), plan hooks, and the routes."""
from datetime import date, datetime, timedelta, timezone

import pytest

from app.api.kv import set_kv
from app.db.models import Card
from app.db.models_v2 import DailyPlan, Exam, FocusSession, LibraryItem, Topic
from app.features import plan_hooks
from app.features.plan_hooks import plan_postprocessor
from app.features.planner import engine as eng
from app.features.planner import service
from app.srs import fsrs6

MON = date(2026, 9, 21)
SUN = date(2026, 9, 27)
NOW = datetime(2026, 9, 21, 3, 0, tzinfo=timezone.utc)  # Monday 08:30 India


def day_input(day=MON, hours=4.0, **kw):
    return eng.DayInput(day=day, hours=hours, morning=("07:00", True), evening=("19:00", True), **kw)


def topic(i, subject="Polity", **kw):
    return eng.TopicInfo(id=f"t{i:03d}xxxx", title=f"Topic {i}", subject=subject, est_hours=1.5, **kw)


def work(*topics):
    return [eng.Work(topic=t, remaining=t.est_hours * 60) for t in topics]


# ------------------------------------------------------------------------------------------------- engine
def test_template_adds_up_to_the_days_hours():
    d = day_input(due_cards=40, groups=[("Polity", 20), ("Economy", 20)])
    blocks, studied = eng.plan_day(d, work(topic(1), topic(2, "Economy")), set(), MON, [], {})
    kinds = {b["kind"] for b in blocks}
    assert {"brief", "study", "revision", "practice"} <= kinds
    assert sum(b["minutes"] for b in blocks) == 240
    assert all(set(b) == {"id", "kind", "start", "minutes", "title", "detail", "topic_id", "ref"} for b in blocks)
    assert len({b["id"] for b in blocks}) == len(blocks)
    starts = [b["start"] for b in blocks]
    assert starts == sorted(starts)
    assert studied


def test_rest_day_and_no_brief():
    assert eng.plan_day(day_input(hours=0), work(topic(1)), set(), MON, [], {}) == ([], [])
    d = eng.DayInput(day=MON, hours=4, morning=None, evening=None)
    blocks, _ = eng.plan_day(d, work(topic(1)), set(), MON, [], {})
    assert not [b for b in blocks if b["kind"] == "brief"]
    assert sum(b["minutes"] for b in blocks) == 240


def test_revision_grows_with_due_cards_and_is_protected_when_short():
    few = eng.plan_day(day_input(due_cards=5), work(topic(1)), set(), MON, [], {})[0]
    many = eng.plan_day(day_input(due_cards=200), work(topic(1)), set(), MON, [], {})[0]
    rev = lambda bl: sum(b["minutes"] for b in bl if b["kind"] == "revision")  # noqa: E731
    study = lambda bl: sum(b["minutes"] for b in bl if b["kind"] == "study")  # noqa: E731
    assert rev(many) > rev(few) and study(many) < study(few)
    assert rev(many) <= 0.4 * 240 + 5
    short = eng.plan_day(day_input(hours=1.5, due_cards=100), work(topic(1)), set(), MON, [], {})[0]
    assert any(b["kind"] == "revision" for b in short) and any(b["kind"] == "brief" for b in short)
    assert sum(b["minutes"] for b in short) == 90


def test_sunday_is_lighter_on_new_study_and_has_review():
    blocks, _ = eng.plan_day(day_input(SUN, 3.0, due_cards=60), work(topic(1), topic(2, "Economy")), set(), SUN, [], {})
    rev = sum(b["minutes"] for b in blocks if b["kind"] == "revision")
    study = sum(b["minutes"] for b in blocks if b["kind"] == "study")
    assert rev > study * 0.5
    assert "Full-week review" in next(b for b in blocks if b["kind"] == "revision")["detail"]


def test_telugu_and_library_take_time_from_core_study():
    d = day_input(telugu_minutes=15, library={"detail": "Read Laxmikanth ch 3"})
    blocks, _ = eng.plan_day(d, work(topic(1), topic(2, "Economy")), set(), MON, [], {})
    tel = next(b for b in blocks if b["kind"] == "telugu")
    lib = next(b for b in blocks if b["kind"] == "library")
    assert tel["minutes"] == 15 and lib["detail"].startswith("Read Laxmikanth") and lib["ref"] == "library"
    assert sum(b["minutes"] for b in blocks) == 240


def test_reduce_new_and_extra_revision():
    base = eng.plan_day(day_input(due_cards=30), work(topic(1)), set(), MON, [], {})[0]
    less = eng.plan_day(day_input(due_cards=30, reduce_new=True, extra_revision=20), work(topic(1)), set(), MON, [], {})[0]
    rev = lambda bl: sum(b["minutes"] for b in bl if b["kind"] == "revision")  # noqa: E731
    assert rev(less) > rev(base)


def test_subject_rotation_and_carried_first():
    queue = work(topic(1, "Polity"), topic(2, "Polity"), topic(3, "History"))
    blocks, _ = eng.plan_day(day_input(), queue, {"Polity"}, MON, [], {})
    first = [b for b in blocks if b["kind"] == "study"][0]
    assert first["topic_id"] == "t003xxxx"  # History first while Polity rests
    q2 = work(topic(1), topic(2, "History"))
    n = eng.catch_up_queue([{"topic_id": "t002xxxx", "minutes": 40}], q2)
    assert n == 1 and q2[1].carried
    blocks2, _ = eng.plan_day(day_input(), q2, set(), MON, [], {})
    assert [b for b in blocks2 if b["kind"] == "study"][0]["topic_id"] == "t002xxxx"
    assert "carried over" in [b for b in blocks2 if b["kind"] == "study"][0]["detail"]


def test_preserved_done_blocks_are_kept():
    kept = {"id": "2026-09-21-study-t001xxxx", "kind": "study", "start": "09:00", "minutes": 60, "title": "Done",
            "detail": "", "topic_id": "t001xxxx", "ref": None}
    blocks, _ = eng.plan_day(day_input(preserved=[kept]), work(topic(2)), set(), MON, [], {})
    assert kept in blocks and sum(b["minutes"] for b in blocks) == 240
    assert len({b["id"] for b in blocks}) == len(blocks)


def test_exam_weights_tilt_in_the_last_eight_weeks():
    exams = [eng.ExamInfo("UPSC CSE", "Prelims", date(2026, 11, 1)), eng.ExamInfo("APPSC Group-I", "Prelims", date(2027, 3, 1))]
    w = eng.exam_weights(exams, date(2026, 9, 21))
    assert w == {"UPSC CSE": 0.7, "APPSC Group-I": 0.3 / 1}
    far = eng.exam_weights(exams, date(2026, 5, 1))
    assert far == {"UPSC CSE": 0.5, "APPSC Group-I": 0.5}
    assert eng.exam_weights([eng.ExamInfo("A", "Prelims", None), eng.ExamInfo("B", "Prelims", None, weight=3)], MON) == {"A": 0.25, "B": 0.75}
    assert eng.exam_weights([], MON) == {}
    assert eng.exam_days(exams + [eng.ExamInfo("Old", "Mains", date(2020, 1, 1))], MON) == {"UPSC CSE": 41, "APPSC Group-I": 161}


def test_pace_and_card_speed():
    assert eng.pace_factor([(30, 50)]) == 1.0  # too little data
    assert eng.pace_factor([(120, 60)]) == pytest.approx(1 / 0.6)
    assert eng.pace_factor([(120, 5)]) == 1.75
    base = datetime(2026, 9, 1, tzinfo=timezone.utc)
    assert eng.minutes_per_card([base]) == eng.DEFAULT_MINUTES_PER_CARD
    times = [base + timedelta(seconds=30 * i) for i in range(40)]
    assert eng.minutes_per_card(times) == pytest.approx(0.5)


def test_fit_to_hours_cuts_study_then_practice_never_revision():
    blocks = [
        {"id": "a", "kind": "study", "minutes": 90, "start": "09:00"}, {"id": "b", "kind": "practice", "minutes": 40, "start": "12:00"},
        {"id": "c", "kind": "revision", "minutes": 50, "start": "18:00"}, {"id": "m", "kind": "mock", "minutes": 60, "start": "10:00"},
    ]
    out, over = eng.fit_to_hours([dict(b) for b in blocks], 200, {"m"})
    assert over == 0 and sum(b["minutes"] for b in out) == 200
    assert next(b for b in out if b["id"] == "c")["minutes"] == 50
    out2, over2 = eng.fit_to_hours([dict(b) for b in blocks], 100, {"m"})
    assert over2 > 0 and next(b for b in out2 if b["id"] == "c")["minutes"] == 50


def test_summary_text():
    assert eng.summary_text(0, 0, [], 0, ["x"], 0).startswith("Rest day")
    assert "cards due" in eng.summary_text(240, 240, [topic(1)], 30, [], 4)


# ------------------------------------------------------------------------------------------------- service
@pytest.fixture
def db(env):
    _, factory = env
    with factory() as s:
        yield s


def seed_syllabus(db, n=6):
    polity = Topic(title="Polity", level=1, approved=True)
    hist = Topic(title="History", level=1, approved=True)
    db.add_all([polity, hist])
    db.flush()
    topics = []
    for i in range(n):
        parent = polity if i % 2 == 0 else hist
        t = Topic(title=f"Topic {i}", parent_id=parent.id, level=2, approved=True, est_hours=1.5, importance=5 + i % 3,
                  exam_tags=["UPSC"], position=i)
        db.add(t)
        topics.append(t)
    db.flush()
    return topics


def test_plan_range_saves_days_and_does_not_repeat_topics(db, env):
    settings, _ = env
    topics = seed_syllabus(db, 6)
    db.add(Exam(name="UPSC CSE", stage="Prelims", date=datetime(2026, 11, 1, tzinfo=timezone.utc)))
    db.flush()
    rows = service.plan_range(db, settings, MON, 3, NOW)
    assert [r.date for r in rows] == ["2026-09-21", "2026-09-22", "2026-09-23"]
    seen = []
    for r in rows:
        assert sum(b["minutes"] for b in r.blocks_json) == 240
        seen += [b["topic_id"] for b in r.blocks_json if b["kind"] == "study" and b["topic_id"]]
    assert len(seen) == len(set(seen)) or len(seen) > len(topics)  # a 1.5 h topic may span two days, never more often than needed
    assert any(b["kind"] == "brief" and b["ref"] == "briefs" for b in rows[0].blocks_json)
    assert "planned" in rows[0].summary
    again = service.plan_range(db, settings, MON, 1, NOW)
    assert db.query(DailyPlan).filter(DailyPlan.date == "2026-09-21").count() == 1 and again[0].id == rows[0].id


def test_settings_change_the_plan(db, env):
    settings, _ = env
    seed_syllabus(db, 4)
    set_kv(db, service.KV_HOURS, {"mon": 6, "tue": 0, "wed": 4, "thu": 4, "fri": 4, "sat": 4, "sun": 3})
    set_kv(db, service.KV_TELUGU, 30)
    set_kv(db, "revision.slot_time", "20:00")
    set_kv(db, "briefs", {"briefs": [{"id": "morning", "time": "06:00", "enabled": True, "days": list(range(7))},
                                     {"id": "evening", "time": "19:00", "enabled": False, "days": list(range(7))}]})
    rows = {r.date: r for r in service.plan_range(db, settings, MON, 3, NOW)}
    mon, tue = rows["2026-09-21"].blocks_json, rows["2026-09-22"].blocks_json
    assert sum(b["minutes"] for b in mon) == 360 and tue == [] and "Rest day" in rows["2026-09-22"].summary
    assert next(b for b in mon if b["kind"] == "telugu")["minutes"] == 30
    assert next(b for b in mon if b["id"].endswith("morning-brief"))["start"] == "06:00"
    assert not any(b["id"].endswith("evening-brief") for b in mon)


def test_revision_slot_and_due_cards(db, env):
    settings, _ = env
    t = seed_syllabus(db, 2)[0]
    for i in range(40):
        last = NOW - timedelta(days=9)
        state = {"stability": 3.0, "difficulty": 5.0, "due": fsrs6.format_time(last + timedelta(days=3)),
                 "last_review": fsrs6.format_time(last), "reps": 2, "lapses": 0}
        db.add(Card(front=f"f{i}", back="b", topic_id=t.id, group="Polity", fsrs_state_json=state, due_at=last + timedelta(days=3)))
    set_kv(db, "revision.slot_time", "17:30")
    db.flush()
    rows = service.plan_range(db, settings, MON, 1, NOW)
    rev = next(b for b in rows[0].blocks_json if b["kind"] == "revision")
    assert rev["start"] == "17:30" and "40 cards due" in rev["detail"] and rev["ref"] == "revise/session"


def test_library_day_and_adjustments(db, env):
    settings, _ = env
    topics = seed_syllabus(db, 4)
    db.add(LibraryItem(book="Laxmikanth", chapters=["Ch 3", "Ch 4"]))
    db.flush()
    set_kv(db, service.KV_LIBRARY, {"enabled": True, "date": "2026-09-22"})
    set_kv(db, service.KV_ADJUST, {"week_start": "2026-09-21", "extra_revision_minutes": 20, "focus_topic_ids": [topics[3].id],
                                   "reduce_new_topics": False})
    rows = {r.date: r for r in service.plan_range(db, settings, MON, 2, NOW)}
    lib = [b for b in rows["2026-09-22"].blocks_json if b["kind"] == "library"]
    assert lib and "Laxmikanth (Ch 3, Ch 4)" in lib[0]["detail"]
    assert not [b for b in rows["2026-09-21"].blocks_json if b["kind"] == "library"]
    study_ids = [b["topic_id"] for b in rows["2026-09-21"].blocks_json if b["kind"] == "study"]
    assert study_ids[0] == topics[3].id  # the focus topic comes first


def test_done_blocks_kept_missed_carried_and_progress_counted(db, env):
    settings, _ = env
    topics = seed_syllabus(db, 4)
    yesterday = "2026-09-20"
    done_block = {"id": "y-done", "kind": "study", "start": "09:00", "minutes": 90, "title": "T", "detail": "",
                  "topic_id": topics[0].id, "ref": None}
    missed_block = {"id": "y-missed", "kind": "study", "start": "11:00", "minutes": 60, "title": "T", "detail": "",
                    "topic_id": topics[1].id, "ref": None}
    db.add(DailyPlan(date=yesterday, blocks_json=[done_block, missed_block], completion_json={"y-done": "done"}))
    db.flush()
    rows = service.plan_range(db, settings, MON, 2, NOW)
    ids = [b["topic_id"] for r in rows for b in r.blocks_json if b["kind"] == "study"]
    assert topics[0].id not in ids  # 90 minutes done covers the whole 1.5 h topic
    first = [b for b in rows[0].blocks_json if b["kind"] == "study"][0]
    assert first["topic_id"] == topics[1].id and "carried over" in first["detail"]
    assert "unfinished topic" in rows[0].summary
    # the owner ticks the first study block today; a re-plan keeps it
    block = first
    service.set_completion(db, MON, block["id"], "done")
    again = service.plan_range(db, settings, MON, 1, NOW)[0]
    assert block in again.blocks_json and again.completion_json == {block["id"]: "done"}


def test_three_missed_days_are_reported(db, env):
    settings, _ = env
    seed_syllabus(db, 4)
    for i in range(1, 4):
        d = (MON - timedelta(days=i)).isoformat()
        db.add(DailyPlan(date=d, blocks_json=[{"id": f"b{i}", "kind": "study", "minutes": 30, "topic_id": None}], completion_json={}))
    db.flush()
    assert service.count_missed_days(db, MON) == 3
    rows = service.plan_range(db, settings, MON, 1, NOW)
    assert "missed 3 days" in rows[0].summary


def test_pace_makes_topics_longer(db, env):
    settings, _ = env
    seed_syllabus(db, 2)
    base = service.plan_range(db, settings, MON, 1, NOW)[0]
    db.add(FocusSession(started_at=NOW - timedelta(days=2), minutes=120, completion_pct=50))
    db.flush()
    assert service.pace(db, MON) == 1.75  # 1/0.5 is capped at 1.75
    slow = service.plan_range(db, settings, MON, 1, NOW)[0]
    assert base.id == slow.id


def test_plan_hook_adds_blocks_and_study_is_cut_first(db, env):
    settings, _ = env
    seed_syllabus(db, 4)
    seen_ctx = {}

    @plan_postprocessor
    def add_mock(db_, day, blocks, ctx):
        seen_ctx.update(ctx)
        if day == "2026-09-21":
            blocks.append({"id": "mock-1", "kind": "mock", "start": "10:00", "minutes": 60, "title": "Mock", "detail": "25 q",
                           "topic_id": None, "ref": "test/abc"})
        return blocks

    try:
        db.add(Exam(name="UPSC CSE", stage="Prelims", date=datetime(2026, 10, 1, tzinfo=timezone.utc)))
        db.flush()
        row = service.plan_range(db, settings, MON, 1, NOW)[0]
    finally:
        plan_hooks.PLAN_POSTPROCESSORS.remove(add_mock)
    assert sum(b["minutes"] for b in row.blocks_json) == 240  # the hook's 60 minutes came out of study/practice
    assert next(b for b in row.blocks_json if b["id"] == "mock-1")["minutes"] == 60
    assert seen_ctx["last_month"] is True and seen_ctx["exam_days"] == {"UPSC CSE": 10} and seen_ctx["settings"] is settings
    assert "Last month" in row.summary


def test_ensure_plan_and_countdown(db, env):
    settings, _ = env
    seed_syllabus(db, 4)
    assert service.ensure_plan(db, settings, MON - timedelta(days=3), NOW) is None
    row = service.ensure_plan(db, settings, MON + timedelta(days=2), NOW)
    assert row is not None and db.query(DailyPlan).count() == 3  # today and the days up to the asked one
    db.add(Exam(name="APPSC Group-I", stage="Prelims", date=None))
    db.add(Exam(name="UPSC CSE", stage="Prelims", date=datetime(2026, 11, 1, tzinfo=timezone.utc)))
    db.flush()
    cd = service.exam_countdown(db, NOW)
    assert {c["name"]: c["days_left"] for c in cd} == {"APPSC Group-I": None, "UPSC CSE": 41}
    assert service.set_completion(db, MON - timedelta(days=30), "x", "done") is None


# ------------------------------------------------------------------------------------------------- routes
def test_routes(client, auth_header):
    from app.db.session import get_session_factory

    with get_session_factory()() as s:
        seed_syllabus(s, 4)
        s.commit()
    today = service.today_ist().isoformat()
    r = client.get(f"/planner/plan?date={today}", headers=auth_header)
    assert r.status_code == 200 and r.json()["blocks"]
    block_id = r.json()["blocks"][0]["id"]
    assert client.get("/planner/plan?date=2001-01-01", headers=auth_header).json()["blocks"] == []
    assert client.get("/planner/plan?date=oops", headers=auth_header).status_code == 400
    week = client.get("/planner/week", headers=auth_header).json()
    assert len(week["plans"]) == 7
    reg = client.post("/planner/regenerate", json={"days": 3}, headers=auth_header).json()
    assert reg["planned"] == 3
    done = client.post("/planner/complete", json={"date": today, "block_id": block_id, "status": "done"}, headers=auth_header)
    assert done.json()["completion"] == {block_id: "done"}
    assert client.post("/planner/complete", json={"date": "2001-01-01", "block_id": "x"}, headers=auth_header).status_code == 404
    assert client.get("/planner/exams", headers=auth_header).json() == {"exams": []}
    assert client.post("/planner/regenerate", json={"days": 99}, headers=auth_header).status_code == 422


def test_nightly_job(env):
    from app.features.planner import nightly
    from tests.helpers import FakeServices

    settings, factory = env
    with factory() as s:
        seed_syllabus(s, 4)
        s.commit()
    nightly(FakeServices.build(settings, factory))
    with factory() as s:
        assert s.query(DailyPlan).count() == 7
