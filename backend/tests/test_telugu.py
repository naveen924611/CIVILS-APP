from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.api.kv import set_kv
from app.db.models_v2 import Job, TeluguItem, TeluguProgress
from app.features.telugu import practice, seed, service
from app.features.telugu.schemas import FeedbackOut
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway

FILES = "../data/telugu"


def _seeded(env):
    settings, factory = env
    with factory() as db:
        counts = seed.seed(db, seed.seed_dir(settings))
    return settings, factory, counts


# ------------------------------------------------------------------ seed data files

def test_seed_files_are_valid_and_honest():
    items = seed.load_items(seed.seed_dir(type("S", (), {"feeds_file": "/nonexistent/feeds.yaml"})()))
    kinds = {i["kind"] for i in items}
    assert kinds == {"vocab", "passage", "translation", "template"}
    assert len({i["key"] for i in items}) == len(items) >= 150
    # nothing may claim to be official: the syllabus was not available
    assert {i.get("source") for i in items} == {"general"}


def test_seed_is_idempotent_and_stable(env):
    settings, factory, first = _seeded(env)
    assert first["added"] == first["total"] > 150 and first["updated"] == first["removed"] == 0
    with factory() as db:
        second = seed.seed(db, seed.seed_dir(settings))
        assert second["added"] == second["updated"] == second["removed"] == 0
        rows = list(db.scalars(select(TeluguItem)))
        assert len(rows) == first["total"]
        vocab = db.get(TeluguItem, seed.item_id("v001"))
        assert vocab.kind == "vocab" and vocab.content_json["official"] is False and vocab.content_json["key"] == "v001"
        positions = sorted(r.position for r in rows if r.kind == "vocab")
        assert positions == list(range(1, len(positions) + 1))


def test_seed_updates_removes_and_skips_bad_items(env, tmp_path):
    settings, factory = env
    folder = tmp_path / "telugu"
    folder.mkdir()
    (folder / "a.yaml").write_text(
        "items:\n"
        "  - {kind: vocab, key: x1, level: 1, te: 'ఒకటి', en: one}\n"
        "  - {kind: vocab, key: x2, level: 1, te: 'రెండు', en: two}\n"
        "  - {kind: vocab, key: x2, level: 1, te: 'dup', en: dup}\n"
        "  - {kind: vocab, key: x3, level: 1, te: 'మూడు'}\n"
        "  - {kind: weird, key: x4}\n"
        "  - {kind: passage, key: x5, title: t, text_te: t, questions: [{options: [a, b], answer: 5}]}\n",
        encoding="utf-8",
    )
    (folder / "broken.yaml").write_text("items: [unclosed", encoding="utf-8")
    with factory() as db:
        assert seed.seed(db, folder) == {"added": 2, "updated": 0, "removed": 0, "total": 2}
        (folder / "a.yaml").write_text(
            "items:\n  - {kind: vocab, key: x1, level: 2, te: 'ఒకటి', en: 'one (changed)'}\n", encoding="utf-8")
        assert seed.seed(db, folder) == {"added": 0, "updated": 1, "removed": 1, "total": 1}
        assert db.get(TeluguItem, seed.item_id("x2")).deleted is True
        assert db.get(TeluguItem, seed.item_id("x1")).level == 2
        # a missing or empty folder never wipes the table
        assert seed.seed(db, tmp_path / "nope")["removed"] == 0
        assert db.get(TeluguItem, seed.item_id("x1")).deleted is False


def test_seed_dir_prefers_folder_beside_feeds_file(tmp_path):
    (tmp_path / "telugu").mkdir()
    settings = type("S", (), {"feeds_file": str(tmp_path / "feeds.yaml")})()
    assert seed.seed_dir(settings) == tmp_path / "telugu"


# ------------------------------------------------------------------ practice rules

def _items(n_vocab=30):
    out = [practice.Item(f"v{i}", "vocab", i) for i in range(1, n_vocab + 1)]
    out += [practice.Item(f"p{i}", "passage", i) for i in range(1, 4)]
    out += [practice.Item(f"t{i}", "translation", i) for i in range(1, 6)]
    out += [practice.Item(f"w{i}", "template", i) for i in range(1, 3)]
    return out


def test_quotas():
    assert practice.quotas(15, 0) == {"vocab": 8, "passage": 1, "translation": 1, "template": 0}
    assert practice.quotas(15, 2)["template"] == 1 and practice.quotas(15, 5)["template"] == 1
    assert practice.quotas(20, 0) == {"vocab": 10, "passage": 1, "translation": 2, "template": 0}
    assert practice.quotas(5, 2) == {"vocab": 4, "passage": 0, "translation": 0, "template": 0}
    assert set(practice.quotas(0, 2).values()) == {0}


def test_pick_today_first_day_is_file_order():
    monday = date(2026, 9, 21)
    picked = practice.pick_today(_items(), [], monday, 15)
    assert [i.id for i in picked] == [f"v{n}" for n in range(1, 9)] + ["p1", "t1"]


def test_pick_today_repeats_weak_words_but_leaves_room_for_new():
    monday = date(2026, 9, 21)
    yesterday = monday - timedelta(days=1)
    history = [
        practice.Done("v1", 0.0, yesterday, "2026-09-20T10:00:00"),   # needs work
        practice.Done("v2", 1.0, yesterday, "2026-09-20T10:01:00"),   # fine
        practice.Done("v3", None, yesterday, "2026-09-20T10:02:00"),  # no score counts as needs work
    ]
    ids = [i.id for i in practice.pick_today(_items(), history, monday, 15) if i.kind == "vocab"]
    assert ids[:2] == ["v1", "v3"] and len(ids) == 8          # room 8 -> 4 weak places, only 2 weak words
    assert "v2" not in ids and ids[2:] == ["v4", "v5", "v6", "v7", "v8", "v9"]


def test_pick_today_keeps_done_items_in_the_day():
    monday = date(2026, 9, 21)
    history = [practice.Done("v5", 1.0, monday, "2026-09-21T09:00:00")]
    ids = [i.id for i in practice.pick_today(_items(), history, monday, 15) if i.kind == "vocab"]
    assert ids[0] == "v5" and len(ids) == 8 and len(set(ids)) == 8


def test_pick_today_small_pool_and_well_done_items_come_last():
    monday = date(2026, 9, 21)
    items = [practice.Item("a", "vocab", 1), practice.Item("b", "vocab", 2)]
    history = [practice.Done("a", 1.0, monday - timedelta(days=3), "2026-09-18T10:00:00")]
    ids = [i.id for i in practice.pick_today(items, history, monday, 15)]
    assert ids == ["b", "a"]


def test_streak_and_study_day():
    today = date(2026, 9, 21)
    days = {today, today - timedelta(days=1), today - timedelta(days=2), today - timedelta(days=4)}
    assert practice.streak(days, today) == 3
    assert practice.streak(days - {today}, today) == 2
    assert practice.streak(set(), today) == 0
    late = datetime(2026, 9, 20, 20, 0, tzinfo=timezone.utc)  # 01:30 next day in India
    assert practice.study_day(late, "Asia/Kolkata") == date(2026, 9, 21)
    assert practice.study_day(late.replace(tzinfo=None), "Asia/Kolkata") == date(2026, 9, 21)


# ------------------------------------------------------------------ service, routes

def test_today_set_and_progress(env):
    settings, factory, _ = _seeded(env)
    monday = date(2026, 9, 21)
    with factory() as db:
        first = service.today_set(db, settings, monday)
        assert first["minutes"] == 15 and first["total"] == 10 and first["done"] == 0
        vocab = [i for i in first["items"] if i["kind"] == "vocab"]
        assert len(vocab) == 8 and vocab[0]["content"]["te"]
        db.add(TeluguProgress(item_id=vocab[0]["id"], done=True, score=1.0,
                              at=datetime(2026, 9, 21, 4, 0, tzinfo=timezone.utc)))
        db.add(TeluguProgress(item_id=vocab[1]["id"], done=True, score=0.0,
                              at=datetime(2026, 9, 20, 4, 0, tzinfo=timezone.utc)))
        db.add(TeluguProgress(item_id="gone", done=True, score=1.0, at=datetime(2026, 9, 20, 4, 0, tzinfo=timezone.utc)))
        db.commit()
        again = service.today_set(db, settings, monday)
        assert again["done"] == 1 and again["items"][0]["id"] == vocab[0]["id"] and again["items"][0]["done"]
        set_kv(db, "study.telugu_minutes", 25)
        assert service.today_set(db, settings, monday)["minutes"] == 25
        set_kv(db, "study.telugu_minutes", "abc")
        assert service.minutes_setting(db) == 15
        prog = service.progress(db, settings, monday)
        assert prog["total_done"] == 3 and prog["streak"] == 2
        assert prog["kinds"]["vocab"]["practised"] == 2 and prog["kinds"]["vocab"]["avg_score"] == 0.5
        assert prog["recent"][-1] == {"day": "2026-09-21", "count": 1} and len(prog["recent"]) == 14


def test_routes(client, auth_header):
    assert client.get("/telugu/today").status_code == 401
    r = client.get("/telugu/today?day=2026-09-21", headers=auth_header)
    assert r.status_code == 200 and r.json()["total"] == 10   # start-up seeded the items
    assert client.get("/telugu/today?day=nonsense", headers=auth_header).status_code == 400
    assert client.get("/telugu/progress", headers=auth_header).json()["total_done"] == 0
    again = client.post("/telugu/reseed", headers=auth_header).json()
    assert again["added"] == 0 and again["total"] > 150


# ------------------------------------------------------------------ telugu_feedback job

def _run(factory, svc, payload):
    with factory() as db:
        job = Job(type="telugu_feedback", payload_json=payload)
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        return db.get(Job, jid)


def _first(db, kind, **content):
    for row in db.scalars(select(TeluguItem).where(TeluguItem.kind == kind).order_by(TeluguItem.position)):
        if all(row.content_json.get(k) == v for k, v in content.items()):
            return row.id
    raise AssertionError("no such item")


def test_feedback_job_for_translation_and_template(env):
    settings, factory, _ = _seeded(env)
    good = FeedbackOut(score=7.5, summary="Nice.", strengths=["Meaning is right"],
                       corrections=[{"said": "ప్రభుత్వము", "better": "ప్రభుత్వం", "why": "modern spelling"}],
                       model_answer="ప్రభుత్వం ...")
    gw = FullFakeGateway([good, good])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        t_id = _first(db, "translation", key="t002")
        w_id = _first(db, "template", key="w001")
        pr = TeluguProgress(item_id=t_id, done=True, answer="ప్రభుత్వము రైతులకు కొత్త పథకం")
        db.add(pr)
        db.commit()
        pid = pr.id
    job = _run(factory, svc, {"item_id": t_id, "answer": "ప్రభుత్వము రైతులకు కొత్త పథకం", "progress_id": pid})
    assert job.status == "done" and job.result_json["score"] == 7.5
    assert job.result_json["feedback"]["corrections"][0]["better"] == "ప్రభుత్వం"
    assert job.result_json["feedback"]["model_answer"] and job.result_json["item_id"] == t_id
    with factory() as db:
        assert db.get(TeluguProgress, pid).score == 0.75
    assert "Translate into Telugu: The government has started" in gw.calls[0][1]
    job2 = _run(factory, svc, {"item_id": w_id, "answer": "గౌరవనీయులైన అయ్యా, నమస్కారం"})
    assert job2.status == "done"
    assert "Formal letter to the District Collector" in gw.calls[1][1] and "Subject" in gw.calls[1][1]
    assert svc.extras["pushed"]


def test_feedback_job_te_to_en_prompt_and_retry(env):
    settings, factory, _ = _seeded(env)
    gw = FullFakeGateway([])                      # AI unavailable: job stays queued
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        item = _first(db, "translation", key="t015")
    job = _run(factory, svc, {"item_id": item, "answer": "A new school was built."})
    assert job.status == "queued"
    assert "Translate into English: గ్రామంలో" in gw.calls[0][1]
    gw.answers.append(FeedbackOut(score=42, summary="ok"))     # scores are clamped to 10
    svc2 = FakeServices.build(settings, factory, gw)
    with factory() as db:
        row = db.get(Job, job.id)
        row.result_json = None
        db.commit()
    run_pending(svc2)
    with factory() as db:
        assert db.get(Job, job.id).result_json["score"] == 10.0


@pytest.mark.parametrize("payload,text", [
    ({"item_id": "missing", "answer": "abc def"}, "not found"),
    ({"item_id": "@vocab", "answer": "abc def"}, "Only translations"),
    ({"item_id": "@translation", "answer": " "}, "no answer"),
])
def test_feedback_job_failures(env, payload, text):
    settings, factory, _ = _seeded(env)
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    with factory() as db:
        if payload["item_id"].startswith("@"):
            payload = {**payload, "item_id": db.scalar(select(TeluguItem.id).where(TeluguItem.kind == payload["item_id"][1:]))}
    job = _run(factory, svc, payload)
    assert job.status == "failed" and text in job.error


def test_feedback_schema_is_forgiving():
    out = FeedbackOut.model_validate({"score": "abc", "strengths": "one", "summary": "x"})
    assert out.score == 0.0 and out.strengths == ["one"]
    assert FeedbackOut.model_validate({"score": -3}).score == 0.0
    assert FeedbackOut.model_validate({"strengths": None}).strengths == []
