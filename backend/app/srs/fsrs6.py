"""FSRS-6 memory model, the way Civils Companion uses it.

Choices (same on the tablet, see android srs/Fsrs.kt; both are checked against data/fsrs_vectors.json, which is
generated with the reference package `fsrs` 6.3.2):
  * day based: every interval is a whole number of days (at least 1), there are NO learning steps, so the very
    first review of a card already gives a review interval;
  * no fuzzing (the same answers on every device);
  * default FSRS-6 weights (21 numbers) and desired retention 0.9 (KV `revision.retention`).

Card state (stored in Card.fsrs_state_json, and in Review.state_json as "the state before this review"):

    {"stability": 2.3065,          days for retrievability to fall to 90 percent; null for a card never reviewed
     "difficulty": 4.9,           1 (easy) to 10 (hard); null for a card never reviewed
     "due": "2026-09-22T10:00:00.000Z",
     "last_review": "2026-09-20T10:00:00.000Z",   null for a card never reviewed
     "reps": 1,                   number of reviews
     "lapses": 0}                 times the card was forgotten (Again on an already learned card)

A missing state (None, {} or a state without "stability") means "new card".  Grades: 1 Again, 2 Hard, 3 Good, 4 Easy.
Like the reference, elapsed time is counted in WHOLE days (rounded down) for retrievability; a review less than one
day after the previous one uses the "same-day" stability formula.
"""
from __future__ import annotations

import math
import re
from datetime import datetime, timedelta, timezone
from typing import Any

DEFAULT_WEIGHTS: tuple[float, ...] = (
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614,
    0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
)
DEFAULT_RETENTION = 0.9
MAX_INTERVAL_DAYS = 36500
STABILITY_MIN = 0.001
DIFFICULTY_MIN = 1.0
DIFFICULTY_MAX = 10.0
GRADES = (1, 2, 3, 4)
AGAIN, HARD, GOOD, EASY = GRADES

_FRACTION = re.compile(r"\.(\d+)")


def parse_time(value: Any) -> datetime:
    """ISO text (with Z or an offset) or a datetime -> aware UTC datetime. Tolerates 1 to 9 fraction digits."""
    if isinstance(value, datetime):
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)
    text = str(value).strip().replace("Z", "+00:00")
    text = _FRACTION.sub(lambda m: "." + (m.group(1) + "000000")[:6], text, count=1)
    dt = datetime.fromisoformat(text)
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)


def format_time(dt: datetime) -> str:
    """Aware datetime -> "2026-09-20T10:00:00.000Z" (millisecond precision, UTC)."""
    return dt.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _trim(dt: datetime) -> datetime:
    """Whole milliseconds, UTC (the tablet stores times with millisecond precision)."""
    dt = dt.astimezone(timezone.utc)
    return dt.replace(microsecond=dt.microsecond // 1000 * 1000)


def is_new(state: dict | None) -> bool:
    return not state or state.get("stability") is None or state.get("difficulty") is None


def initial_state(now: datetime | None = None) -> dict:
    """The state of a card that was never reviewed (it is due right away)."""
    now = now or datetime.now(timezone.utc)
    return {"stability": None, "difficulty": None, "due": format_time(now), "last_review": None, "reps": 0, "lapses": 0}


class Fsrs6:
    def __init__(
        self,
        weights: tuple[float, ...] | list[float] = DEFAULT_WEIGHTS,
        retention: float = DEFAULT_RETENTION,
        max_interval: int = MAX_INTERVAL_DAYS,
    ):
        if len(weights) != 21:
            raise ValueError(f"FSRS-6 needs 21 weights, got {len(weights)}")
        if not 0.5 <= retention <= 0.99:
            raise ValueError("retention must be between 0.5 and 0.99")
        self.w = tuple(float(x) for x in weights)
        self.retention = float(retention)
        self.max_interval = int(max_interval)
        self.decay = -self.w[20]
        self.factor = 0.9 ** (1 / self.decay) - 1

    # ---------------------------------------------------------------- pieces of the model
    def retrievability(self, state: dict | None, now: datetime) -> float:
        """Chance (0..1) to recall the card at `now`. 0 for a card never reviewed."""
        if is_new(state) or not state.get("last_review"):
            return 0.0
        elapsed = max(0, (now - parse_time(state["last_review"])).days)
        return (1 + self.factor * elapsed / state["stability"]) ** self.decay

    def interval_for(self, stability: float) -> int:
        """Days until retrievability falls to the desired retention (whole days, 1 .. max_interval)."""
        days = (stability / self.factor) * (self.retention ** (1 / self.decay) - 1)
        return min(max(int(round(days)), 1), self.max_interval)

    def _initial_stability(self, grade: int) -> float:
        return max(self.w[grade - 1], STABILITY_MIN)

    def _initial_difficulty(self, grade: int, clamp: bool) -> float:
        d = self.w[4] - math.e ** (self.w[5] * (grade - 1)) + 1
        return min(max(d, DIFFICULTY_MIN), DIFFICULTY_MAX) if clamp else d

    def _next_difficulty(self, difficulty: float, grade: int) -> float:
        arg1 = self._initial_difficulty(EASY, clamp=False)
        delta = -(self.w[6] * (grade - 3))
        arg2 = difficulty + (10.0 - difficulty) * delta / 9.0
        nxt = self.w[7] * arg1 + (1 - self.w[7]) * arg2
        return min(max(nxt, DIFFICULTY_MIN), DIFFICULTY_MAX)

    def _short_term_stability(self, stability: float, grade: int) -> float:
        inc = math.e ** (self.w[17] * (grade - 3 + self.w[18])) * stability ** -self.w[19]
        if grade >= HARD:
            inc = max(inc, 1.0)
        return max(stability * inc, STABILITY_MIN)

    def _next_stability(self, difficulty: float, stability: float, r: float, grade: int) -> float:
        w = self.w
        if grade == AGAIN:
            long_term = w[11] * difficulty ** -w[12] * ((stability + 1) ** w[13] - 1) * math.e ** ((1 - r) * w[14])
            short_term = stability / math.e ** (w[17] * w[18])
            nxt = min(long_term, short_term)
        else:
            hard_penalty = w[15] if grade == HARD else 1
            easy_bonus = w[16] if grade == EASY else 1
            nxt = stability * (
                1 + math.e ** w[8] * (11 - difficulty) * stability ** -w[9] * (math.e ** ((1 - r) * w[10]) - 1)
                * hard_penalty * easy_bonus
            )
        return max(nxt, STABILITY_MIN)

    # ---------------------------------------------------------------- public API
    def _step(self, state: dict | None, grade: int, now: datetime) -> tuple[float, float, int, int, int]:
        """(stability, difficulty, interval_days, reps, lapses) after grading. Shared by review and next_intervals."""
        if grade not in GRADES:
            raise ValueError(f"grade must be 1, 2, 3 or 4, got {grade}")
        reps = int((state or {}).get("reps") or 0)
        lapses = int((state or {}).get("lapses") or 0)
        if is_new(state):
            stability = self._initial_stability(grade)
            difficulty = self._initial_difficulty(grade, clamp=True)
        else:
            assert state is not None
            old_s, old_d = float(state["stability"]), float(state["difficulty"])
            last = parse_time(state["last_review"]) if state.get("last_review") else None
            days = (now - last).days if last is not None else None
            if days is not None and days < 1:
                stability = self._short_term_stability(old_s, grade)
            else:
                stability = self._next_stability(old_d, old_s, self.retrievability(state, now), grade)
            difficulty = self._next_difficulty(old_d, grade)
            if grade == AGAIN:
                lapses += 1
        return stability, difficulty, self.interval_for(stability), reps + 1, lapses

    def review(self, state: dict | None, grade: int, now: datetime) -> dict:
        """Returns the new state after grading the card `grade` (1..4) at `now`. Does not change `state`."""
        now = _trim(now)
        stability, difficulty, days, reps, lapses = self._step(state, grade, now)
        return {
            "stability": stability,
            "difficulty": difficulty,
            "due": format_time(now + timedelta(days=days)),
            "last_review": format_time(now),
            "reps": reps,
            "lapses": lapses,
        }

    def next_intervals(self, state: dict | None, now: datetime) -> dict[int, int]:
        """Days until the next review for each grade, e.g. {1: 1, 2: 3, 3: 6, 4: 11} (shown on the grade buttons)."""
        now = _trim(now)
        return {grade: self._step(state, grade, now)[2] for grade in GRADES}


def engine(retention: float | None = None, weights: list[float] | tuple[float, ...] | None = None) -> Fsrs6:
    """Convenience: an engine with the owner's retention (KV `revision.retention`, default 0.9)."""
    r = DEFAULT_RETENTION if retention is None else retention
    try:
        return Fsrs6(weights or DEFAULT_WEIGHTS, r)
    except ValueError:
        return Fsrs6(DEFAULT_WEIGHTS, DEFAULT_RETENTION)
