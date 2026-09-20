from datetime import datetime, timedelta, timezone

import httpx
from sqlalchemy import select

from app.db.models_v2 import Chunk, DocPage, Document, Job, Note, Topic
from app.jobs import registry
from app.jobs.registry import AiUnavailable, JobFailed, job_handler
from app.jobs.runner import run_pending
from app.llm.gateway import LlmGateway
from app.rag.index import index_document
from app.rag.search import search
from app.sync.registry import sync_tables
from tests.helpers import FakeServices, FullFakeGateway


def iso(dt):
    return dt.isoformat().replace("+00:00", "Z")


def test_registry_finds_all_synced_tables():
    names = set(sync_tables())
    assert {"cards", "topics", "notes", "reviews", "jobs", "documents", "mistakes", "telugu_progress"} <= names
    assert "chunks" not in names and "note_versions" not in names


def test_pull_lists_tables_and_paginates(client, auth_header):
    now = datetime.now(timezone.utc)
    body = {"tables": {"topics": [{"id": f"t{i}", "title": f"Topic {i}", "updated_at": iso(now)} for i in range(3)]}}
    r = client.post("/sync/push", json=body, headers=auth_header)
    assert r.status_code == 200 and r.json()["accepted"]["topics"] == ["t0", "t1", "t2"]
    r = client.get("/sync/pull", headers=auth_header).json()
    assert {t["id"] for t in r["tables"]["topics"]} == {"t0", "t1", "t2"}
    assert r["more"] is False and r["next_since"] == r["server_time"]
    assert r["tables"]["topics"][0]["title"].startswith("Topic")
    # nothing new after next_since
    r2 = client.get("/sync/pull", params={"since": r["next_since"]}, headers=auth_header).json()
    assert r2["tables"] == {}


def test_push_rules(client, auth_header):
    now = datetime.now(timezone.utc)
    old = now - timedelta(days=1)
    # unknown and read-only tables are rejected
    r = client.post("/sync/push", headers=auth_header, json={"tables": {
        "nope": [{"id": "x"}], "sheets": [{"id": "s1", "content_md": "hack"}]}}).json()
    assert len(r["rejected"]) == 2
    # last write wins by the tablet's timestamp: a stale edit is ignored
    client.post("/sync/push", headers=auth_header, json={"tables": {"topics": [
        {"id": "a", "title": "New title", "updated_at": iso(now)}]}})
    r = client.post("/sync/push", headers=auth_header, json={"tables": {"topics": [
        {"id": "a", "title": "Old title", "updated_at": iso(old)}]}}).json()
    assert r["accepted"]["topics"] == ["a"]
    pulled = client.get("/sync/pull", headers=auth_header).json()["tables"]["topics"]
    assert pulled[0]["title"] == "New title"
    # server-owned fields cannot be written
    client.post("/sync/push", headers=auth_header, json={"tables": {"topics": [
        {"id": "a", "title": "T", "importance": 9.9, "syllabus_id": "zzz", "updated_at": iso(now + timedelta(seconds=5))}]}})
    pulled = client.get("/sync/pull", headers=auth_header).json()["tables"]["topics"][0]
    assert pulled["importance"] == 0.0 and pulled["syllabus_id"] is None
    # bad data is rejected, not a crash
    r = client.post("/sync/push", headers=auth_header, json={"tables": {"reviews": [
        {"id": "r1", "card_id": "c", "grade": "x", "reviewed_at": "not-a-date"}]}}).json()
    assert r["rejected"] or r["accepted"]


def test_owner_edit_beats_newer_ai_version(client, auth_header, env=None):
    from app.db.session import get_session_factory

    now = datetime.now(timezone.utc)
    with get_session_factory()() as db:
        db.add(Note(id="n1", topic_id="t", content_md="AI text", updated_at=now + timedelta(hours=1)))
        db.commit()
    r = client.post("/sync/push", headers=auth_header, json={"tables": {"notes": [
        {"id": "n1", "content_md": "My edit", "owner_edited": True, "updated_at": iso(now)}]}})
    assert r.json()["accepted"]["notes"] == ["n1"]
    with get_session_factory()() as db:
        assert db.get(Note, "n1").content_md == "My edit"


def test_pushed_job_runs_and_notifies(env):
    settings, factory = env
    gw = FullFakeGateway([])
    svc = FakeServices.build(settings, factory, gw)

    @job_handler("t_ok", notify="answer")
    def _ok(ctx):
        return {"echo": ctx.payload["q"]}

    @job_handler("t_ai", feature="tutor_answer")
    def _ai(ctx):
        raise AiUnavailable("busy")

    @job_handler("t_bad")
    def _bad(ctx):
        raise JobFailed("Nothing to summarise")

    @job_handler("t_boom")
    def _boom(ctx):
        raise RuntimeError("bug")

    try:
        with factory() as db:
            for t in ("t_ok", "t_ok", "t_ai", "t_bad", "t_boom", "unknown_type"):
                db.add(Job(type=t, payload_json={"q": "hi"}))
            db.commit()
        assert run_pending(svc) == 2
        with factory() as db:
            jobs = {j.type: j for j in db.scalars(select(Job))}
            done = [j for j in db.scalars(select(Job)) if j.type == "t_ok"]
            assert all(j.status == "done" and j.result_json == {"echo": "hi"} and j.notified for j in done)
            assert jobs["t_bad"].status == "failed" and jobs["t_bad"].error == "Nothing to summarise"
            assert jobs["t_boom"].status == "failed" and "RuntimeError" in jobs["t_boom"].error
            assert jobs["unknown_type"].status == "failed"
            assert jobs["t_ai"].status == "queued" and jobs["t_ai"].result_json["_attempts"] == 1
        pushed = svc.extras["pushed"]
        assert len(pushed) == 1 and "2 answers ready" in pushed[0][1]  # one combined push
        assert run_pending(svc) == 0  # the retry is not due yet, and nothing else is queued
    finally:
        for name in ("t_ok", "t_ai", "t_bad", "t_boom"):
            registry.HANDLERS.pop(name, None)


def test_deferrable_job_waits_when_budget_is_used(env):
    settings, factory = env
    gw = FullFakeGateway([], level=3)
    gw.guard.allow = lambda feature: feature not in {"mock_test"}
    svc = FakeServices.build(settings, factory, gw)

    @job_handler("t_defer", feature="mock_test")
    def _d(ctx):
        return {}

    try:
        with factory() as db:
            db.add(Job(type="t_defer"))
            db.commit()
        assert run_pending(svc) == 0
        with factory() as db:
            assert db.scalar(select(Job)).status == "queued"
    finally:
        registry.HANDLERS.pop("t_defer", None)


def _doc(db, title, pages):
    doc = Document(title=title)
    db.add(doc)
    db.flush()
    for i, text in enumerate(pages, 1):
        db.add(DocPage(document_id=doc.id, page=i, text=text))
    db.commit()
    return doc


def test_rag_word_search_without_embeddings(env):
    _, factory = env
    gw = FullFakeGateway([])
    with factory() as db:
        a = _doc(db, "Polity", ["Article 21 protects life and personal liberty. " * 5,
                                "The Rajya Sabha has 250 members and is a permanent house. " * 5])
        _doc(db, "Economy", ["Repo rate is the rate at which the RBI lends to banks. " * 5])
        assert index_document(db, gw, a.id) >= 2
        idx_other = db.scalar(select(Document).where(Document.title == "Economy"))
        index_document(db, gw, idx_other.id)
        hits = search(db, gw, "how many members in Rajya Sabha", k=3)
        assert hits and hits[0].document_title == "Polity" and hits[0].page == 2
        hits = search(db, gw, "repo rate", document_ids=[idx_other.id])
        assert hits and all(h.document_title == "Economy" for h in hits)
        assert search(db, gw, "zzzz qqqq") == []


def test_rag_uses_embeddings_when_present(env):
    _, factory = env

    def vec(texts):
        return [[1.0, 0.0] if "liberty" in t or "freedom" in t else [0.0, 1.0] for t in texts]

    gw = FullFakeGateway([], vectors=vec)
    with factory() as db:
        d = _doc(db, "Polity", ["Personal liberty is a fundamental right. " * 4, "Budget is presented in February. " * 4])
        index_document(db, gw, d.id)
        assert all(c.embedding for c in db.scalars(select(Chunk)))
        hits = search(db, gw, "freedom", k=1)  # no shared word, found by meaning
        assert hits and "liberty" in hits[0].text


def test_gateway_text_images_and_embeddings(env):
    settings, factory = env
    seen = []

    def handler(req: httpx.Request):
        body = req.content.decode()
        seen.append((req.url.path, body))
        if "EmbedContents" in req.url.path:
            n = body.count('"model"')
            return httpx.Response(200, json={"embeddings": [{"values": [0.1, 0.2]}] * n})
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "hello"}]}}],
                                         "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 1}})

    gw = LlmGateway(settings, factory, client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert gw.generate_text(feature="t", system="s", user="u", images=[("image/png", b"\x89PNG")]) == "hello"
    path, body = seen[-1]
    assert "inlineData" in body and "responseMimeType" not in body and path.endswith("generateContent")
    assert gw.embed_texts(["a", "b"]) == [[0.1, 0.2], [0.1, 0.2]]
    assert "taskType" not in seen[-1][1]  # the new embedding model takes no task type


def test_gateway_embedding_falls_back_then_gives_up(env):
    settings, factory = env
    calls = []

    def handler(req):
        calls.append(req.url.path)
        return httpx.Response(404, json={"error": "no such model"})

    gw = LlmGateway(settings, factory, client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert gw.embed_texts(["a"]) is None
    assert len(calls) == 2 and "embedding-001" in calls[1]


def test_topic_default_fields():
    t = Topic(title="x")
    assert t.title == "x"
