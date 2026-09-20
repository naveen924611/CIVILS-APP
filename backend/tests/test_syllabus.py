import json
from datetime import datetime, timezone

from sqlalchemy import select

from app.api.kv import get_kv
from app.db.models import NewsItem
from app.db.models_v2 import DocPage, Document, Job, Note, Pyq, SyllabusImport, Topic
from app.features.syllabus import importance, service
from app.features.syllabus.schemas import ChunkOut
from app.features.syllabus.trees import (
    add_coverage,
    chunk_text,
    clean_tree,
    count_leaves,
    filter_tree,
    merge_trees,
    nest_topics,
    split_papers,
)
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway

TEXT = """Paper II
Indian Polity and Governance
Constitution: features, amendments
Federalism

Paper III
Indian Economy
Planning and growth
"""


def _out(*nodes):
    return ChunkOut.model_validate({"nodes": list(nodes)})


def _node(title, *children, tags=(), hours=None):
    return {"title": title, "exam_tags": list(tags), "est_hours": hours, "children": list(children)}


# ---------------------------------------------------------------- pure tree helpers

def test_clean_tree_sets_levels_tags_and_merges_twins():
    raw = [
        {"title": "  GS  Paper 1 ", "exam_tags": ["upsc", "x"], "children": [
            {"title": "History", "children": [{"title": "Modern"}, {"title": "modern!"}]},
            {"title": ""},
            "junk",
        ]},
        {"title": "GS Paper 1", "children": [{"title": "Geography"}]},
    ]
    tree = clean_tree(raw)
    assert len(tree) == 1
    top = tree[0]
    assert top["title"] == "GS Paper 1" and top["level"] == 0 and top["exam_tags"] == ["UPSC"]
    assert [c["title"] for c in top["children"]] == ["History", "Geography"]
    hist = top["children"][0]
    assert hist["level"] == 1 and hist["exam_tags"] == ["UPSC"]  # inherited from the paper
    assert [c["title"] for c in hist["children"]] == ["Modern"] and hist["children"][0]["level"] == 2


def test_clean_tree_caps_level_and_bounds_numbers():
    deep = {"title": "a", "est_hours": 9999, "importance": -4, "children": [{"title": "b", "children": [
        {"title": "c", "children": [{"title": "d", "children": [{"title": "e"}]}]}]}]}
    tree = clean_tree([deep])
    assert tree[0]["est_hours"] == 500.0 and tree[0]["importance"] == 0.0
    e = tree[0]["children"][0]["children"][0]["children"][0]["children"][0]
    assert e["level"] == 3
    assert clean_tree("nonsense") == []


def test_merge_trees_and_counts():
    a = clean_tree([_node("P", _node("A", _node("x")))])
    b = clean_tree([_node("p", _node("A", _node("y")), _node("B"))])
    merged = merge_trees(a, b)
    assert [c["title"] for c in merged[0]["children"]] == ["A", "B"]
    assert [c["title"] for c in merged[0]["children"][0]["children"]] == ["x", "y"]
    assert count_leaves(merged) == 3


def test_filter_tree_keeps_ancestors_and_inherits_tags():
    tree = clean_tree([
        _node("UPSC only", _node("u"), tags=["UPSC"]),
        _node("Both", _node("shared", tags=["UPSC", "APPSC"]), _node("upsc leaf", tags=["UPSC"]), tags=["UPSC"]),
    ])
    appsc = filter_tree(tree, "APPSC")
    assert [n["title"] for n in appsc] == ["Both"]
    assert [c["title"] for c in appsc[0]["children"]] == ["shared"]
    assert filter_tree(tree, None) is tree


def test_split_and_chunk_text():
    parts = split_papers(TEXT)
    assert len(parts) == 2 and parts[0].startswith("Paper II") and parts[1].startswith("Paper III")
    assert len(chunk_text(TEXT)) == 2
    long_text = "Paper I\n" + "\n\n".join(f"Line {i} " + "word " * 40 for i in range(60))
    chunks = chunk_text(long_text, max_chars=1500)
    assert len(chunks) > 1 and all(len(c) <= 1500 for c in chunks)
    assert chunk_text("x" * 5000, max_chars=2000)[0] == "x" * 2000


def test_nest_topics_and_coverage():
    rows = [
        {"id": "p", "parent_id": None, "title": "Paper", "position": 0, "status": "not_started"},
        {"id": "a", "parent_id": "p", "title": "A", "position": 1, "status": "studied"},
        {"id": "b", "parent_id": "p", "title": "B", "position": 0, "status": "revised"},
        {"id": "c", "parent_id": "p", "title": "C", "position": 2, "status": "in_progress"},
        {"id": "o", "parent_id": "gone", "title": "Orphan", "position": 0, "status": "strong"},
    ]
    tree = nest_topics(rows)
    assert [n["id"] for n in tree] == ["o", "p"]
    assert [c["id"] for c in tree[1]["children"]] == ["b", "a", "c"]
    covered, leaves = add_coverage(tree)
    assert (covered, leaves) == (3, 4)
    assert tree[1]["coverage"] == 67 and tree[0]["coverage"] == 100


# ---------------------------------------------------------------- starters

def _starter(tmp_path, monkeypatch, key="demo", tree=None, verified=False):
    folder = tmp_path / "syl"
    folder.mkdir(exist_ok=True)
    doc = {"key": key, "exam": "UPSC CSE", "title": "Demo outline", "verified": verified,
           "tree": tree or [_node("GS Paper 1", _node("Polity", _node("Preamble")))]}
    (folder / f"{key}.json").write_text(json.dumps(doc), encoding="utf-8")
    (folder / "broken.json").write_text("{nope", encoding="utf-8")
    monkeypatch.setenv("SYLLABUS_DIR", str(folder))


def test_seed_starters_is_idempotent_and_never_undoes_owner_choices(env, tmp_path, monkeypatch):
    settings, factory = env
    _starter(tmp_path, monkeypatch)
    with factory() as db:
        assert service.seed_starters(db, settings) == 1
        assert service.seed_starters(db, settings) == 0
        row = db.get(SyllabusImport, service.seed_id("demo"))
        assert row.status == "pending" and "not checked" in row.note
        row.deleted = True  # the owner threw it away: it must not come back
        db.commit()
        assert service.seed_starters(db, settings) == 0
        assert db.scalar(select(Topic)) is None  # nothing is used before approval


def test_shipped_starter_files_are_valid():
    from pathlib import Path

    folder = Path(__file__).resolve().parents[2] / "data" / "syllabus"
    files = sorted(folder.glob("*.json"))
    assert len(files) >= 4
    for path in files:
        doc = json.loads(path.read_text(encoding="utf-8"))
        assert doc["key"] and doc["exam"] and doc["title"] and clean_tree(doc["tree"])
        assert doc.get("verified") in (True, False)


# ---------------------------------------------------------------- the import job

def _queue(factory, **payload):
    with factory() as db:
        job = Job(type="syllabus_import", payload_json=payload)
        db.add(job)
        db.commit()
        return job.id


def test_import_job_builds_a_pending_tree_from_text(env):
    settings, factory = env
    gw = FullFakeGateway([
        _out(_node("Paper II", _node("Indian Polity", _node("Federalism"), tags=["UPSC"]))),
        _out(_node("Paper III", _node("Indian Economy", _node("Planning and growth", hours=2)))),
    ])
    svc = FakeServices.build(settings, factory, gw)
    job_id = _queue(factory, exam="UPSC CSE", title="GS Mains", text=TEXT)
    run_pending(svc)
    with factory() as db:
        job = db.get(Job, job_id)
        assert job.status == "done"
        imp = db.get(SyllabusImport, job.result_json["import_id"])
        assert imp.status == "pending" and imp.exam == "UPSC CSE"
        assert [n["title"] for n in imp.tree_json] == ["Paper II", "Paper III"]
        assert imp.tree_json[1]["children"][0]["children"][0]["est_hours"] == 2.0
        assert "items found" in imp.note
    assert len(gw.calls) == 2 and "Federalism" in gw.calls[0][1]  # one call per paper


def test_import_job_reads_a_library_document(env):
    settings, factory = env
    gw = FullFakeGateway([_out(_node("Paper II", _node("Polity")))])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        doc = Document(title="APPSC notification")
        db.add(doc)
        db.flush()
        db.add(DocPage(document_id=doc.id, page=1, text="Paper II\nPolity"))
        db.add(DocPage(document_id=doc.id, page=2, text="Federalism"))
        db.commit()
        doc_id = doc.id
    _queue(factory, exam="APPSC Group-I", title="From PDF", document_id=doc_id)
    run_pending(svc)
    assert "Federalism" in gw.calls[0][1] and "Polity" in gw.calls[0][1]


def test_import_job_needs_text_and_a_real_document(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    a = _queue(factory, exam="UPSC", title="Empty")
    b = _queue(factory, exam="UPSC", title="Gone", document_id="nope")
    c = _queue(factory, title="No exam", text="x")
    run_pending(svc)
    with factory() as db:
        assert {db.get(Job, j).status for j in (a, b, c)} == {"failed"}
        assert "no text" in db.get(Job, a).error.lower() or "no syllabus text" in db.get(Job, a).error.lower()
        assert "not found" in db.get(Job, b).error
        rows = list(db.scalars(select(SyllabusImport).where(SyllabusImport.title.in_(["Empty", "Gone"]))))
        assert {r.status for r in rows} == {"failed"}


def test_import_job_retries_when_ai_is_busy_then_fails_kindly(env):
    settings, factory = env
    gw = FullFakeGateway([])
    svc = FakeServices.build(settings, factory, gw)
    job_id = _queue(factory, exam="UPSC", title="Busy", text=TEXT)
    run_pending(svc)
    with factory() as db:
        job = db.get(Job, job_id)
        assert job.status == "queued" and job.result_json["_attempts"] == 1
        imp = db.scalar(select(SyllabusImport))
        assert imp.status == "processing" and "again" in imp.note
        job.result_json = {"_attempts": settings.job_max_attempts - 1}
        db.commit()
    run_pending(svc)
    with factory() as db:
        assert db.get(Job, job_id).status == "failed"
        assert db.scalar(select(SyllabusImport)).status == "failed"


def test_import_job_bad_answer_fails_at_once(env):
    settings, factory = env
    gw = FullFakeGateway([])
    gw.last_error = "parse: not json"
    svc = FakeServices.build(settings, factory, gw)
    job_id = _queue(factory, exam="UPSC", title="Garbled", text=TEXT)
    run_pending(svc)
    with factory() as db:
        assert db.get(Job, job_id).status == "failed"
        assert "one paper at a time" in db.get(Job, job_id).error


def test_import_job_with_nothing_found_fails(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([_out()]))
    job_id = _queue(factory, exam="UPSC", title="Blank", text="just some words")
    run_pending(svc)
    with factory() as db:
        assert db.get(Job, job_id).status == "failed"


# ---------------------------------------------------------------- approval

def _pending(db, tree, exam="UPSC CSE", title="Test"):
    imp = SyllabusImport(exam=exam, title=title, status="pending", tree_json=clean_tree(tree))
    db.add(imp)
    db.commit()
    return imp


def test_approve_creates_topics_notes_and_priors(env):
    settings, factory = env
    tree = [_node("GS Paper 2", _node("Polity", _node("Preamble", hours=2), _node("Federalism")))]
    tree[0]["importance"] = 9
    with factory() as db:
        imp = _pending(db, tree)
        result = service.approve(db, imp)
        assert result["created"] == 4 and result["merged"] == 0 and imp.status == "approved"
        topics = list(db.scalars(select(Topic).order_by(Topic.level)))
        assert [t.level for t in topics] == [0, 1, 2, 2]
        assert all(t.approved and t.exam_tags == ["UPSC"] for t in topics)
        assert {t.paper for t in topics} == {"GS Paper 2"}
        pre = next(t for t in topics if t.title == "Preamble")
        assert pre.est_hours == 2.0 and pre.parent_id == next(t for t in topics if t.title == "Polity").id
        notes = list(db.scalars(select(Note)))
        assert len(notes) == 3 and all(n.status == "no_material" for n in notes)  # subjects and topics, not the paper itself
        assert get_kv(db, importance.PRIOR_KEY)  # the first guess is remembered
        assert all(t.importance > 0 for t in topics)
        # approving again changes nothing
        again = service.approve(db, imp)
        assert again["created"] == 0
        assert len(list(db.scalars(select(Topic)))) == 4 and len(list(db.scalars(select(Note)))) == 3


def test_approve_merges_a_second_exam_into_shared_topics(env):
    settings, factory = env
    with factory() as db:
        service.approve(db, _pending(db, [_node("Polity", _node("Federalism"))], exam="UPSC CSE", title="U"))
        second = _pending(db, [_node("Polity", _node("Federalism"), _node("State legislature"))], exam="APPSC Group-I", title="A")
        result = service.approve(db, second)
        assert result["merged"] == 2 and result["created"] == 1
        fed = db.scalar(select(Topic).where(Topic.title == "Federalism"))
        assert sorted(fed.exam_tags) == ["APPSC", "UPSC"]  # shared topics count for both exams
        assert len(list(db.scalars(select(Topic).where(Topic.title == "Polity")))) == 1


def test_approve_without_merge_keeps_separate_topics(env):
    settings, factory = env
    with factory() as db:
        service.approve(db, _pending(db, [_node("Polity")], exam="UPSC", title="U"))
        service.approve(db, _pending(db, [_node("Polity")], exam="APPSC", title="A"), merge=False)
        assert len(list(db.scalars(select(Topic).where(Topic.title == "Polity")))) == 2


def test_approve_with_exam_filter_and_owner_edits(env):
    settings, factory = env
    tree = [_node("Paper", _node("Both", tags=["UPSC", "APPSC"]), _node("Only UPSC", tags=["UPSC"]), tags=["UPSC", "APPSC"])]
    with factory() as db:
        imp = _pending(db, tree, exam="Both exams")
        edited = [{"title": "Paper", "children": [
            {"title": "Both", "exam_tags": ["APPSC"]}, {"title": "Only UPSC", "exam_tags": ["UPSC"]}, {"title": "Added by owner"}]}]
        result = service.approve(db, imp, exam_filter="APPSC", tree=edited)
        assert result["created"] == 3
        # untagged nodes inherit the paper's tags, so the owner's own addition is kept; the UPSC-only topic is left out
        assert {t.title for t in db.scalars(select(Topic))} == {"Paper", "Both", "Added by owner"}
        assert [n["title"] for n in imp.tree_json[0]["children"]] == ["Both", "Only UPSC", "Added by owner"]  # the edit is kept


# ---------------------------------------------------------------- importance

def test_score_formula():
    assert importance.score(None, 8.0, ["UPSC"], None, 0) == 8.0
    assert importance.score(None, 8.0, ["UPSC", "APPSC"], None, 0) == 8.5  # shared by both exams
    assert importance.score(6.0, 8.0, [], None, 0) == 6.8
    assert importance.score(6.0, 8.0, [], 10.0, 0) > importance.score(6.0, 8.0, [], 0.0, 0)  # past papers matter most
    assert importance.score(None, 5.0, [], None, 40) == 6.0  # the news boost is capped at +1
    assert importance.score(10.0, 10.0, ["UPSC", "APPSC"], 10.0, 9) == 10.0
    assert importance.paper_weight("Telugu (qualifying)") == 2.0 and importance.paper_weight("Something new") == 5.0


def test_recompute_uses_recent_pyqs_and_news(env):
    settings, factory = env
    tree = [_node("GS Paper 2", _node("Polity", _node("Preamble"), _node("Federalism")))]
    now = datetime(2026, 9, 20, tzinfo=timezone.utc)
    with factory() as db:
        service.approve(db, _pending(db, tree))
        ids = {t.title: t.id for t in db.scalars(select(Topic))}
        db.add_all([
            Pyq(exam="UPSC", year=2025, question="q1", topic_ids=[ids["Federalism"]]),
            Pyq(exam="UPSC", year=2024, question="q2", topic_ids=[ids["Federalism"]]),
            Pyq(exam="UPSC", year=2010, question="q3", topic_ids=[ids["Preamble"]]),
        ])
        db.add(NewsItem(url="https://x/1", title="Federalism row", topic_ids=[ids["Federalism"]], published_at=now))
        db.add(NewsItem(url="https://x/2", title="Old", topic_ids=[ids["Preamble"]], published_at=datetime(2026, 1, 1, tzinfo=timezone.utc)))
        db.commit()
        stats = importance.recompute(db, today=now)
        assert stats["topics"] == 4 and stats["with_pyq"] >= 2
        by = {t.title: t.importance for t in db.scalars(select(Topic))}
        assert by["Federalism"] > by["Preamble"]
        assert by["Polity"] >= by["Preamble"]  # a subject counts the past papers of its topics
        again = importance.recompute(db, today=now)
        assert again["changed"] == 0  # repeatable: no drift


def test_recompute_with_no_topics(env):
    settings, factory = env
    with factory() as db:
        assert importance.recompute(db) == {"topics": 0, "with_pyq": 0, "changed": 0}


# ---------------------------------------------------------------- routes

def _no_kick(client):
    client.app.state.services.kick_jobs = lambda: None


def test_routes_import_edit_approve_and_tree(client, auth_header):
    _no_kick(client)
    r = client.post("/syllabus/import", json={"exam": "UPSC CSE", "title": "Mine"}, headers=auth_header)
    assert r.status_code == 400
    r = client.post("/syllabus/import", json={"exam": "UPSC CSE", "title": "Mine", "text": "Paper I\nPolity"}, headers=auth_header)
    assert r.status_code == 200
    import_id, job_id = r.json()["import_id"], r.json()["job_id"]
    got = client.get(f"/syllabus/{import_id}", headers=auth_header).json()
    assert got["status"] == "processing" and got["node_count"] == 0
    assert client.post(f"/syllabus/{import_id}/approve", json={}, headers=auth_header).status_code == 409
    assert client.put(f"/syllabus/{import_id}/tree", json={"tree": []}, headers=auth_header).status_code == 409

    with client.app.state.services.session_factory() as db:  # pretend the AI finished
        db.get(SyllabusImport, import_id).status = "pending"
        assert db.get(Job, job_id).payload_json["import_id"] == import_id
        db.commit()
    assert client.put(f"/syllabus/{import_id}/tree", json={"tree": []}, headers=auth_header).status_code == 400
    tree = [_node("GS Paper 1", _node("History", _node("Modern India")), tags=["UPSC"])]
    r = client.put(f"/syllabus/{import_id}/tree", json={"tree": tree, "title": "Renamed"}, headers=auth_header)
    assert r.json()["node_count"] == 3
    r = client.post(f"/syllabus/{import_id}/approve", json={}, headers=auth_header)
    assert r.status_code == 200 and r.json()["created"] == 3

    data = client.get("/syllabus/tree", headers=auth_header).json()
    assert data["leaves"] == 1 and data["coverage"] == 0
    assert data["tree"][0]["children"][0]["children"][0]["title"] == "Modern India"
    assert client.get("/syllabus/tree?exam=APPSC", headers=auth_header).json()["tree"] == []
    assert client.post("/syllabus/recompute-importance", headers=auth_header).json()["topics"] == 3
    assert client.get("/syllabus/nope", headers=auth_header).status_code == 404


def test_seed_route_and_setup(client, auth_header, tmp_path, monkeypatch):
    _starter(tmp_path, monkeypatch)
    added = client.post("/syllabus/seed", headers=auth_header).json()["added"]
    assert added == 1
    assert client.post("/syllabus/seed", headers=auth_header).json()["added"] == 0
    from app.features import syllabus

    syllabus.setup(client.app.state.services)  # start-up seeding never raises and adds nothing twice
    with client.app.state.services.session_factory() as db:
        assert len(list(db.scalars(select(SyllabusImport).where(SyllabusImport.id == service.seed_id("demo"))))) == 1


def test_nightly_importance_job_never_raises(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory)
    from app.features import syllabus

    syllabus._nightly(svc)  # no topics yet: nothing to do
    with factory() as db:
        service.approve(db, _pending(db, [_node("Polity", _node("Preamble"))]))
        db.query(Topic).update({"importance": 0.0})
        db.commit()
    syllabus._nightly(svc)
    with factory() as db:
        assert all(t.importance > 0 for t in db.scalars(select(Topic)))
