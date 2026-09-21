from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.api.kv import set_kv
from app.db.models import Card
from app.db.models_v2 import Attempt, DocPage, Document, Job, Mcq, Mistake, Note, Pyq, Topic
from app.db.models_v2 import Test as TestRow
from app.db.session import get_session_factory
from app.features.plan_hooks import run_postprocessors
from app.features.tests import generate, jobs, mistakes, pyq, scoring
from app.features.tests.common import marks
from app.features.tests.schemas import GenMcq, McqBatch, PyqBatch, PyqItem
from app.jobs.registry import AiUnavailable, JobFailed
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway

NOTE_TEXT = "The Preamble declares India a sovereign socialist secular democratic republic. Article 21 protects life."


def q(n, ans=0, topic_ref=1):
    opts = [f"Option {n} A", f"Option {n} B", f"Option {n} C", f"Option {n} D"]
    return GenMcq(question=f"Question number {n} about the Constitution?", options=opts, answer_index=ans,
                  explanation=f"The answer is {opts[ans]} because the note says so.", topic_ref=topic_ref)


def world(db):
    paper = Topic(title="GS Paper 2", level=0, approved=True)
    db.add(paper)
    db.flush()
    subject = Topic(title="Polity", level=1, parent_id=paper.id, approved=True, status="studied")
    db.add(subject)
    db.flush()
    topic = Topic(title="Preamble", level=2, parent_id=subject.id, approved=True, status="studied")
    db.add(topic)
    db.flush()
    db.add(Note(topic_id=topic.id, sections={"key_points": ["Sovereign socialist secular", {"text": "Article 21 protects life"}],
                                             "must_remember": ["Preamble: We the people"]}, content_md=NOTE_TEXT))
    db.commit()
    return subject, topic


def make_mcqs(db, topic, n=4):
    rows = []
    for i in range(n):
        m = Mcq(source_type="note", question=f"Sample question {i} for scoring?", options=["a1", "b1", "c1", "d1"],
                answer_index=1, explanation="b1 is right", topic_id=topic.id)
        db.add(m)
        rows.append(m)
    db.commit()
    return rows


# ---------------------------------------------------------------- pure scoring


def test_marks_and_default_types():
    assert marks(10, 3, False) == 10
    assert marks(10, 3, True) == 9.0
    assert scoring.default_mistake_type("sure") == "silly"
    assert scoring.default_mistake_type("unsure") == "confused"
    assert scoring.default_mistake_type("guess") == "didnt_know"
    assert scoring.default_mistake_type("") == "didnt_know"


def test_validate_mcq_rules():
    assert generate.validate_mcq(q(1)) is None
    bad = q(2)
    bad.options = ["x", "x", "y", "z"]
    assert generate.validate_mcq(bad) == "options repeat"
    bad = q(3)
    bad.explanation = "Nothing useful here at all."
    assert generate.validate_mcq(bad) == "explanation does not state the answer"
    bad = q(4)
    bad.answer_index = 7
    assert generate.validate_mcq(bad) == "answer position out of range"
    assert generate.allocate(3, 10) == [4, 3, 3]


def test_analyse_counts(env):
    settings, factory = env
    with factory() as db:
        subject, topic = world(db)
        mcqs = make_mcqs(db, topic, 4)
        now = datetime.now(timezone.utc)
        rows = [
            Attempt(mcq_id=mcqs[0].id, test_id="t", chosen=1, correct=True, confidence="sure", at=now),
            Attempt(mcq_id=mcqs[1].id, test_id="t", chosen=0, correct=False, confidence="guess", at=now),
            Attempt(mcq_id=mcqs[2].id, test_id="t", chosen=2, correct=False, confidence="sure", at=now),
            Attempt(mcq_id=mcqs[3].id, test_id="t", chosen=-1, correct=False, at=now),
        ]
        topics = {t.id: t for t in db.scalars(select(Topic))}
        out = scoring.analyse(rows, {m.id: m for m in mcqs}, topics, negative=True)
    assert (out["correct"], out["wrong"], out["skipped"]) == (1, 2, 1)
    assert out["score"] == round(1 - 2 / 3, 2)
    assert out["mistakes"] == {"didnt_know": 1, "confused": 0, "silly": 1}
    assert out["subjects"][0]["subject"] == "Polity" and out["subjects"][0]["total"] == 4
    assert out["study_topics"][0]["title"] == "Preamble"
    assert out["guessing"]["guesses"] == 1 and "guessed 1 question" in out["guessing"]["message"]
    assert len(out["tips"]) == 2


# ---------------------------------------------------------------- generation


def test_topic_test_and_negative_default(env):
    settings, factory = env
    gw = FullFakeGateway([McqBatch(questions=[q(i) for i in range(1, 8)])])
    with factory() as db:
        subject, topic = world(db)
        set_kv(db, "test.negative_marking", True)
        test = generate.generate_test(db, gw, {"kind": "topic", "topic_id": topic.id, "count": 5})
        db.commit()
        assert test.kind == "topic" and len(test.mcq_ids) == 5 and test.negative_marking is True
        assert test.status == "ready" and test.duration_min >= 10
        assert all(db.get(Mcq, i).topic_id == topic.id for i in test.mcq_ids)
    assert gw.calls[0][0] == "test_generate"
    assert "Preamble" in gw.calls[0][1]


def test_generation_needs_notes_and_ai(env):
    settings, factory = env
    with factory() as db:
        t = Topic(title="Empty", level=2, approved=True)
        db.add(t)
        db.commit()
        with pytest.raises(JobFailed):
            generate.generate_test(db, FullFakeGateway([]), {"kind": "topic", "topic_id": t.id})
        subject, topic = world(db)
        with pytest.raises(AiUnavailable):
            generate.generate_test(db, FullFakeGateway([]), {"kind": "topic", "topic_id": topic.id})
        with pytest.raises(AiUnavailable):  # only two usable questions
            generate.generate_test(db, FullFakeGateway([McqBatch(questions=[q(1), q(2)]), McqBatch(questions=[])]),
                                   {"kind": "topic", "topic_id": topic.id, "count": 5})
        with pytest.raises(JobFailed):
            generate.generate_test(db, FullFakeGateway([]), {"kind": "nonsense"})


def test_weekly_uses_week_topics_and_is_not_repeated(env):
    settings, factory = env
    gw = FullFakeGateway([McqBatch(questions=[q(i) for i in range(1, 12)])])
    with factory() as db:
        world(db)
        sunday = date(2026, 9, 27)
        test = generate.generate_test(db, gw, {"kind": "weekly", "date": sunday.isoformat(), "count": 8})
        db.commit()
        assert test.kind == "weekly" and len(test.mcq_ids) == 8 and test.duration_min == 30
        again = generate.generate_test(db, gw, {"kind": "weekly", "date": sunday.isoformat()})
        assert again.id == test.id
        assert generate.find_weekly(db, sunday).id == test.id


def test_past_paper_and_mistakes_test(env):
    settings, factory = env
    with factory() as db:
        subject, topic = world(db)
        for i in range(3):
            db.add(Pyq(exam="UPSC", year=2021, paper="GS1", question=f"Past question {i} about federalism?",
                       options=["a", "b", "c", "d"], answer_index=2, topic_ids=[topic.id]))
        db.add(Pyq(exam="UPSC", year=2021, paper="GS1", question="No answer question here?", options=["a", "b", "c", "d"], answer_index=-1))
        db.commit()
        test = generate.generate_test(db, FullFakeGateway([]), {"kind": "past_paper", "exam": "upsc", "year": 2021})
        assert len(test.mcq_ids) == 3
        with pytest.raises(JobFailed):
            generate.generate_test(db, FullFakeGateway([]), {"kind": "past_paper", "exam": "UPSC", "year": 1990})
        with pytest.raises(JobFailed):
            generate.generate_test(db, FullFakeGateway([]), {"kind": "mistakes"})
        mcqs = make_mcqs(db, topic, 3)
        for m in mcqs:
            db.add(Mistake(mcq_id=m.id, mistake_type="didnt_know", your_answer=0))
        db.commit()
        mt = generate.generate_test(db, FullFakeGateway([]), {"kind": "mistakes"})
        assert set(mt.mcq_ids) == {m.id for m in mcqs}


# ---------------------------------------------------------------- jobs, hooks, cards


def test_mock_job_runs_and_announces(env):
    settings, factory = env
    gw = FullFakeGateway([McqBatch(questions=[q(i) for i in range(1, 10)])])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        world(db)
        job = Job(type="mock_test", payload_json={"kind": "weekly", "date": "2026-09-27", "count": 6})
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        job = db.get(Job, jid)
        assert job.status == "done", job.error
        test = db.get(TestRow, job.result_json["test_id"])
        assert len(test.mcq_ids) == 6
    assert svc.extras["pushed"][0][2]["type"] == "mock_ready"


def test_test_generate_job_failure_is_kind(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    with factory() as db:
        job = Job(type="test_generate", payload_json={"kind": "topic"})
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        job = db.get(Job, jid)
        assert job.status == "failed" and "topic" in job.error.lower()


def test_mistake_cards_once(env):
    settings, factory = env
    with factory() as db:
        subject, topic = world(db)
        mcqs = make_mcqs(db, topic, 3)
        know = Mistake(mcq_id=mcqs[0].id, mistake_type="didnt_know", your_answer=0)
        conf = Mistake(mcq_id=mcqs[1].id, mistake_type="confused", your_answer=0)
        silly = Mistake(mcq_id=mcqs[2].id, mistake_type="silly", your_answer=0)
        db.add_all([know, conf, silly])
        db.commit()
        c1 = mistakes.make_card_for_mistake(db, know)
        c2 = mistakes.make_card_for_mistake(db, conf)
        assert mistakes.make_card_for_mistake(db, silly) is None
        assert mistakes.make_card_for_mistake(db, know) is None  # never twice
        db.commit()
        assert c1.group == "Polity" and c1.source_type == "mistake" and "b1" in c1.back
        assert "a1" in c2.front and "b1" in c2.front and c2.due_at is not None
        assert db.scalars(select(Card).where(Card.source_type == "mistake")).all().__len__() == 2


def test_sync_push_analyses_when_everything_arrived(client, auth_header):
    factory = get_session_factory()
    with factory() as db:
        subject, topic = world(db)
        mcqs = make_mcqs(db, topic, 3)
        test = TestRow(kind="topic", title="T", mcq_ids=[m.id for m in mcqs], status="ready")
        db.add(test)
        db.commit()
        tid, ids, topic_id = test.id, [m.id for m in mcqs], topic.id
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    attempts = [{"id": f"a{i}", "updated_at": now, "mcq_id": ids[i], "test_id": tid, "chosen": 1 if i == 0 else 0,
                 "correct": i == 0, "confidence": "guess", "at": now} for i in range(3)]
    finish = {"id": tid, "updated_at": now, "status": "done", "score": 1.0, "finished_at": now}
    # the test row arrives first: not all answers are here yet, so no analysis
    r = client.post("/sync/push", headers=auth_header, json={"tables": {"tests": [finish], "attempts": attempts[:2]}})
    assert r.status_code == 200 and r.json()["rejected"] == []
    with factory() as db:
        assert db.get(TestRow, tid).analysis_json is None
    later = (datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat().replace("+00:00", "Z")
    attempts[2]["updated_at"] = later
    client.post("/sync/push", headers=auth_header, json={"tables": {"attempts": [attempts[2]]}})
    with factory() as db:
        t = db.get(TestRow, tid)
        assert t.status == "analysed" and t.analysis_json["correct"] == 1 and t.analysis_json["wrong"] == 2
        assert t.analysis_json["study_topics"][0]["topic_id"] == topic_id
    r = client.get(f"/tests/{tid}/analysis", headers=auth_header)
    assert r.status_code == 200 and r.json()["analysis"]["total"] == 3
    # the mistake row triggers a card
    mrow = {"id": "m1", "updated_at": later, "mcq_id": ids[1], "mistake_type": "didnt_know", "your_answer": 0}
    client.post("/sync/push", headers=auth_header, json={"tables": {"mistakes": [mrow]}})
    with factory() as db:
        assert db.scalars(select(Card).where(Card.source_type == "mistake", Card.source_id == ids[1])).first() is not None


# ---------------------------------------------------------------- past papers


def test_pyq_import_and_mapping(env):
    settings, factory = env
    gw = FullFakeGateway([PyqBatch(questions=[
        PyqItem(number=1, question="Article 21 of the Constitution protects which right?", options=["Life", "Trade", "Vote", "Rest"], answer_index=0),
        PyqItem(number=2, question="Cut off question", options=["a", "b"]),
        PyqItem(number=3, question="Who declared the Preamble sovereign socialist secular?", options=["A", "B", "C", "D"]),
    ])])
    with factory() as db:
        subject, topic = world(db)
        topic.title = "Article 21 right to life"
        doc = Document(title="UPSC 2020 paper", pages=1)
        db.add(doc)
        db.flush()
        db.add(DocPage(document_id=doc.id, page=1, text="1. Article 21 ..."))
        db.commit()
        out = pyq.import_pyqs(db, gw, doc.id, "UPSC", 2020, "GS1")
        db.commit()
        assert out["added"] == 2 and out["skipped"] == 1 and out["no_answer"] == 1
        rows = list(db.scalars(select(Pyq)))
        assert any(r.topic_ids == [topic.id] for r in rows)
        # importing the same text again adds nothing
        gw2 = FullFakeGateway([PyqBatch(questions=[PyqItem(question="Article 21 of the Constitution protects which right?", options=["Life", "Trade", "Vote", "Rest"])])])
        assert pyq.import_pyqs(db, gw2, doc.id, "UPSC", 2020)["added"] == 0
        with pytest.raises(AiUnavailable):
            pyq.import_pyqs(db, FullFakeGateway([]), doc.id, "UPSC", 2020)
        with pytest.raises(JobFailed):
            pyq.import_pyqs(db, gw, "missing", "UPSC", 2020)
        assert pyq.map_all(db)["questions"] == 2


def test_pyq_job(env):
    settings, factory = env
    gw = FullFakeGateway([PyqBatch(questions=[PyqItem(question="A long enough question text?", options=["a", "b", "c", "d"], answer_index=3)])])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        doc = Document(title="p")
        db.add(doc)
        db.flush()
        db.add(DocPage(document_id=doc.id, page=1, text="text"))
        job = Job(type="pyq_import", payload_json={"document_id": doc.id, "exam": "APPSC", "year": 2019})
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        assert db.get(Job, jid).result_json["added"] == 1
        assert db.scalars(select(Pyq)).first().answer_index == 3


# ---------------------------------------------------------------- plan, schedule, api


def test_plan_block_for_weekly_mock(env):
    settings, factory = env
    gw = FullFakeGateway([McqBatch(questions=[q(i) for i in range(1, 9)])])
    with factory() as db:
        world(db)
        assert run_postprocessors(db, "2026-09-27", [], {"last_month": False}) == []
        test = generate.generate_test(db, gw, {"kind": "weekly", "date": "2026-09-27", "count": 6})
        db.commit()
        blocks = run_postprocessors(db, "2026-09-27", [], {"last_month": False})
        mock = [b for b in blocks if b["kind"] == "mock"]
        assert mock and mock[0]["ref"] == f"test/{test.id}" and mock[0]["minutes"] == 30
        assert not [b for b in run_postprocessors(db, "2026-09-26", [], {}) if b["kind"] == "mock"]


def test_queue_weekly_mock_respects_day(env, monkeypatch):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    kicked = []
    svc.kick_jobs = lambda: kicked.append(1)
    monkeypatch.setattr(jobs, "today_ist", lambda: date(2026, 9, 26))  # Saturday, tomorrow is Sunday
    jobs.queue_weekly_mock(svc)
    jobs.queue_weekly_mock(svc)  # a second call does not queue twice
    with factory() as db:
        rows = list(db.scalars(select(Job).where(Job.type == "mock_test")))
        assert len(rows) == 1 and rows[0].payload_json["date"] == "2026-09-27"
        set_kv(db, "test.weekly_day", "wed")
        db.execute(Job.__table__.delete())
        db.commit()
    jobs.queue_weekly_mock(svc)
    with factory() as db:
        assert db.scalars(select(Job)).first() is None
    monkeypatch.setattr(jobs, "today_ist", lambda: date(2026, 9, 29))  # Tuesday, tomorrow is Wednesday
    jobs.queue_weekly_mock(svc)
    with factory() as db:
        assert db.scalars(select(Job)).first().payload_json["date"] == "2026-09-30"


def test_api_generate_and_pyq_routes(client, auth_header):
    factory = get_session_factory()
    with factory() as db:
        subject, topic = world(db)
        db.add(Pyq(exam="UPSC", year=2022, paper="GS2", question="Which article deals with the Preamble?", options=["a", "b", "c", "d"],
                   answer_index=1, topic_ids=[]))
        db.commit()
        tid = topic.id
    r = client.post("/tests/generate", headers=auth_header, json={"kind": "mistakes"})
    assert r.status_code == 400 and "mistake" in r.json()["detail"].lower()
    r = client.post("/tests/generate", headers=auth_header, json={"kind": "topic", "topic_id": tid})
    assert r.status_code == 503  # no AI keys in the test app
    r = client.get("/tests/pyq/stats", headers=auth_header)
    assert r.json()["total"] == 1 and r.json()["with_answer"] == 1 and r.json()["papers"][0]["year"] == 2022
    r = client.post("/tests/pyq/map", headers=auth_header)
    assert r.status_code == 200 and r.json()["questions"] == 1
    assert client.get("/tests/nope/analysis", headers=auth_header).status_code == 404
