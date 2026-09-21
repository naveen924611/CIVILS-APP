"""Scoring and analysis of a finished test (spec 6.17). The tablet shows its own copy of this analysis at once (it works
offline); the server keeps one in `Test.analysis_json` for the weekly report and for the mistake cards.

Confidence -> default mistake type (the owner can change it on the review screen):
    guess or not marked  -> "didnt_know"   (added to the study plan)
    unsure               -> "confused"     (narrowed to two options: comparison card)
    sure                 -> "silly"        (was sure but wrong: read slowly)
"""
from collections.abc import Iterable
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Attempt, Mcq, Test, Topic
from app.db.util import iso

from .common import marks, subject_of

MISTAKE_TYPES = ("didnt_know", "confused", "silly")
CONFIDENCES = ("sure", "unsure", "guess")
TIPS = {
    "didnt_know": "Topics you did not know are added to your revision: open the mistake book and study them.",
    "confused": "You narrowed some questions to two options. Comparison cards were made so you can tell them apart.",
    "silly": "Some mistakes were slips although you felt sure. Read every option slowly before you choose.",
}


def default_mistake_type(confidence: str | None) -> str:
    return {"sure": "silly", "unsure": "confused"}.get((confidence or "").strip().lower(), "didnt_know")


def latest_attempts(db: Session, test_id: str) -> dict[str, Attempt]:
    """The newest attempt per question of a test (a question answered twice counts once)."""
    out: dict[str, Attempt] = {}
    rows = db.scalars(select(Attempt).where(Attempt.test_id == test_id, Attempt.deleted.is_(False)).order_by(Attempt.at))
    for a in rows:
        out[a.mcq_id] = a
    return out


def analyse(attempts: Iterable[Attempt], mcqs: dict[str, Mcq], topics: dict[str, Topic], negative: bool) -> dict:
    attempts = list(attempts)
    total = len(attempts)
    correct = sum(1 for a in attempts if a.correct)
    skipped = sum(1 for a in attempts if a.chosen < 0)
    wrong = total - correct - skipped
    subjects: dict[str, dict] = {}
    study: dict[str, int] = {}
    mistakes = {k: 0 for k in MISTAKE_TYPES}
    conf = {k: {"n": 0, "correct": 0} for k in (*CONFIDENCES, "unmarked")}
    for a in attempts:
        mcq = mcqs.get(a.mcq_id)
        subject = subject_of(mcq.topic_id if mcq else None, topics)
        row = subjects.setdefault(subject, {"subject": subject, "total": 0, "correct": 0, "wrong": 0, "skipped": 0})
        row["total"] += 1
        bucket = conf[a.confidence if a.confidence in CONFIDENCES else "unmarked"]
        if a.chosen >= 0:
            bucket["n"] += 1
            bucket["correct"] += 1 if a.correct else 0
        if a.correct:
            row["correct"] += 1
        elif a.chosen < 0:
            row["skipped"] += 1
        else:
            row["wrong"] += 1
            kind = a.mistake_type if a.mistake_type in MISTAKE_TYPES else default_mistake_type(a.confidence)
            mistakes[kind] += 1
            if kind == "didnt_know" and mcq and mcq.topic_id:
                study[mcq.topic_id] = study.get(mcq.topic_id, 0) + 1
    guesses = conf["guess"]["n"]
    guess_right = conf["guess"]["correct"]
    guess_wrong = guesses - guess_right
    net = marks(guess_right, guess_wrong, True)
    if guesses == 0:
        message = "You did not mark any answer as a guess."
    else:
        message = (f"You guessed {guesses} question{'s' if guesses != 1 else ''} and got {guess_right} right. "
                   f"With negative marking those guesses would give {net:g} marks.")
    tips = [TIPS[k] for k in MISTAKE_TYPES if mistakes[k]]
    subject_rows = sorted(subjects.values(), key=lambda r: (-r["total"], r["subject"]))
    return {
        "total": total, "correct": correct, "wrong": wrong, "skipped": skipped,
        "score": marks(correct, wrong, negative), "max_score": float(total), "negative_marking": bool(negative),
        "accuracy": round(correct / total, 3) if total else 0.0,
        "subjects": subject_rows,
        "mistakes": mistakes,
        "confidence": conf,
        "guessing": {"guesses": guesses, "correct": guess_right, "net_marks_if_negative": net, "message": message},
        "study_topics": [
            {"topic_id": tid, "title": topics[tid].title if tid in topics else "", "count": n}
            for tid, n in sorted(study.items(), key=lambda p: (-p[1], p[0]))
        ],
        "tips": tips,
        "n_attempts": total,
        "analysed_at": iso(datetime.now(timezone.utc)),
    }


def maybe_analyse(db: Session, test: Test) -> bool:
    """Analyses a finished test once all its answers have arrived from the tablet. True when the analysis was (re)written.
    Called from the sync hooks of `tests` and `attempts`, whichever arrives last does the work."""
    if test is None or test.deleted or test.status not in ("done", "analysed"):
        return False
    expected = len(test.mcq_ids or [])
    attempts = latest_attempts(db, test.id)
    if expected == 0 or len(attempts) < expected:
        return False
    if (test.analysis_json or {}).get("n_attempts") == len(attempts):
        return False
    mcqs = {m.id: m for m in db.scalars(select(Mcq).where(Mcq.id.in_(list(attempts))))}
    topics = {t.id: t for t in db.scalars(select(Topic).where(Topic.deleted.is_(False)))}
    result = analyse(attempts.values(), mcqs, topics, bool(test.negative_marking))
    test.analysis_json = result
    if test.score is None:
        test.score = result["score"]
    test.status = "analysed"
    db.flush()
    return True
