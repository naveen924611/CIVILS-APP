"""Writes data/fsrs_vectors.json with the reference package `fsrs` (needs `pip install fsrs`, developers only).

    cd backend && python -m app.srs.gen_vectors

The file is the truth for both ports: tests/test_srs_vectors.py (Python) and the Kotlin FsrsVectorsTest read it.
Reference set-up: fsrs.Scheduler(learning_steps=(), relearning_steps=(), enable_fuzzing=False), so the first review of
a card gives a review interval at once. Each step records the state AFTER the review, the interval in days, the
retrievability just BEFORE the review and the next interval for all four grades before the review.
"""
from __future__ import annotations

import json
import random
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fsrs import Card, Rating, Scheduler

from app.srs.fsrs6 import DEFAULT_WEIGHTS, Fsrs6, format_time

ROOT = Path(__file__).resolve().parents[3]
START = datetime(2026, 1, 5, 6, 30, tzinfo=timezone.utc)


def run_case(name: str, weights, retention: float, steps: list[tuple[float, int]]) -> dict:
    """steps = [(hours since the previous review, grade), ...]"""
    sched = Scheduler(
        parameters=list(weights), desired_retention=retention, learning_steps=(), relearning_steps=(),
        enable_fuzzing=False,
    )
    card = Card(card_id=1, due=START)
    at = START
    out = []
    for hours, grade in steps:
        at = at + timedelta(hours=hours)
        before = sched.get_card_retrievability(card, at) if card.last_review else 0.0
        intervals = {}
        for g in (1, 2, 3, 4):
            c2, _ = sched.review_card(card, Rating(g), at)
            intervals[str(g)] = (c2.due - at).days
        card, _ = sched.review_card(card, Rating(grade), at)
        out.append({
            "at": format_time(at), "grade": grade, "stability": card.stability, "difficulty": card.difficulty,
            "interval_days": (card.due - at).days, "retrievability_before": before, "intervals": intervals,
        })
    return {"name": name, "weights": list(weights), "retention": retention, "steps": out}


def ideal(grades: list[int], stretch: float = 1.0) -> list[tuple[float, int]]:
    """Review exactly when due (gap = the interval the reference chose), so the sequence follows the schedule."""
    sched = Scheduler(learning_steps=(), relearning_steps=(), enable_fuzzing=False)
    card = Card(card_id=1, due=START)
    at = START
    steps: list[tuple[float, int]] = []
    gap = 0.0
    for g in grades:
        steps.append((gap, g))
        at = at + timedelta(hours=gap)
        card, _ = sched.review_card(card, Rating(g), at)
        gap = (card.due - at).days * 24 * stretch
    return steps


def random_steps(rng: random.Random, n: int) -> list[tuple[float, int]]:
    steps = [(0.0, rng.choice([1, 2, 3, 3, 3, 4]))]
    for _ in range(n - 1):
        kind = rng.random()
        hours = rng.choice([0.5, 3.0, 20.0]) if kind < 0.15 else rng.uniform(1, 90) * 24
        steps.append((round(hours, 3), rng.choice([1, 2, 3, 3, 3, 3, 4, 4])))
    return steps


def strength_cases() -> list[dict]:
    eng = Fsrs6()
    now = datetime(2026, 3, 1, 9, 0, tzinfo=timezone.utc)
    rng = random.Random(7)

    def card(reviews: list[tuple[int, int]]):
        """reviews = [(days before `now`, grade)]"""
        state = None
        for days_ago, grade in sorted(reviews, key=lambda x: -x[0]):
            state = eng.review(state, grade, now - timedelta(days=days_ago))
        return state

    from app.srs.strength import status_for, topic_memory

    groups = [
        ("no reviews yet", [None, None, None], "not_started"),
        ("one of four reviewed", [card([(1, 3)]), None, None, None], "not_started"),
        ("mostly studied", [card([(3, 3)]), card([(2, 3)]), card([(5, 4)]), None], "in_progress"),
        ("well revised", [card([(40, 3), (25, 3), (10, 3)]) for _ in range(5)], "studied"),
        ("strong", [card([(90, 3), (60, 3), (30, 3), (5, 4)]) for _ in range(4)], "revised"),
        ("owner set strong stays strong", [card([(1, 1)]), card([(1, 2)])], "strong"),
    ]
    for i in range(6):
        cards = [
            card([(rng.randint(1, 120), rng.choice([1, 2, 3, 4])) for _ in range(rng.randint(1, 4))]) if rng.random() < 0.8
            else None
            for _ in range(rng.randint(2, 8))
        ]
        groups.append((f"random {i}", cards, rng.choice(["not_started", "in_progress", "studied"])))
    out = []
    for name, states, status in groups:
        mem = topic_memory(eng, states, now)
        assert mem is not None
        out.append({
            "name": name, "now": format_time(now), "states": states, "status": status,
            "expect": {
                "strength": mem.strength, "coverage": mem.coverage, "repeat_share": mem.repeat_share,
                "status": status_for(status, mem),
            },
        })
    return out


def main() -> None:
    rng = random.Random(20260920)
    cases = [
        run_case("all good, on time", DEFAULT_WEIGHTS, 0.9, ideal([3] * 9)),
        run_case("easy start then good", DEFAULT_WEIGHTS, 0.9, ideal([4, 3, 3, 3, 3, 3])),
        run_case("again first, then good", DEFAULT_WEIGHTS, 0.9, ideal([1, 3, 3, 3, 3])),
        run_case("hard all the way", DEFAULT_WEIGHTS, 0.9, ideal([2] * 8)),
        run_case("lapse in the middle", DEFAULT_WEIGHTS, 0.9, ideal([3, 3, 3, 1, 3, 3, 4])),
        run_case("reviewed late", DEFAULT_WEIGHTS, 0.9, ideal([3, 3, 3, 3, 3], stretch=2.0)),
        run_case("reviewed early", DEFAULT_WEIGHTS, 0.9, ideal([3, 3, 3, 3, 3, 3], stretch=0.5)),
        run_case("same day repeats", DEFAULT_WEIGHTS, 0.9, [(0, 1), (0.1, 3), (0.2, 3), (5.0, 4), (30.0, 3), (2.0, 1)]),
        run_case("retention 0.85", DEFAULT_WEIGHTS, 0.85, ideal([3, 3, 4, 3, 3, 1, 3])),
        run_case("retention 0.95", DEFAULT_WEIGHTS, 0.95, ideal([3, 3, 4, 3, 3, 1, 3])),
        run_case("retention 0.8", DEFAULT_WEIGHTS, 0.80, ideal([3, 3, 3, 3])),
    ]
    custom = list(DEFAULT_WEIGHTS)
    custom[8], custom[13], custom[20] = 1.5, 0.35, 0.25
    cases.append(run_case("custom weights", custom, 0.9, ideal([3, 2, 3, 4, 1, 3])))
    for i in range(36):
        r = rng.choice([0.9, 0.9, 0.85, 0.93])
        cases.append(run_case(f"random {i}", DEFAULT_WEIGHTS, r, random_steps(rng, rng.randint(4, 14))))
    doc = {
        "about": "Generated by backend/app/srs/gen_vectors.py with the reference package fsrs 6.3.2 "
                 "(Scheduler(learning_steps=(), relearning_steps=(), enable_fuzzing=False)). Do not edit by hand.",
        "reference": "fsrs 6.3.2",
        "tolerance": {"float": 1e-6, "interval_days": 1},
        "cases": cases,
        "strength_cases": strength_cases(),
    }
    text = json.dumps(doc, indent=1)
    target = ROOT / "data" / "fsrs_vectors.json"
    target.write_text(text, encoding="utf-8")
    android = ROOT / "android" / "app" / "src" / "test" / "resources"
    android.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(target, android / "fsrs_vectors.json")
    print(f"wrote {target} ({len(cases)} cases) and the Android copy")


if __name__ == "__main__":
    main()
