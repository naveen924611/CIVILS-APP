"""SI (Civil) goal on the server: exam tag, whole-word exam matching, seeder `supersedes`, the aptitude test kind and the plan hooks."""
import json
import math
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest
from sqlalchemy import select

from app.api.kv import set_kv
from app.db.models import Card
from app.db.models_v2 import Attempt, DailyPlan, Exam, Job, Mcq, Mistake, SyllabusImport, Topic
from app.db.models_v2 import Test as TestRow
from app.features import plan_hooks
from app.features.examnames import has_word, is_si_exam
from app.features.planner import engine as eng
from app.features.planner import service
from app.features.si import plan as si_plan
from app.features.syllabus import service as syl
from app.features.syllabus.trees import clean_tree, count_nodes, exam_tags_for, filter_tree
from app.features.tests import aptitude, generate, mistakes, scoring
from app.features.tests.common import to_ist_date, today_ist
from app.jobs.registry import JobFailed
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway

REPO = Path(__file__).resolve().parents[2]
SI_NAME = "SLPRB SI (Civil)"


@pytest.fixture
def db(env):
    _, factory = env
    with factory() as s:
        yield s


def add_exam(db, name=SI_NAME, when=None, deleted=False):
    exam = Exam(name=name, stage="Prelims", date=when, deleted=deleted)
    db.add(exam)
    db.flush()
    return exam


def upcoming(n=7):
    start = today_ist()
    return [start + timedelta(days=i) for i in range(n)]


# ---------------------------------------------------------------- exam tag SI and whole-word matching


def test_exam_tags_for_si_names():
    assert exam_tags_for("SLPRB SI (Civil)") == ["SI"]
    assert exam_tags_for("AP SLPRB Sub-Inspector") == ["SI"]
    assert exam_tags_for("SI") == ["SI"] and exam_tags_for("si civil") == ["SI"] and exam_tags_for("Police SI-Civil 2026") == ["SI"]
    assert exam_tags_for("Mission Impossible") == [] and exam_tags_for("Basic Physics") == [] and exam_tags_for("Silicon") == []
    assert exam_tags_for("UPSC CSE") == ["UPSC"] and exam_tags_for("APPSC Group-I") == ["APPSC"]
    assert exam_tags_for("UPSC and APPSC") == ["UPSC", "APPSC"] and exam_tags_for("Both exams") == ["APPSC", "UPSC"]
    assert exam_tags_for("") == [] and exam_tags_for(None) == []


def test_has_word_and_is_si_exam():
    assert has_word("SLPRB SI (Civil)", "si") and has_word("UPSC-CSE", "upsc") and has_word("UPSC CSE", "UPSC")
    assert not has_word("Mission", "si") and not has_word("Basic", "si") and not has_word("APPSC Group-I", "psc")
    assert has_word("APPSC Group-I", "group-i") and not has_word("x", "") and not has_word("", "si")
    assert is_si_exam("SLPRB SI (Civil)") and is_si_exam("slprb") and is_si_exam("SI") and not is_si_exam("Mission") and not is_si_exam("UPSC CSE")


def test_clean_tree_keeps_si_and_filter_tree_works_for_it():
    raw = [
        {"title": "Arithmetic", "exam_tags": ["si"], "children": [{"title": "Percentage"}, {"title": "Average", "exam_tags": ["SI", "APPSC", "X"]}]},
        {"title": "Polity", "exam_tags": ["UPSC"], "children": [{"title": "Preamble"}]},
    ]
    tree = clean_tree(raw)
    assert tree[0]["exam_tags"] == ["SI"] and tree[0]["children"][0]["exam_tags"] == ["SI"]  # inherited
    assert tree[0]["children"][1]["exam_tags"] == ["SI", "APPSC"]  # unknown tags dropped, order kept
    only_si = filter_tree(tree, "SI")
    assert [n["title"] for n in only_si] == ["Arithmetic"] and count_nodes(only_si) == 3
    assert [n["title"] for n in filter_tree(tree, "si")] == ["Arithmetic"]
    assert [n["title"] for n in filter_tree(tree, "APPSC")] == ["Arithmetic"] and filter_tree(tree, "UPSC")[0]["title"] == "Polity"


def test_syllabus_import_prompt_knows_si():
    text = (REPO / "backend" / "app" / "llm" / "prompts" / "syllabus_import.system.md").read_text(encoding="utf-8")
    line = next(x for x in text.splitlines() if "exam_tags" in x and "is a list" in x)
    assert '"SI"' in line and "SLPRB" in line and '"UPSC"' in line and '"APPSC"' in line


def test_priority_for_uses_whole_words():
    prio = {"UPSC": 1, "APPSC": 1, "SI": 2}
    assert service._priority_for("SLPRB SI (Civil)", prio) == 2.0
    assert service._priority_for("UPSC CSE", prio) == 1.0 and service._priority_for("APPSC Group-I", prio) == 1.0
    assert service._priority_for("Mission Impossible", {"SI": 3}) == 1.0  # "si" inside a word is not the tag
    assert service._priority_for("UPSC CSE", {"appsc": 0.5, "upsc": 0.25}) == 0.25 and service._priority_for("APPSC Group-I", {"upsc": 0.25, "appsc": 0.5}) == 0.5
    assert service._priority_for("APPSC Group-I", {"psc": 9}) == 1.0  # a piece of a word does not count
    assert service._priority_for("SLPRB SI (Civil)", {"SI": "oops"}) == 1.0 and service._priority_for("x", {}) == 1.0


def test_load_exams_weights_from_priority_kv(db):
    set_kv(db, "exam.priority", {"UPSC": 1, "APPSC": 1, "SI": 2})
    for name in ("UPSC CSE", "APPSC Group-I", SI_NAME):
        add_exam(db, name)
    weights = {e.name: e.weight for e in service.load_exams(db)}
    assert weights == {"UPSC CSE": 1.0, "APPSC Group-I": 1.0, SI_NAME: 2.0}


def _exams(today):
    return [eng.ExamInfo("UPSC CSE", "Prelims", today + timedelta(days=300)), eng.ExamInfo("APPSC Group-I", "Prelims", today + timedelta(days=200)),
            eng.ExamInfo(SI_NAME, "Prelims", today + timedelta(days=20)), eng.ExamInfo("Mission Impossible", "Prelims", None)]


def test_matching_exams_by_whole_word():
    today = date(2026, 9, 21)
    exams = _exams(today)
    names = lambda topic: [e.name for e in eng.matching_exams(topic, exams)]  # noqa: E731
    assert names(eng.TopicInfo("a", "Percentage", exam_tags=["SI"])) == [SI_NAME]  # not "Mission Impossible"
    assert names(eng.TopicInfo("b", "Polity", exam_tags=["UPSC"])) == ["UPSC CSE"]
    assert names(eng.TopicInfo("c", "Polity", exam_tags=["APPSC"])) == ["APPSC Group-I"]
    assert names(eng.TopicInfo("d", "Polity", exam_tags=["UPSC", "SI"])) == ["UPSC CSE", SI_NAME]
    assert names(eng.TopicInfo("e", "Polity")) == [e.name for e in exams]  # untagged: every exam
    assert names(eng.TopicInfo("f", "Polity", exam_tags=["XYZ"])) == [e.name for e in exams]  # nothing matches: every exam (as before)


def test_si_topic_is_scored_against_the_slprb_exam_only():
    today = date(2026, 9, 21)
    exams = _exams(today)[:3]
    weights = eng.exam_weights(exams, today)
    si_topic = eng.TopicInfo("a", "Percentage", exam_tags=["SI"], importance=5)
    upsc_topic = eng.TopicInfo("b", "Polity", exam_tags=["UPSC"], importance=5)
    # SI exam is 20 days away, UPSC 300: the SI topic is more urgent
    assert eng.topic_score(si_topic, exams, weights, today) > eng.topic_score(upsc_topic, exams, weights, today)
    without = [e for e in exams if e.name != SI_NAME]
    untagged = eng.TopicInfo("c", "Other", importance=5)
    w2 = eng.exam_weights(without, today)
    assert eng.topic_score(si_topic, without, w2, today) == eng.topic_score(untagged, without, w2, today)  # no SI exam: scored like an untagged topic
    # the same topic is not scored against the far UPSC exam
    only_upsc_far = [exams[0], exams[2]]
    w3 = {exams[0].name: 0.5, exams[2].name: 0.5}
    expected = 0.5 * 0.5 + 0.4 * (0.5 * 2 / (1 + 20 / 90.0))
    assert eng.topic_score(si_topic, only_upsc_far, w3, today) == pytest.approx(expected)


def test_revision_exam_proximity_uses_whole_words(db):
    from app.features.revision import service as rev

    today = today_ist()
    soon = datetime.now(timezone.utc) + timedelta(days=10)
    topic = Topic(title="Percentage", level=2, approved=True, exam_tags=["SI"])
    mission = add_exam(db, "Mission Impossible", soon)
    assert rev.exam_proximity(topic, [mission], today) == 0.0  # "si" inside "Mission" is not the SI exam
    slprb = add_exam(db, SI_NAME, soon)
    assert rev.exam_proximity(topic, [mission, slprb], today) > 0.9


def test_priority_weights_the_si_exam(db):
    set_kv(db, "exam.priority", {"UPSC": 1, "APPSC": 1, "SI": 2})
    today = today_ist()
    add_exam(db, "UPSC CSE", datetime.now(timezone.utc) + timedelta(days=90))
    add_exam(db, SI_NAME, datetime.now(timezone.utc) + timedelta(days=90))
    exams = service.load_exams(db)
    weights = eng.exam_weights(exams, today)
    assert weights[SI_NAME] == pytest.approx(2 * weights["UPSC CSE"])  # priority 2 against 1, same date


# ---------------------------------------------------------------- seeder: starters and `supersedes`


def _write(folder, key, tree=None, supersedes=None, exam="UPSC CSE"):
    doc = {"key": key, "exam": exam, "title": key, "verified": True, "tree": tree or [{"title": f"Paper {key}", "children": [{"title": "Topic"}]}]}
    if supersedes:
        doc["supersedes"] = supersedes
    (folder / f"{key}.json").write_text(json.dumps(doc), encoding="utf-8")


@pytest.fixture
def folder(tmp_path, monkeypatch):
    f = tmp_path / "syl"
    f.mkdir()
    monkeypatch.setenv("SYLLABUS_DIR", str(f))
    return f


def test_supersedes_retires_an_old_pending_import(env, folder):
    settings, factory = env
    _write(folder, "old-key")
    with factory() as db:
        assert syl.seed_starters(db, settings) == 1
        old = db.get(SyllabusImport, syl.seed_id("old-key"))
        assert old.status == "pending" and not old.deleted
        _write(folder, "new-key", supersedes="old-key")
        assert syl.seed_starters(db, settings) == 1
        db.expire_all()
        assert db.get(SyllabusImport, syl.seed_id("old-key")).deleted is True
        new = db.get(SyllabusImport, syl.seed_id("new-key"))
        assert new.status == "pending" and not new.deleted
        stamp = db.get(SyllabusImport, syl.seed_id("old-key")).updated_at
        assert syl.seed_starters(db, settings) == 0  # a second run changes nothing
        db.expire_all()
        assert db.get(SyllabusImport, syl.seed_id("old-key")).deleted is True and db.get(SyllabusImport, syl.seed_id("old-key")).updated_at == stamp


def test_supersedes_never_touches_an_approved_import(env, folder):
    settings, factory = env
    _write(folder, "old-key")
    with factory() as db:
        syl.seed_starters(db, settings)
        old = db.get(SyllabusImport, syl.seed_id("old-key"))
        syl.approve(db, old)
        _write(folder, "new-key", supersedes="old-key")
        assert syl.seed_starters(db, settings) == 1
        db.expire_all()
        old = db.get(SyllabusImport, syl.seed_id("old-key"))
        assert old.status == "approved" and old.deleted is False
        assert db.scalar(select(Topic).where(Topic.title == "Topic")) is not None  # its topics stay


def test_supersedes_leaves_the_old_one_when_the_new_one_already_existed(env, folder):
    settings, factory = env
    _write(folder, "old-key")
    _write(folder, "new-key", supersedes="old-key")
    with factory() as db:
        db.add(SyllabusImport(id=syl.seed_id("new-key"), exam="UPSC CSE", title="new", status="pending", tree_json=[]))
        db.add(SyllabusImport(id=syl.seed_id("old-key"), exam="UPSC CSE", title="old", status="pending", tree_json=[]))
        db.commit()
        assert syl.seed_starters(db, settings) == 0
        db.expire_all()
        assert db.get(SyllabusImport, syl.seed_id("old-key")).deleted is False  # the new one was not added in this run: hands off


def test_supersedes_keeps_an_owner_deleted_import_deleted_and_survives_bad_values(env, folder):
    settings, factory = env
    _write(folder, "old-key")
    with factory() as db:
        syl.seed_starters(db, settings)
        db.get(SyllabusImport, syl.seed_id("old-key")).deleted = True
        db.commit()
        _write(folder, "new-key", supersedes="old-key")
        _write(folder, "other-key", supersedes="does-not-exist")
        _write(folder, "self-key", supersedes="self-key")
        _write(folder, "empty-key", supersedes="")
        assert syl.seed_starters(db, settings) == 4
        assert db.get(SyllabusImport, syl.seed_id("old-key")).deleted is True
        assert db.get(SyllabusImport, syl.seed_id("self-key")).deleted is False


def test_old_outline_still_in_the_folder_is_not_seeded_again_beside_its_replacement(env, folder):
    settings, factory = env
    _write(folder, "old-key")
    _write(folder, "new-key", supersedes="old-key")
    with factory() as db:
        assert syl.seed_starters(db, settings) == 1  # only the replacement is added on a fresh install
        assert db.get(SyllabusImport, syl.seed_id("old-key")) is None and db.get(SyllabusImport, syl.seed_id("new-key")) is not None


def _raw_count(nodes):
    return sum(1 + _raw_count(n.get("children", [])) for n in nodes)


def _walk(nodes):
    for n in nodes:
        yield n
        yield from _walk(n.get("children", []))


def test_shipped_outlines_load_and_survive_clean_tree(env, monkeypatch):
    settings, _ = env
    monkeypatch.setenv("SYLLABUS_DIR", str(REPO / "data" / "syllabus"))
    docs = {d["key"]: d for d in syl.load_starters(settings)}
    for key in ("appsc-g1-prelims-2026", "appsc-g1-mains-2026", "slprb-si-written"):
        assert key in docs, f"{key} did not load"
        raw = docs[key]["tree"]
        cleaned = clean_tree(raw)
        assert count_nodes(cleaned) == _raw_count(raw), f"{key}: clean_tree dropped or merged nodes"
        assert docs[key].get("verified") is True
    assert docs["appsc-g1-prelims-2026"]["supersedes"] == "appsc-group1-prelims" and docs["appsc-g1-mains-2026"]["supersedes"] == "appsc-group1-mains"
    assert "appsc-group1-prelims" not in docs and "appsc-group1-mains" not in docs  # the old files live in _superseded/ now
    si = docs["slprb-si-written"]
    raw_si = [n for n in _walk(si["tree"]) if "SI" in (n.get("exam_tags") or [])]
    cleaned_si = clean_tree(si["tree"])
    kept = [n for n in _walk(cleaned_si) if "SI" in n["exam_tags"]]
    assert raw_si and len(kept) == count_nodes(cleaned_si) >= len(raw_si)  # every node carries "SI" (inherited from its paper)
    assert exam_tags_for(si["exam"]) == ["SI"]


def test_seeding_the_shipped_files_retires_old_pending_group1_outlines(env, monkeypatch):
    settings, factory = env
    monkeypatch.setenv("SYLLABUS_DIR", str(REPO / "data" / "syllabus"))
    with factory() as db:
        old_ids = [syl.seed_id("appsc-group1-prelims"), syl.seed_id("appsc-group1-mains")]
        for i in old_ids:
            db.add(SyllabusImport(id=i, exam="APPSC Group-I", title="old", status="pending", tree_json=[]))
        db.commit()
        syl.seed_starters(db, settings)
        db.expire_all()
        assert all(db.get(SyllabusImport, i).deleted for i in old_ids)
        assert db.get(SyllabusImport, syl.seed_id("slprb-si-written")).status == "pending"


def test_syllabus_tree_route_filters_by_si(client, auth_header, monkeypatch):
    monkeypatch.setenv("SYLLABUS_DIR", str(REPO / "data" / "syllabus"))
    client.post("/syllabus/seed", headers=auth_header)  # (start-up already seeded the same files: adding again is a no-op)
    with client.app.state.services.session_factory() as db:
        imp = db.get(SyllabusImport, syl.seed_id("slprb-si-written"))
        assert imp is not None
    r = client.post(f"/syllabus/{syl.seed_id('slprb-si-written')}/approve", json={}, headers=auth_header)
    assert r.status_code == 200
    si = client.get("/syllabus/tree?exam=SI", headers=auth_header).json()
    assert si["exam"] == "SI" and si["leaves"] > 20 and si["tree"]
    assert client.get("/syllabus/tree?exam=UPSC", headers=auth_header).json()["tree"] == []
    assert client.get("/syllabus/tree?exam=APPSC", headers=auth_header).json()["tree"] == []
    with client.app.state.services.session_factory() as db:
        assert all("SI" in (t.exam_tags or []) for t in db.scalars(select(Topic)))


# ---------------------------------------------------------------- the aptitude test kind


def _si_topic(db, title, tags=("SI",), approved=True, deleted=False):
    t = Topic(title=title, level=2, approved=approved, exam_tags=list(tags), deleted=deleted)
    db.add(t)
    db.flush()
    return t


def test_generate_aptitude_test_single_area(db):
    set_kv(db, "test.negative_marking", True)  # the owner's default must not apply to this drill
    decoys = [_si_topic(db, "Percentage", tags=("UPSC",)), _si_topic(db, "Percentage", approved=False), _si_topic(db, "Percentage", deleted=True)]
    real = _si_topic(db, "Percentage")
    assert decoys and real
    day = date(2026, 10, 5)
    test = generate.generate_test(db, None, {"kind": "aptitude", "area": "percentage", "count": 10, "date": day.isoformat()})
    assert test.kind == "aptitude" and len(test.kind) <= 20
    assert test.title == "Aptitude drill: Percentage" and test.status == "ready" and test.negative_marking is False
    assert test.duration_min == math.ceil(10 * 1.25) == 13 and to_ist_date(test.scheduled_for) == day
    rows = [db.get(Mcq, i) for i in test.mcq_ids]
    assert len(rows) == 10 and {r.source_type for r in rows} == {"mock"} and {r.source_id for r in rows} == {test.id}
    assert {r.topic_id for r in rows} == {real.id}
    for r in rows:
        assert len(r.options) == 4 and 0 <= r.answer_index <= 3 and r.options[r.answer_index] in r.explanation
    assert [r.question for r in rows] == [q["question"] for q in aptitude.generate_questions("percentage", 10, aptitude.area_seed(day, "percentage"))]


def test_generate_aptitude_test_mixed_default_and_unlinked_topics(db):
    day = today_ist()
    test = generate.generate_test(db, None, {"kind": "aptitude"})
    assert test.title == "Aptitude drill: mixed" and len(test.mcq_ids) == 20 and test.duration_min == 25 and to_ist_date(test.scheduled_for) == day
    assert {db.get(Mcq, i).topic_id for i in test.mcq_ids} == {None}  # no SI topics in this database
    rows = [db.get(Mcq, i) for i in test.mcq_ids]
    assert [r.question for r in rows] == [q["question"] for q in aptitude.mixed_questions(day, 20)]
    linked = _si_topic(db, "Ratio and proportion")  # the label matches the syllabus title ignoring '&' / 'and'
    t2 = generate.generate_test(db, None, {"kind": "aptitude", "area": "ratio_proportion", "count": 5, "date": "2026-10-06"})
    assert {db.get(Mcq, i).topic_id for i in t2.mcq_ids} == {linked.id}


def test_generate_aptitude_test_is_idempotent_and_validates(db):
    a = generate.generate_test(db, None, {"kind": "aptitude", "area": "Profit & loss", "date": "2026-10-07"})
    b = generate.generate_test(db, None, {"kind": "aptitude", "area": "profit_loss", "date": "2026-10-07"})
    c = generate.generate_test(db, None, {"kind": "aptitude", "area": "profit and loss", "date": "2026-10-08"})
    assert a.id == b.id and c.id != a.id and a.title == "Aptitude drill: Profit & loss"
    assert db.query(TestRow).filter(TestRow.kind == "aptitude").count() == 2 and db.query(Mcq).count() == 40
    with pytest.raises(JobFailed):
        generate.generate_test(db, None, {"kind": "aptitude", "area": "astrology"})
    tiny = generate.generate_test(db, None, {"kind": "aptitude", "area": "series", "count": 1, "date": "2026-10-09"})
    huge = generate.generate_test(db, None, {"kind": "aptitude", "area": "series", "count": 500, "date": "2026-10-09"})
    assert len(tiny.mcq_ids) == 5 and len(huge.mcq_ids) == 40 and huge.duration_min == 50


def test_test_generate_job_makes_the_drill_and_announces_it(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    with factory() as db:
        job = Job(type="test_generate", payload_json={"kind": "aptitude", "area": "clocks_calendars", "date": "2026-10-10", "count": 8})
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        job = db.get(Job, jid)
        assert job.status == "done", job.error
        test = db.get(TestRow, job.result_json["test_id"])
        assert test.kind == "aptitude" and len(test.mcq_ids) == 8 and test.title == "Aptitude drill: Clocks & calendars"
    title, body, data = svc.extras["pushed"][0]
    assert "Aptitude drill is ready" in body and data["type"] == "mock_ready" and data["test_id"] == test.id


def test_api_generate_aptitude(client, auth_header):
    r = client.post("/tests/generate", headers=auth_header, json={"kind": "aptitude", "area": "percentage", "date": "2026-10-11"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["kind"] == "aptitude" and len(body["mcq_ids"]) == 20 and body["negative_marking"] is False and body["status"] == "ready"
    assert client.post("/tests/generate", headers=auth_header, json={"kind": "aptitude", "area": "percentage", "date": "2026-10-11"}).json()["id"] == body["id"]
    bad = client.post("/tests/generate", headers=auth_header, json={"kind": "aptitude", "area": "astrology"})
    assert bad.status_code == 400 and "astrology" in bad.json()["detail"]
    mixed = client.post("/tests/generate", headers=auth_header, json={"kind": "aptitude", "count": 12, "date": "2026-10-12"}).json()
    assert len(mixed["mcq_ids"]) == 12 and mixed["title"] == "Aptitude drill: mixed"


def test_scoring_and_mistakes_work_for_the_aptitude_kind(db):
    test = generate.generate_test(db, None, {"kind": "aptitude", "area": "average", "count": 5, "date": "2026-10-13"})
    now = datetime.now(timezone.utc)
    for i, mid in enumerate(test.mcq_ids):
        mcq = db.get(Mcq, mid)
        wrong = (mcq.answer_index + 1) % 4
        db.add(Attempt(mcq_id=mid, test_id=test.id, chosen=mcq.answer_index if i < 3 else wrong, correct=i < 3, confidence="sure", at=now))
    test.status = "done"
    db.flush()
    assert scoring.maybe_analyse(db, test) is True
    assert test.status == "analysed" and test.analysis_json["correct"] == 3 and test.analysis_json["wrong"] == 2
    assert test.analysis_json["negative_marking"] is False and test.score == 3.0  # no negative marks
    assert test.analysis_json["subjects"][0]["subject"] == "Other"  # a question with no syllabus topic
    wrong_mcq = db.get(Mcq, test.mcq_ids[4])
    m = Mistake(mcq_id=wrong_mcq.id, mistake_type="didnt_know", your_answer=(wrong_mcq.answer_index + 1) % 4)
    db.add(m)
    db.flush()
    card = mistakes.make_card_for_mistake(db, m)
    assert card is not None and wrong_mcq.options[wrong_mcq.answer_index] in card.back
    assert db.query(Card).count() == 1


# ---------------------------------------------------------------- plan hooks


WEEKDAY_TITLES = {
    0: "Run: 1600 m pace work", 1: "Sprint and long jump drills", 2: "Run: 1600 m pace work", 3: "Sprint and long jump drills",
    4: "Run: 1600 m pace work", 5: "PET simulation: 1600 m + 100 m or long jump", 6: "Mobility and rest",
}


def test_physical_block_content_for_every_weekday(db):
    add_exam(db)
    for d in upcoming(7):
        blocks = si_plan.add_physical_block(db, d.isoformat(), [], {})
        assert len(blocks) == 1
        b = blocks[0]
        assert b["id"] == f"phys-{d.isoformat()}" and b["kind"] == "other" and b["start"] == "06:00" and b["ref"] == "goals" and b["topic_id"] is None
        assert b["title"] == WEEKDAY_TITLES[d.weekday()] and b["minutes"] == (20 if d.weekday() == 6 else 45)
        assert set(b) == {"id", "kind", "start", "minutes", "title", "detail", "topic_id", "ref"}
        assert b["detail"]
        again = si_plan.add_physical_block(db, d.isoformat(), blocks, {})
        assert len(again) == 1  # idempotent
    monday = next(d for d in upcoming(7) if d.weekday() == 0)
    assert "4 x 400 m" in si_plan.add_physical_block(db, monday.isoformat(), [], {})[0]["detail"]
    saturday = next(d for d in upcoming(7) if d.weekday() == 5)
    assert "log the times in Goals" in si_plan.add_physical_block(db, saturday.isoformat(), [], {})[0]["detail"]


def test_hooks_only_while_an_si_exam_is_live(db):
    day = upcoming(7)[1].isoformat()

    def both():
        return si_plan.add_physical_block(db, day, [], {}), si_plan.add_aptitude_drill(db, day, [], {})

    assert both() == ([], [])  # no exam at all
    add_exam(db, "UPSC CSE")
    add_exam(db, "Mission Impossible")
    add_exam(db, "APPSC Group-I")
    assert both() == ([], [])  # other exams (and a name with "si" inside a word) do not count
    gone = add_exam(db, SI_NAME, deleted=True)
    assert both() == ([], [])
    gone.deleted = False
    gone.date = datetime.now(timezone.utc) - timedelta(days=3)
    db.flush()
    assert both() == ([], [])  # the exam is in the past
    gone.date = datetime.now(timezone.utc) + timedelta(days=40)
    db.flush()
    assert all(x for x in both())  # in the future: live
    gone.date = None
    db.flush()
    assert all(x for x in both())  # no date: live
    other = add_exam(db, "SLPRB Sub-Inspector Final", datetime.now(timezone.utc) - timedelta(days=30))
    assert all(x for x in both()) and other  # one exam still to come is enough


def test_physical_switch_and_past_days(db):
    add_exam(db)
    day = upcoming(7)[2].isoformat()
    set_kv(db, "si.physical_plan", False)
    assert si_plan.add_physical_block(db, day, [], {}) == []
    assert si_plan.add_aptitude_drill(db, day, [], {}) or date.fromisoformat(day).weekday() == 6  # the drill is separate (Sunday has none)
    set_kv(db, "si.physical_plan", True)
    assert len(si_plan.add_physical_block(db, day, [], {})) == 1
    set_kv(db, "si.physical_plan", None)
    assert len(si_plan.add_physical_block(db, day, [], {})) == 1  # only an explicit false switches it off
    yesterday = (today_ist() - timedelta(days=1)).isoformat()
    assert si_plan.add_physical_block(db, yesterday, [], {}) == [] and si_plan.add_aptitude_drill(db, yesterday, [], {}) == []
    assert si_plan.add_physical_block(db, "not a date", [], {}) == [] and si_plan.add_aptitude_drill(db, "", [], {}) == []


def test_aptitude_drill_block_and_test(db):
    add_exam(db)
    for d in upcoming(7):
        blocks = si_plan.add_aptitude_drill(db, d.isoformat(), [], {})
        if d.weekday() == 6:
            assert blocks == []  # no drill on Sunday
            continue
        assert len(blocks) == 1
        b = blocks[0]
        assert b["id"] == f"drill-{d.isoformat()}" and b["kind"] == "practice" and b["start"] == "19:30" and b["minutes"] == 25
        assert b["title"] == "Aptitude drill (20 questions)" and b["topic_id"] is None
        assert aptitude.LABELS[aptitude.area_for_date(d)] in b["detail"]
        assert b["ref"].startswith("test/")
        test = db.get(TestRow, b["ref"].split("/", 1)[1])
        assert test.kind == "aptitude" and len(test.mcq_ids) == 20 and test.status == "ready" and test.negative_marking is False
        assert test.duration_min == 25 and to_ist_date(test.scheduled_for) == d and test.title == "Aptitude drill: mixed"
        again = si_plan.add_aptitude_drill(db, d.isoformat(), blocks, {})
        assert len(again) == 1  # idempotent on the blocks
        second = si_plan.add_aptitude_drill(db, d.isoformat(), [], {})
        assert second[0]["ref"] == b["ref"]  # and on the test: found, not made again
    assert db.query(TestRow).filter(TestRow.kind == "aptitude").count() == 6


def test_drill_failure_skips_the_block_and_never_raises(db, monkeypatch):
    add_exam(db)
    day = next(d for d in upcoming(7) if d.weekday() != 6).isoformat()

    def boom(*a, **k):
        raise RuntimeError("generator broke")

    monkeypatch.setattr(si_plan, "generate_aptitude_test", boom)
    assert si_plan.add_aptitude_drill(db, day, [{"id": "x"}], {}) == [{"id": "x"}]
    assert len(si_plan.add_physical_block(db, day, [], {})) == 1  # the physical block is unaffected


def test_hooks_are_registered_with_the_planner():
    assert si_plan.add_physical_block in plan_hooks.PLAN_POSTPROCESSORS and si_plan.add_aptitude_drill in plan_hooks.PLAN_POSTPROCESSORS
    from app.features import FEATURES

    assert "si" in FEATURES


def test_physical_block_is_outside_the_study_hours_end_to_end(db, env):
    settings, _ = env
    add_exam(db)
    polity = Topic(title="Polity", level=1, approved=True)
    db.add(polity)
    db.flush()
    for i in range(6):
        db.add(Topic(title=f"Topic {i}", parent_id=polity.id, level=2, approved=True, est_hours=1.5, importance=5, exam_tags=["SI"], position=i))
    db.flush()
    rows = service.plan_range(db, settings, today_ist(), 7)
    assert len(rows) == 7
    for row in rows:
        d = date.fromisoformat(row.date)
        ids = {b["id"] for b in row.blocks_json}
        assert f"phys-{row.date}" in ids
        phys = next(b for b in row.blocks_json if b["id"] == f"phys-{row.date}")
        assert (f"drill-{row.date}" in ids) == (d.weekday() != 6)
        total = sum(b["minutes"] for b in row.blocks_json)
        hours = eng.hours_for(d, service._hours(db)) * 60
        assert total - phys["minutes"] <= hours  # what the owner studies fits his hours
        assert eng.counted_minutes(row.blocks_json) == total - phys["minutes"]
        assert service.plan_dict(row)["minutes"] == total - phys["minutes"]
        assert row.blocks_json == sorted(row.blocks_json, key=lambda b: (b["start"], b["id"]))
        assert any(b["kind"] == "brief" for b in row.blocks_json)  # the day still has its normal blocks
    assert db.query(DailyPlan).count() == 7
    again = service.plan_range(db, settings, today_ist(), 7)
    assert [r.id for r in again] == [r.id for r in rows]  # planning again: same rows...
    assert db.query(TestRow).filter(TestRow.kind == "aptitude").count() == 6  # ...and no second drill test
    for row in again:
        assert len([b for b in row.blocks_json if b["id"].startswith("phys-")]) == 1


def test_counted_minutes_and_fit_to_hours_ignore_physical_blocks():
    blocks = [{"id": "s", "kind": "study", "minutes": 120, "start": "09:00"}, {"id": "phys-2026-09-21", "kind": "other", "minutes": 45, "start": "06:00"},
              {"id": "drill-2026-09-21", "kind": "practice", "minutes": 25, "start": "19:30"}]
    assert eng.counted_minutes(blocks) == 145 and plan_hooks.counts_toward_hours(blocks[0]) and not plan_hooks.counts_toward_hours(blocks[1])
    out, over = eng.fit_to_hours([dict(b) for b in blocks], 145, {"phys-2026-09-21", "drill-2026-09-21"})
    assert over == 0 and sum(b["minutes"] for b in out) == 190  # nothing was cut for the 45 physical minutes
    out, over = eng.fit_to_hours([dict(b) for b in blocks], 100, {"phys-2026-09-21", "drill-2026-09-21"})
    assert over == 0 and next(b for b in out if b["id"] == "s")["minutes"] == 75  # study is cut for the drill and the hours only


# ---------------------------------------------------------------- GET /si/spec


def test_si_spec_route(client, auth_header, monkeypatch, tmp_path):
    monkeypatch.setenv("EXAM_SPECS_DIR", str(REPO / "data" / "exam-specs"))
    r = client.get("/si/spec", headers=auth_header)
    assert r.status_code == 200 and r.json()["key"] == "slprb-si-2026" and r.json()["stages_pc11"]
    assert r.json() == json.loads((REPO / "data" / "exam-specs" / "slprb_si_2026.json").read_text(encoding="utf-8"))
    monkeypatch.setenv("EXAM_SPECS_DIR", str(tmp_path / "nothing"))
    assert client.get("/si/spec", headers=auth_header).status_code == 404
    (tmp_path / "broken").mkdir()
    (tmp_path / "broken" / "slprb_si_2026.json").write_text("{nope", encoding="utf-8")
    monkeypatch.setenv("EXAM_SPECS_DIR", str(tmp_path / "broken"))
    assert client.get("/si/spec", headers=auth_header).status_code == 404
    assert client.get("/si/spec").status_code in (401, 403)  # login is required

