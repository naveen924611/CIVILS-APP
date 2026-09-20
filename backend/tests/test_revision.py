"""Revision: card generation, the queue with its rules, grading, strength, snooze, Sunday review, routes."""
from datetime import datetime, timedelta, timezone

import pytest

from app.api.kv import set_kv
from app.db.models import Card
from app.db.models_v2 import Highlight, Note, Review, RevisionRule, Topic
from app.features.revision import cardgen, service
from app.features.revision import queue as q
from app.srs import fsrs6

MON = datetime(2026, 9, 21, 6, 0, tzinfo=timezone.utc)  # Monday 11:30 India
SUN = datetime(2026, 9, 20, 6, 0, tzinfo=timezone.utc)  # Sunday


def mk_topic(db, title, parent=None, level=2, **kw):
    t = Topic(title=title, parent_id=parent.id if parent else None, level=level, approved=True, **kw)
    db.add(t)
    db.flush()
    return t


def mk_card(db, topic, front, state=None, due=None, group=None):
    if due is None:
        due = fsrs6.parse_time(state["due"]) if state else MON - timedelta(days=1)
    c = Card(front=front, back="answer " + front, topic_id=topic.id if topic else None, source_type="note",
             group=group or (topic.title if topic else "Current affairs"), fsrs_state_json=state, due_at=due)
    db.add(c)
    db.flush()
    return c


def reviewed_state(days_ago=3, stability=5.0, now=MON):
    last = now - timedelta(days=days_ago)
    return {"stability": stability, "difficulty": 5.0, "due": fsrs6.format_time(last + timedelta(days=int(stability))),
            "last_review": fsrs6.format_time(last), "reps": 2, "lapses": 0}


@pytest.fixture
def db(env):
    _, factory = env
    with factory() as s:
        yield s


# ------------------------------------------------------------------------------------------------- pure queue rules
def test_snooze_offsets_spread_over_three_days():
    assert q.snooze_offsets(0) == []
    assert q.snooze_offsets(1) == [1]
    assert q.snooze_offsets(6) == [1, 1, 2, 2, 3, 3]
    assert set(q.snooze_offsets(9)) == {1, 2, 3}
    # a small daily limit needs more days
    offs = q.snooze_offsets(10, max_per_day=2)
    assert max(offs) == 5 and all(offs.count(d) <= 2 for d in set(offs))


def test_priority_formula_uses_the_four_parts():
    assert q.priority(q.DEFAULT_WEIGHTS, 0.5, 0.4, 0.5, 0.2) == pytest.approx(0.4 * 0.5 + 0.25 * 0.4 + 0.2 * 0.5 + 0.15 * 0.2)


def test_manual_order_puts_named_groups_first():
    a, b, c = (q.QueueGroup(key=k, topic_id=k, title=k, subject="s", reason="", priority=0.0, card_ids=["x"]) for k in "abc")
    assert [g.key for g in q.apply_manual_order([a, b, c], ["c", "a"])] == ["c", "a", "b"]


# ------------------------------------------------------------------------------------------------- cards from notes
def test_cardgen_from_note_is_idempotent_and_skips_duplicates(db):
    subj = mk_topic(db, "Polity", level=1)
    topic = mk_topic(db, "Fundamental Rights", subj)
    note = Note(topic_id=topic.id, sections={
        "cards": [{"front": "Which article gives equality before law?", "back": "Article 14"}],
        "must_remember": ["Article 21 protects life and personal liberty of every person", "Art 32"],
    })
    db.add(note)
    db.flush()
    out = cardgen.generate_cards(db, now=MON)
    assert out["created"] == 3
    cards = db.query(Card).all()
    assert {c.group for c in cards} == {"Polity"}
    assert all(c.fsrs_state_json is None and c.due_at is not None for c in cards)
    finish = [c for c in cards if c.front.startswith("Fundamental Rights: Finish the point")]
    assert len(finish) == 1 and "Article 21 protects" in finish[0].front and finish[0].front.endswith("...")
    assert any(c.front.startswith("Recall this point about Fundamental Rights") for c in cards)  # the short point
    assert cardgen.generate_cards(db, now=MON)["created"] == 0  # nothing twice


def test_cardgen_highlights_and_topic_filter(db):
    topic = mk_topic(db, "Budget")
    other = mk_topic(db, "Rivers")
    db.add_all([
        Highlight(text="The fiscal deficit target for the year is set in the budget", kind="must", topic_id=topic.id),
        Highlight(text="Godavari is the longest peninsular river", kind="card", topic_id=other.id),
        Highlight(text="just a highlight", kind="point", topic_id=topic.id),
    ])
    db.flush()
    assert cardgen.generate_cards(db, topic_id=topic.id, now=MON) == {"created": 1, "notes": 0, "highlights": 1}
    assert cardgen.generate_cards(db, now=MON)["created"] == 1  # only the rivers one is left


def test_finish_sentence_front_short_point():
    assert cardgen.finish_sentence_front("Art 32", "Rights").startswith("Recall this point about Rights")


# ------------------------------------------------------------------------------------------------- queue
def test_queue_orders_weak_topics_first_and_reports_reasons(db):
    subj = mk_topic(db, "Polity", level=1)
    weak = mk_topic(db, "Weak topic", subj, strength=0.1, importance=5)
    fine = mk_topic(db, "Fine topic", subj, strength=0.9, importance=5)
    for i in range(3):
        mk_card(db, weak, f"w{i}", reviewed_state(days_ago=10, stability=4))
        mk_card(db, fine, f"f{i}", reviewed_state(days_ago=5, stability=5))
    mk_card(db, None, "news1", None, group="Current affairs")
    out = service.build_queue(db, MON)
    titles = [g["title"] for g in out["groups"]]
    assert titles[0] == "Weak topic"
    assert out["groups"][0]["reason"] == "Weak"
    news = next(g for g in out["groups"] if g["title"] == "Current affairs")
    assert news["reason"] == "From your briefs" and news["new"] == 1
    assert out["total_cards"] == 7 and out["mode"] == "smart"
    assert out["minutes"] == round(7 * 0.7)


def test_only_due_cards_and_new_card_limit(db):
    topic = mk_topic(db, "T")
    mk_card(db, topic, "not due", reviewed_state(days_ago=1, stability=30))  # due far away
    mk_card(db, topic, "due", reviewed_state(days_ago=9, stability=3))
    for i in range(5):
        mk_card(db, topic, f"new{i}")
    set_kv(db, service.KV_NEW, 2)
    out = service.build_queue(db, MON)
    ids = out["groups"][0]["card_ids"]
    assert len(ids) == 3  # the due one and two new ones
    assert out["groups"][0]["new"] == 2
    assert out["groups"][0]["due"] == 1


def test_max_cards_cut_and_pinned_subject_and_daily_group(db):
    polity = mk_topic(db, "Polity", level=1)
    geo = mk_topic(db, "Geography", level=1)
    tp = mk_topic(db, "Polity topic", polity, strength=0.9)
    tg = mk_topic(db, "Geo topic", geo, strength=0.0, importance=9)
    for i in range(6):
        mk_card(db, tp, f"p{i}", reviewed_state(8, 5))
        mk_card(db, tg, f"g{i}", reviewed_state(10, 3))
    for i in range(6):
        mk_card(db, None, f"c{i}", None, group="Current affairs")
    set_kv(db, service.KV_MAX, 10)
    db.add(RevisionRule(type="pinned_subject", params_json={"subject": "Polity"}, enabled=True))
    db.add(RevisionRule(type="daily_group", params_json={"group": "Current affairs", "cards": 3}, enabled=True))
    db.flush()
    out = service.build_queue(db, MON)
    assert out["total_cards"] == 10
    assert out["groups"][0]["title"] == "Polity topic" and out["groups"][0]["reason"] == "Pinned subject"
    ca = next(g for g in out["groups"] if g["title"] == "Current affairs")
    assert ca["cards"] == 3  # the daily group is capped to its share and is never cut away
    assert [g["title"] for g in out["groups"]][:2] == ["Polity topic", "Current affairs"]


def test_my_order_and_smart_order_toggle(db):
    a = mk_topic(db, "Alpha", strength=0.0, importance=9)
    b = mk_topic(db, "Beta", strength=0.9)
    mk_card(db, a, "a1", reviewed_state(10, 3))
    mk_card(db, b, "b1", reviewed_state(10, 3))
    day = service.study_day(MON)
    smart = service.build_queue(db, MON)
    assert smart["groups"][0]["title"] == "Alpha"
    service.save_manual_order(db, day, [b.id, a.id])
    mine = service.build_queue(db, MON)
    assert mine["mode"] == "my" and [g["title"] for g in mine["groups"]] == ["Beta", "Alpha"]
    assert service.build_queue(db, MON, smart=True)["groups"][0]["title"] == "Alpha"
    assert service.clear_manual_order(db, day) == 1
    assert service.build_queue(db, MON)["mode"] == "smart"


def test_snooze_moves_cards_to_next_days(db):
    topic = mk_topic(db, "Snoozed")
    for i in range(6):
        mk_card(db, topic, f"s{i}", reviewed_state(10, 3))
    other = mk_topic(db, "Other")
    mk_card(db, other, "o", reviewed_state(10, 3))
    out = service.snooze_topic(db, topic.id, MON)
    assert out["moved"] == 6 and out["days"] == [1, 2, 3]
    titles = [g["title"] for g in service.build_queue(db, MON)["groups"]]
    assert titles == ["Other"]
    # two days later some of them are back
    later = MON + timedelta(days=2)
    assert "Snoozed" in [g["title"] for g in service.build_queue(db, later)["groups"]]


def test_sunday_review_adds_fading_cards_of_the_week(db):
    topic = mk_topic(db, "Weekly")
    fading = mk_card(db, topic, "fading", reviewed_state(days_ago=5, stability=3, now=SUN),
                     due=SUN + timedelta(days=2))  # reviewed 5 days ago, recall about 0.86, not due yet
    strong = mk_card(db, topic, "strong", reviewed_state(days_ago=1, stability=40, now=SUN), due=SUN + timedelta(days=39))
    old = mk_card(db, topic, "old", reviewed_state(days_ago=30, stability=100, now=SUN), due=SUN + timedelta(days=70))
    rev = service.sunday_review(db, SUN)
    assert rev["is_sunday"] and rev["card_ids"] == [fading.id]
    out = service.build_queue(db, SUN)
    assert out["sunday_review"] and out["groups"][0]["card_ids"] == [fading.id]
    assert out["groups"][0]["reason"] == "Sunday review"
    set_kv(db, service.KV_SUNDAY, False)
    assert service.build_queue(db, SUN)["total_cards"] == 0
    assert strong.id and old.id


# ------------------------------------------------------------------------------------------------- grading and strength
def test_grade_card_writes_review_and_state(db):
    topic = mk_topic(db, "T")
    card = mk_card(db, topic, "c")
    st = service.grade_card(db, card, 3, MON)
    assert st["reps"] == 1 and st["stability"] == pytest.approx(2.3065)
    assert card.fsrs_state_json == st and card.due_at is not None
    rv = db.query(Review).one()
    assert rv.card_id == card.id and rv.grade == 3 and rv.state_json is None  # state BEFORE the review: it was new
    later = MON + timedelta(days=2)
    st2 = service.grade_card(db, card, 1, later)
    assert st2["lapses"] == 1 and st2["stability"] < st["stability"] * 5
    assert sum(1 for r in db.query(Review) if r.state_json) == 1
    assert set(service.intervals_for(db, card, later)) == {1, 2, 3, 4}


def test_update_strength_and_status_only_goes_up(db):
    topic = mk_topic(db, "Study me", status="not_started")
    for i in range(4):
        mk_card(db, topic, f"c{i}", reviewed_state(days_ago=1, stability=30))
    out = service.update_strength(db, MON)
    assert out["strength_changed"] == 1 and out["status_upgraded"] == 1
    assert topic.strength > 0.8 and topic.status == "strong"
    topic.status = "strong"
    for c in db.query(Card):
        c.fsrs_state_json = None
    service.update_strength(db, MON)
    assert topic.status == "strong" and topic.strength == 0.0  # strength follows the cards, status never drops


# ------------------------------------------------------------------------------------------------- routes and jobs
def test_routes(client, auth_header):
    from app.db.session import get_session_factory

    r = client.get("/revision/queue", headers=auth_header)
    assert r.status_code == 200 and r.json()["groups"] == []
    assert client.post("/revision/generate", json={}, headers=auth_header).json()["created"] == 0
    assert client.post("/revision/review", json={"card_id": "nope", "grade": 3}, headers=auth_header).status_code == 404
    assert client.post("/revision/review", json={"card_id": "x", "grade": 9}, headers=auth_header).status_code == 422
    assert client.get("/revision/cards/nope/intervals", headers=auth_header).status_code == 404
    assert client.put("/revision/order", json={"date": "2026-09-21", "group_order": ["a"]}, headers=auth_header).json()["group_order"] == ["a"]
    assert client.put("/revision/order", json={"date": "bad", "group_order": []}, headers=auth_header).status_code == 400
    assert client.delete("/revision/order?date=2026-09-21", headers=auth_header).json() == {"cleared": 1}
    assert client.get("/revision/sunday-review", headers=auth_header).status_code == 200
    assert client.post("/revision/recompute", headers=auth_header).json()["topics"] == 0
    assert client.get("/revision/queue").status_code in (401, 403)

    with get_session_factory()() as s:
        t = mk_topic(s, "Route topic")
        note = Note(topic_id=t.id, sections={"cards": [{"front": "Front of a card?", "back": "Back"}]})
        s.add(note)
        s.commit()
        topic_id = t.id
    assert client.post("/revision/generate", json={"topic_id": topic_id}, headers=auth_header).json()["created"] == 1
    body = client.get("/revision/queue", headers=auth_header).json()
    assert body["total_cards"] == 1
    card_id = body["groups"][0]["card_ids"][0]
    done = client.post("/revision/review", json={"card_id": card_id, "grade": 3}, headers=auth_header).json()
    assert done["state"]["reps"] == 1 and set(done["intervals"]) == {"1", "2", "3", "4"}
    assert client.get(f"/revision/cards/{card_id}/intervals", headers=auth_header).json()["intervals"]["3"] >= 1
    assert client.post("/revision/snooze", json={"topic_id": topic_id}, headers=auth_header).json()["moved"] == 0
    assert client.get("/revision/queue", headers=auth_header).json()["total_cards"] == 0


def test_nightly_job_makes_cards_and_updates_strength(env):
    from app.features.revision import nightly
    from tests.helpers import FakeServices

    settings, factory = env
    with factory() as s:
        t = mk_topic(s, "Night topic")
        s.add(Note(topic_id=t.id, sections={"cards": [{"front": "Night question?", "back": "Night answer"}]}))
        s.commit()
    svc = FakeServices.build(settings, factory)
    nightly(svc)
    with factory() as s:
        assert s.query(Card).count() == 1
