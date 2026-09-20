import io
from datetime import datetime
from zoneinfo import ZoneInfo

from PIL import Image
from sqlalchemy import select

from app.config import get_settings
from app.db.models_v2 import AnswerSubmission, DocPage, Document, ExplainSession, Job, Note, Topic
from app.db.session import get_session_factory
from app.features.answers import generation, plan
from app.features.answers import setup as answers_setup
from app.features.answers.schemas import EvalOut, ExplainOut, QuestionOut
from app.jobs.runner import run_pending
from app.rag.index import index_document
from tests.helpers import FakeServices, FullFakeGateway

LONG = "The Preamble declares India a sovereign socialist secular democratic republic and lists justice liberty equality."


def png(color=(200, 30, 30)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (40, 30), color).save(buf, "PNG")
    return buf.getvalue()


def _run(factory, svc, jtype, payload):
    with factory() as db:
        job = Job(type=jtype, payload_json=payload)
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        return db.get(Job, jid)


# ------------------------------------------------------------------ explain_feedback

def test_explain_feedback_uses_note_key_points(env):
    settings, factory = env
    out = ExplainOut(covered=["Sovereign"], missed=["Secular", "Republic"],
                     needs_correcting=[{"said": "42nd added federal", "correct": "42nd added socialist, secular"}],
                     model_explanation="The Preamble ...")
    gw = FullFakeGateway([out])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic = Topic(title="Preamble")
        db.add(topic)
        db.flush()
        db.add(Note(topic_id=topic.id, sections={"key_points": ["Sovereign", {"text": "Secular"}, 7], "must_remember": ["Republic"]}))
        s = ExplainSession(topic_id=topic.id, transcript=LONG, status="queued")
        db.add(s)
        db.commit()
        sid = s.id
    job = _run(factory, svc, "explain_feedback", {"session_id": sid})
    assert job.status == "done" and job.result_json == {"session_id": sid}
    with factory() as db:
        s = db.get(ExplainSession, sid)
        assert s.status == "done"
        fb = s.feedback_json
        assert fb["coverage"] == {"covered": 1, "total": 3} and fb["missed"] == ["Secular", "Republic"]
        assert fb["needs_correcting"][0]["said"].startswith("42nd") and fb["model_explanation"]
        assert fb["from_your_material"] is True
    prompt = gw.calls[0][1]
    assert "1. Sovereign" in prompt and "2. Secular" in prompt and "3. Republic" in prompt
    assert svc.extras["pushed"] and "feedback" in svc.extras["pushed"][0][1]


def test_explain_feedback_falls_back_to_documents_then_general(env):
    settings, factory = env
    gw = FullFakeGateway([ExplainOut(covered=["a"], missed=["b"]), ExplainOut(covered=[], missed=["c"], general_knowledge=True)])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        doc = Document(title="Polity book")
        topic = Topic(title="Preamble")
        db.add_all([doc, topic])
        db.flush()
        db.add(DocPage(document_id=doc.id, page=1, text="The Preamble opens the Constitution with We the people."))
        db.commit()
        index_document(db, gw, doc.id, topic_ids=[topic.id])
        s1 = ExplainSession(topic_id=topic.id, transcript=LONG)
        s2 = ExplainSession(topic_id=None, transcript=LONG)
        db.add_all([s1, s2])
        db.commit()
        ids = (s1.id, s2.id)
    _run(factory, svc, "explain_feedback", {"session_id": ids[0]})
    _run(factory, svc, "explain_feedback", {"session_id": ids[1]})
    assert "We the people" in gw.calls[0][1]
    assert "common knowledge" in gw.calls[1][1]
    with factory() as db:
        assert db.get(ExplainSession, ids[0]).feedback_json["from_your_material"] is True
        assert db.get(ExplainSession, ids[1]).feedback_json["from_your_material"] is False


def test_explain_feedback_errors(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    with factory() as db:
        short = ExplainSession(transcript="too short")
        ok = ExplainSession(transcript=LONG, status="queued")
        db.add_all([short, ok])
        db.commit()
        short_id, ok_id = short.id, ok.id
    assert _run(factory, svc, "explain_feedback", {"session_id": "nope"}).status == "failed"
    job = _run(factory, svc, "explain_feedback", {"session_id": short_id})
    assert job.status == "failed" and "too short" in job.error
    with factory() as db:
        assert db.get(ExplainSession, short_id).status == "failed"
    job = _run(factory, svc, "explain_feedback", {"session_id": ok_id})  # AI gives nothing: stays queued
    assert job.status == "queued"
    with factory() as db:
        assert db.get(ExplainSession, ok_id).status == "queued"


# ------------------------------------------------------------------ upload + answer_eval

def _make_answer(**kw):
    with get_session_factory()() as db:
        row = AnswerSubmission(question="Discuss the role of the Preamble.", word_limit=150, **kw)
        db.add(row)
        db.commit()
        return row.id


def test_upload_and_evaluate_handwritten_answer(client, auth_header):
    aid = _make_answer(status="draft")
    files = [("files", ("a.png", png(), "image/png")), ("files", ("b.png", png((0, 200, 0)), "image/png"))]
    r = client.post(f"/answers/{aid}/images", headers=auth_header, files=files)
    assert r.status_code == 200 and r.json()["count"] == 2
    again = client.post(f"/answers/{aid}/images", headers=auth_header, files=files[:1])  # a retry adds nothing new
    assert again.json()["count"] == 2
    replaced = client.post(f"/answers/{aid}/images?replace=true", headers=auth_header, files=files[1:])
    assert replaced.json()["count"] == 1
    client.post(f"/answers/{aid}/images", headers=auth_header, files=files[:1])

    result = EvalOut(transcript=" ".join(["word"] * 120), score=7.46,
                     structure={"intro": "Good", "body": "Ok", "conclusion": "Missing"},
                     content_coverage="Covers most", examples_data="No data", presentation="Neat",
                     word_limit_comment="Slightly short", strengths=["Clear"], improvements=["Add data"],
                     model_outline=["Intro", "Body", "Conclusion"])
    gw = FullFakeGateway([result])
    svc = FakeServices.build(get_settings(), get_session_factory(), gw)
    job = _run(get_session_factory(), svc, "answer_eval", {"answer_id": aid})
    assert job.status == "done" and job.result_json == {"answer_id": aid, "score": 7.5}
    assert gw.image_calls == [("answer_eval", 2)]
    with get_session_factory()() as db:
        row = db.get(AnswerSubmission, aid)
        assert row.status == "done" and row.score == 7.5
        fb = row.feedback_json
        assert fb["word_limit"] == {"words": 120, "limit": 150, "comment": "Slightly short"}
        assert fb["structure"]["conclusion"] == "Missing" and fb["model_outline"] == ["Intro", "Body", "Conclusion"]
        assert "score" not in fb and fb["examples_data"] == "No data"


def test_unreadable_answer_is_scored_low(client, auth_header):
    aid = _make_answer()
    client.post(f"/answers/{aid}/images", headers=auth_header, files=[("files", ("a.png", png(), "image/png"))])
    gw = FullFakeGateway([EvalOut(readable=False, score=9)])
    svc = FakeServices.build(get_settings(), get_session_factory(), gw)
    job = _run(get_session_factory(), svc, "answer_eval", {"answer_id": aid})
    assert job.result_json["score"] == 2.0


def test_answer_eval_errors(client, auth_header):
    aid = _make_answer(status="queued")
    svc = FakeServices.build(get_settings(), get_session_factory(), FullFakeGateway([]))
    assert _run(get_session_factory(), svc, "answer_eval", {"answer_id": "missing"}).status == "failed"
    job = _run(get_session_factory(), svc, "answer_eval", {"answer_id": aid})  # no photos
    assert job.status == "failed" and "photos" in job.error
    with get_session_factory()() as db:
        assert db.get(AnswerSubmission, aid).status == "failed"
    aid2 = _make_answer(status="queued")
    client.post(f"/answers/{aid2}/images", headers=auth_header, files=[("files", ("a.png", png(), "image/png"))])
    job = _run(get_session_factory(), svc, "answer_eval", {"answer_id": aid2})  # AI unavailable: retried later
    assert job.status == "queued"


def test_upload_validation(client, auth_header):
    assert client.post("/answers/nope/images", headers=auth_header, files=[("files", ("a.png", png(), "image/png"))]).status_code == 404
    aid = _make_answer()
    bad = client.post(f"/answers/{aid}/images", headers=auth_header, files=[("files", ("a.png", b"not a picture", "image/png"))])
    assert bad.status_code == 400
    assert client.post(f"/answers/{aid}/images").status_code == 401


# ------------------------------------------------------------------ question generation

def test_generate_endpoint(client, auth_header):
    with get_session_factory()() as db:
        topic = Topic(title="Federalism")
        db.add(topic)
        db.commit()
        tid = topic.id
    client.app.state.services.gateway = FullFakeGateway([QuestionOut(question="Critically analyse Indian federalism.", word_limit=250)])
    r = client.post("/answers/generate", headers=auth_header, json={"topic_id": tid, "kind": "mains"})
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "draft" and body["topic_id"] == tid and body["word_limit"] == 250 and body["kind"] == "mains"
    client.app.state.services.gateway = FullFakeGateway([])
    assert client.post("/answers/generate", headers=auth_header, json={}).status_code == 503


def test_essay_and_unknown_kinds(env):
    settings, factory = env
    gw = FullFakeGateway([QuestionOut(question="Technology is a double-edged sword.", word_limit=300),
                          QuestionOut(question="Discuss the Finance Commission.", word_limit=99999)])
    with factory() as db:
        essay = generation.create_draft(db, gw, None, "essay")
        other = generation.create_draft(db, gw, None, "weird")
        assert essay.kind == "essay" and essay.word_limit == 1000
        assert other.kind == "mains" and other.word_limit == 250  # bad limit falls back


def test_weekly_drafts_only_for_studied_topics_and_capped(env):
    settings, factory = env
    gw = FullFakeGateway([QuestionOut(question=f"Question number {i} about topic?") for i in range(10)])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        for i in range(5):
            db.add(Topic(title=f"Studied {i}", status="in_progress", level=2))
        db.add(Topic(title="Untouched", status="not_started", level=2))
        db.commit()
    assert generation.weekly_drafts(svc) == 3
    assert generation.weekly_drafts(svc) == 2  # at most 5 open drafts
    assert generation.weekly_drafts(svc) == 0
    with factory() as db:
        assert generation.open_drafts(db) == 5
        assert all(a.topic_id for a in db.scalars(select(AnswerSubmission)))


def test_weekly_drafts_stops_when_ai_busy_or_budget_short(env):
    settings, factory = env
    with factory() as db:
        db.add(Topic(title="Studied", status="studied", level=2))
        db.commit()
    assert generation.weekly_drafts(FakeServices.build(settings, factory, FullFakeGateway([]))) == 0
    assert generation.weekly_drafts(FakeServices.build(settings, factory, FullFakeGateway([], level=3))) == 0


def test_setup_registers_weekly_job_only_when_scheduler_enabled(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory)
    answers_setup(svc)
    assert svc.scheduler.get_job("answers:weekly_drafts") is None
    svc2 = FakeServices.build(settings.model_copy(update={"scheduler_enabled": True}), factory)
    answers_setup(svc2)
    assert svc2.scheduler.get_job("answers:weekly_drafts") is not None


# ------------------------------------------------------------------ plan block

def test_plan_block_only_with_a_draft(env):
    settings, factory = env
    today = datetime.now(ZoneInfo(settings.timezone)).strftime("%Y-%m-%d")
    ctx = {"settings": settings}
    base = [{"id": "b1", "kind": "study", "start": "18:00", "minutes": 90, "title": "Study", "detail": "", "topic_id": None, "ref": None}]
    with factory() as db:
        assert plan.add_answer_block(db, today, [dict(base[0])], ctx) == base
        db.add(AnswerSubmission(question="Discuss " + "the federal structure " * 8, status="draft", topic_id="t1"))
        db.commit()
        blocks = plan.add_answer_block(db, today, [dict(base[0])], ctx)
        assert len(blocks) == 2
        b = blocks[1]
        assert b["kind"] == "answer" and b["ref"] == "answers" and b["minutes"] == 30 and b["topic_id"] == "t1"
        assert b["start"] == "19:30" and b["detail"].endswith("...") and b["id"] == f"answer-{today}"
        assert len(plan.add_answer_block(db, today, blocks, ctx)) == 2  # not added twice
        assert plan.add_answer_block(db, "2020-01-01", [dict(base[0])], ctx) == base  # past days untouched
        late = [dict(base[0], start="21:30", minutes=60)]
        assert plan.add_answer_block(db, today, late, ctx)[1]["start"] == "21:00"
        assert plan.add_answer_block(db, today, [], ctx)[0]["start"] == "17:00"


def test_typed_answer_is_evaluated_without_photos(client):
    aid = _make_answer(status="queued")
    typed = "The Preamble states the ideals of the Constitution: justice, liberty, equality and fraternity."
    gw = FullFakeGateway([EvalOut(transcript=typed, score=6.0, content_coverage="Ok")])
    svc = FakeServices.build(get_settings(), get_session_factory(), gw)
    job = _run(get_session_factory(), svc, "answer_eval", {"answer_id": aid, "text": typed})
    assert job.status == "done" and job.result_json["score"] == 6.0
    assert gw.image_calls == []
    assert "<answer>" in gw.calls[0][1] and "justice, liberty" in gw.calls[0][1]
    short = _make_answer(status="queued")
    assert _run(get_session_factory(), svc, "answer_eval", {"answer_id": short, "text": "too short"}).status == "failed"
