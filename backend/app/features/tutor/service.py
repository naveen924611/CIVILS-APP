"""Retrieval and answering for the tutor. Pure functions over a database session and the AI gateway."""
import re
from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Note, Topic
from app.llm import promptlib
from app.rag.search import search as rag_search
from app.rag.search import tokens

PROMPT = "tutor_v1"
PROMPT_GENERAL = "tutor_general_v1"
NOT_FROM_MATERIAL = "Not from your material. "
NO_MATERIAL_TELUGU = "మీ నోట్స్‌లో లేదు. "
_TELUGU = re.compile(r"[ఀ-౿]")
_CITE = re.compile(r"\[(\d{1,2})\]")
MAX_PASSAGE_CHARS = 900
MAX_QUESTION_CHARS = 1500
# Tutor modes chosen on the Ask screen (spec 7.8). Empty style = the default short exam answer.
MODE_STYLES = {
    "simple": "Style for this reply: explain very simply, as to a beginner, with one everyday example.",
    "depth": "Style for this reply: go in depth. Give a fuller explanation (up to 8 short bullet points), "
             "including the Mains angle. This overrides the length rule.",
    "quiz": "Style for this reply: do NOT explain. Ask the student 3 short exam-style questions on this topic, "
            "numbered, and do not give the answers. This overrides the answer-format rules.",
    "evaluate": "Style for this reply: the student's own answer is inside the question text. Evaluate it: what is good, "
                "what is missing, one improved line, and a mark out of 10. Use only the material for facts.",
}


@dataclass
class Passage:
    text: str
    source: dict  # {"document_id","title","page"} or {"note_id","topic"}


@dataclass
class Retrieved:
    passages: list[Passage] = field(default_factory=list)


def is_telugu(text: str) -> bool:
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return False
    return sum(1 for c in letters if _TELUGU.match(c)) / len(letters) > 0.3


def clean_passage(text: str) -> str:
    """Retrieved text is data. Remove anything that could close our wrapper tag, and squeeze whitespace."""
    text = re.sub(r"</?\s*passage[^>]*>", " ", text, flags=re.I)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:MAX_PASSAGE_CHARS]


def _enough_overlap(query_tokens: list[str], text: str) -> bool:
    if not query_tokens:
        return False
    qset = set(query_tokens)
    shared = qset & set(tokens(text))
    need = 1 if len(qset) <= 2 else 2
    return len(shared) >= need


def note_passages(db: Session, question: str, topic_ids: list[str] | None, limit: int = 2) -> list[Passage]:
    """Notes of the given topics, or the notes that best match the question words."""
    q = tokens(question)
    if not q:
        return []
    stmt = select(Note).where(Note.deleted.is_(False), Note.content_md != "")
    if topic_ids:
        stmt = stmt.where(Note.topic_id.in_(topic_ids))
    scored: list[tuple[float, Note]] = []
    qset = set(q)
    for note in db.scalars(stmt):
        toks = tokens(note.content_md)
        if not toks:
            continue
        shared = qset & set(toks)
        if not _enough_overlap(q, note.content_md) and not (topic_ids and shared):
            continue
        scored.append((len(shared) / (len(qset) or 1), note))
    scored.sort(key=lambda x: x[0], reverse=True)
    out: list[Passage] = []
    for _score, note in scored[:limit]:
        topic = db.get(Topic, note.topic_id)
        title = topic.title if topic else "Notes"
        out.append(Passage(_best_slice(note.content_md, qset), {"note_id": note.id, "topic": title, "topic_id": note.topic_id}))
    return out


def _best_slice(content: str, qset: set[str]) -> str:
    """The paragraph of a note that shares the most words with the question."""
    paras = [p.strip() for p in re.split(r"\n\s*\n", content) if p.strip()]
    if not paras:
        return content[:MAX_PASSAGE_CHARS]
    best = max(paras, key=lambda p: len(qset & set(tokens(p))))
    return best


def retrieve(db: Session, gateway, question: str, topic_ids: list[str] | None = None) -> Retrieved:
    q = tokens(question)
    passages: list[Passage] = []
    seen: set[tuple[str, int]] = set()
    for hit in rag_search(db, gateway, question, topic_ids=topic_ids or None, k=6):
        if not _enough_overlap(q, hit.text) and hit.score < 0.03:
            continue
        key = (hit.document_id, hit.page)
        if key in seen:
            continue
        seen.add(key)
        passages.append(Passage(hit.text, {"document_id": hit.document_id, "title": hit.document_title, "page": hit.page}))
    passages.extend(note_passages(db, question, topic_ids))
    return Retrieved(passages[:7])


def build_passages_text(passages: list[Passage]) -> str:
    lines = []
    for i, p in enumerate(passages, 1):
        label = p.source.get("title") or p.source.get("topic") or "Notes"
        page = f", page {p.source['page']}" if p.source.get("page") else ""
        lines.append(f'<passage id="{i}" from="{clean_passage(str(label))[:80]}{page}">\n{clean_passage(p.text)}\n</passage>')
    return "\n".join(lines)


def cited_sources(answer: str, passages: list[Passage]) -> list[dict]:
    """Sources the answer cites ([1], [2]); when it cites none, the two best passages."""
    used: list[int] = []
    for m in _CITE.finditer(answer):
        n = int(m.group(1))
        if 1 <= n <= len(passages) and n not in used:
            used.append(n)
    if not used:
        used = list(range(1, min(2, len(passages)) + 1))
    out: list[dict] = []
    for n in used:
        src = dict(passages[n - 1].source)
        if src not in out:
            out.append(src)
    return out


def answer(
    db: Session, gateway, question: str, topic_ids: list[str] | None, mode: str = ""
) -> tuple[str, list[dict]] | None:
    """Returns (answer text, sources) or None when the AI is not available."""
    question = question.strip()[:MAX_QUESTION_CHARS]
    style = MODE_STYLES.get(mode, "")
    lang = "Telugu" if is_telugu(question) else "English"
    found = retrieve(db, gateway, question, topic_ids)
    if found.passages:
        system, user = promptlib.load(PROMPT)
        text = gateway.generate_text(
            feature="tutor_answer",
            system=system,
            user=promptlib.render(user, question=question, passages=build_passages_text(found.passages),
                                   language=lang, style=style),
            max_output_tokens=900,
        )
        if not text:
            return None
        return text.strip(), cited_sources(text, found.passages)
    system, user = promptlib.load(PROMPT_GENERAL)
    text = gateway.generate_text(
        feature="tutor_answer",
        system=system,
        user=promptlib.render(user, question=question, language=lang, style=style),
        max_output_tokens=500,
    )
    if not text:
        return None
    label = NO_MATERIAL_TELUGU if lang == "Telugu" else NOT_FROM_MATERIAL
    return label + text.strip(), []
