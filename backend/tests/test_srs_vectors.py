"""FSRS-6 checked against numbers made by the reference package (data/fsrs_vectors.json)."""
import json
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from app.srs.fsrs6 import (
    DEFAULT_WEIGHTS,
    Fsrs6,
    engine,
    format_time,
    initial_state,
    is_new,
    parse_time,
)
from app.srs.strength import STATUS_ORDER, TopicMemory, card_memory, status_for, topic_memory

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "data" / "fsrs_vectors.json").read_text(encoding="utf-8"))
ANDROID_COPY = Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "test" / "resources" / "fsrs_vectors.json"


def test_android_copy_is_identical():
    assert ANDROID_COPY.read_text(encoding="utf-8") == (ANDROID_COPY.parents[5] / "data" / "fsrs_vectors.json").read_text(
        encoding="utf-8"
    )


@pytest.mark.parametrize("case", VECTORS["cases"], ids=[c["name"] for c in VECTORS["cases"]])
def test_reproduces_reference(case):
    eng = Fsrs6(case["weights"], case["retention"])
    state = None
    for step in case["steps"]:
        at = parse_time(step["at"])
        assert eng.retrievability(state, at) == pytest.approx(step["retrievability_before"], abs=1e-6)
        got = eng.next_intervals(state, at)
        for grade, days in step["intervals"].items():
            assert abs(got[int(grade)] - days) <= 1
        state = eng.review(state, step["grade"], at)
        assert state["stability"] == pytest.approx(step["stability"], abs=1e-6)
        assert state["difficulty"] == pytest.approx(step["difficulty"], abs=1e-6)
        assert (parse_time(state["due"]) - at).days == step["interval_days"]
        assert state["last_review"] == format_time(at)


def test_strength_cases():
    eng = Fsrs6()
    for case in VECTORS["strength_cases"]:
        now = parse_time(case["now"])
        mem = topic_memory(eng, case["states"], now)
        exp = case["expect"]
        assert mem.strength == pytest.approx(exp["strength"], abs=1e-9)
        assert mem.coverage == pytest.approx(exp["coverage"], abs=1e-9)
        assert mem.repeat_share == pytest.approx(exp["repeat_share"], abs=1e-9)
        assert status_for(case["status"], mem) == exp["status"], case["name"]


def test_new_card_and_first_review_gives_interval():
    eng = Fsrs6()
    now = datetime(2026, 9, 20, 10, 0, tzinfo=timezone.utc)
    st = initial_state(now)
    assert is_new(st) and is_new(None) and is_new({})
    assert eng.retrievability(None, now) == 0.0
    ivs = eng.next_intervals(None, now)
    assert ivs[1] == 1 and ivs[4] > ivs[3] >= ivs[2] >= ivs[1]
    after = eng.review(None, 3, now)
    assert after["reps"] == 1 and after["lapses"] == 0
    assert parse_time(after["due"]) - now == timedelta(days=ivs[3])
    assert not is_new(after)


def test_lapse_counts_and_input_not_changed():
    eng = Fsrs6()
    t0 = datetime(2026, 9, 20, 10, 0, tzinfo=timezone.utc)
    s1 = eng.review(None, 3, t0)
    snapshot = dict(s1)
    s2 = eng.review(s1, 1, t0 + timedelta(days=3))
    assert s1 == snapshot and s2["lapses"] == 1 and s2["reps"] == 2
    assert eng.review(None, 1, t0)["lapses"] == 0  # forgetting a card never learned is not a lapse


def test_bad_inputs():
    with pytest.raises(ValueError):
        Fsrs6(weights=[1.0] * 5)
    with pytest.raises(ValueError):
        Fsrs6(retention=0.2)
    with pytest.raises(ValueError):
        Fsrs6().review(None, 5, datetime.now(timezone.utc))
    assert engine(0.2).retention == 0.9  # bad owner setting falls back to the default
    assert engine(0.85).retention == 0.85
    assert engine(None, list(DEFAULT_WEIGHTS)).w == DEFAULT_WEIGHTS


def test_time_helpers():
    assert parse_time("2026-09-20T10:00:00Z") == datetime(2026, 9, 20, 10, tzinfo=timezone.utc)
    assert parse_time("2026-09-20T10:00:00.123456789Z").microsecond == 123456
    assert parse_time("2026-09-20T15:30:00+05:30") == datetime(2026, 9, 20, 10, tzinfo=timezone.utc)
    assert parse_time(datetime(2026, 9, 20, 10)).tzinfo is not None
    assert format_time(datetime(2026, 9, 20, 10, 0, 0, 999999, tzinfo=timezone.utc)) == "2026-09-20T10:00:00.999Z"


def test_interval_is_capped_and_at_least_one_day():
    eng = Fsrs6(max_interval=30)
    assert eng.interval_for(1000.0) == 30
    assert eng.interval_for(0.001) == 1


def test_matches_reference_on_random_reviews():
    fsrs = pytest.importorskip("fsrs")
    rng = random.Random(99)
    for retention in (0.9, 0.87):
        sched = fsrs.Scheduler(desired_retention=retention, learning_steps=(), relearning_steps=(), enable_fuzzing=False)
        eng = Fsrs6(retention=retention)
        card = fsrs.Card(card_id=5, due=datetime(2026, 1, 1, tzinfo=timezone.utc))
        state = None
        at = datetime(2026, 1, 1, 8, 0, tzinfo=timezone.utc)
        for _ in range(40):
            at += timedelta(hours=rng.choice([0.2, 5, 30, 24 * rng.randint(1, 60)]))
            grade = rng.choice([1, 2, 3, 3, 4])
            card, _ = sched.review_card(card, fsrs.Rating(grade), at)
            state = eng.review(state, grade, at)
            assert state["stability"] == pytest.approx(card.stability, abs=1e-6)
            assert state["difficulty"] == pytest.approx(card.difficulty, abs=1e-6)
            assert parse_time(state["due"]) == card.due


def test_topic_memory_and_status_rules():
    eng = Fsrs6()
    now = datetime(2026, 9, 20, 10, tzinfo=timezone.utc)
    assert topic_memory(eng, [], now) is None
    assert card_memory(eng, None, now) == 0.0
    mem = TopicMemory(strength=0.8, coverage=0.9, repeat_share=0.9, cards=10)
    assert status_for("studied", mem) == "strong"
    assert status_for("not_started", TopicMemory(0.0, 0.0, 0.0, 3)) == "not_started"
    assert status_for("strong", TopicMemory(0.0, 0.1, 0.0, 3)) == "strong"  # never lowered
    assert status_for("weird", TopicMemory(0.1, 0.7, 0.0, 3)) == "studied"
    assert status_for("not_started", TopicMemory(0.5, 0.7, 0.6, 3)) == "revised"
    assert STATUS_ORDER[0] == "not_started"
