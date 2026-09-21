"""Past-paper questions (PYQs): import from a Library document, and map every question to syllabus topics (M8).

Import copies questions exactly as printed (the AI only splits the text; it never writes or completes a question). The
answer is stored only when the paper itself printed it. Mapping to topics uses the same word matching as "In the news"
(no AI, works offline), so the importance scores (7.1) can use how often a topic appeared in past papers.
"""
import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import DocPage, Document, Pyq
from app.features.notes.news import best_topics, build_terms
from app.jobs.registry import AiUnavailable, JobFailed
from app.llm.promptlib import load, render

from .common import norm_text
from .schemas import PyqBatch

log = logging.getLogger(__name__)

PAGES_PER_CALL = 3
MAX_CALLS = 24
MAX_CHARS = 9000
MAX_TOPICS = 2
THRESHOLD = 2.0  # lower than for news: an exam question names its topic in fewer words


def _page_groups(db: Session, document_id: str) -> list[tuple[str, str]]:
    """[(page range text, text)] of about PAGES_PER_CALL pages each."""
    pages = list(db.scalars(select(DocPage).where(DocPage.document_id == document_id, DocPage.deleted.is_(False))
                            .order_by(DocPage.page)))
    pages = [p for p in pages if (p.text or "").strip()]
    groups: list[tuple[str, str]] = []
    for i in range(0, len(pages), PAGES_PER_CALL):
        chunk = pages[i:i + PAGES_PER_CALL]
        rng = f"{chunk[0].page}-{chunk[-1].page}" if len(chunk) > 1 else str(chunk[0].page)
        groups.append((rng, "\n\n".join(p.text.strip() for p in chunk)[:MAX_CHARS]))
    return groups


def map_topics_for(db: Session, questions: list[Pyq], terms=None) -> int:
    """Fills `topic_ids` of questions that have none. Returns how many got a topic."""
    terms = terms if terms is not None else build_terms(db)
    if not terms:
        return 0
    mapped = 0
    for q in questions:
        if q.topic_ids:
            continue
        text = " ".join([q.question or "", " ".join(str(o) for o in (q.options or []))])
        hits = best_topics(text, terms, limit=MAX_TOPICS, threshold=THRESHOLD)
        if hits:
            q.topic_ids = [h[0] for h in hits]
            mapped += 1
    db.flush()
    return mapped


def map_all(db: Session) -> dict:
    """Maps every past-paper question that has no topic yet and refreshes the importance scores."""
    rows = list(db.scalars(select(Pyq).where(Pyq.deleted.is_(False))))
    todo = [r for r in rows if not r.topic_ids]
    mapped = map_topics_for(db, todo)
    db.commit()
    recomputed: dict = {}
    if mapped:
        try:
            from app.features.syllabus.importance import recompute

            recomputed = recompute(db)
        except Exception:  # importance is a bonus; never fail the mapping because of it
            log.exception("importance recompute failed")
            db.rollback()
    return {"questions": len(rows), "mapped": mapped, "unmapped": len(todo) - mapped, "importance": recomputed}


def import_pyqs(db: Session, gateway, document_id: str, exam: str, year: int, paper: str = "") -> dict:
    """Reads the pages of a Library document into Pyq rows. Returns {"added", "skipped", "no_answer", "mapped", "pages"}."""
    doc = db.get(Document, document_id)
    if doc is None or doc.deleted:
        raise JobFailed("That document was not found in your library.")
    exam = (exam or "").strip()
    if not exam:
        raise JobFailed("Say which exam the paper is for (for example UPSC or APPSC).")
    groups = _page_groups(db, document_id)
    if not groups:
        raise JobFailed("This document has no readable text yet. Wait until it says 'Processed' in the Library.")
    system, user_tpl = load("tests_pyq")
    seen = {norm_text(p.question) for p in db.scalars(select(Pyq).where(Pyq.deleted.is_(False), Pyq.year == int(year)))
            if p.exam.strip().lower() == exam.lower()}
    added: list[Pyq] = []
    answered = 0
    skipped = 0
    for rng, text in groups[:MAX_CALLS]:
        user = render(user_tpl, exam=exam, year=str(year), paper=paper, pages=rng, text=text)
        out = gateway.generate_json(feature="test_generate", system=system, user=user, schema=PyqBatch, max_output_tokens=4096)
        if out is None:
            continue
        answered += 1
        for item in out.questions:
            question = (item.question or "").strip()
            options = [o.strip() for o in item.options]
            key = norm_text(question)
            if len(question) < 10 or len(options) != 4 or any(not o for o in options) or key in seen:
                skipped += 1
                continue
            seen.add(key)
            idx = item.answer_index if item.answer_index is not None and 0 <= item.answer_index <= 3 else -1
            row = Pyq(exam=exam[:40], year=int(year), paper=(paper or "")[:80], question=question, options=options,
                      answer_index=idx, topic_ids=[], document_id=document_id)
            db.add(row)
            added.append(row)
    if answered == 0:
        raise AiUnavailable("The AI is busy right now. The paper will be read later.")
    db.flush()
    mapped = map_topics_for(db, added)
    return {"added": len(added), "skipped": skipped, "no_answer": sum(1 for r in added if r.answer_index < 0),
            "mapped": mapped, "pages": len(groups)}
