"""Finds the pieces of the owner's own material that answer a question.

Hybrid: meaning search (embeddings, when they exist) + word search (BM25), merged with reciprocal-rank fusion.
Works with word search alone when no embeddings could be made.
"""
import math
import re
import threading
from dataclasses import dataclass

import numpy as np
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Chunk, Document
from app.rag import index as _index

_WORD = re.compile(r"[\wఀ-౿]+", re.UNICODE)
_STOP = frozenset(
    "the a an of and or to in on for with is are was were be by as at from that this it its which what who how why "
    "when where into than then their there these those have has had do does did not no can will would should may".split()
)


@dataclass
class Hit:
    chunk_id: str
    document_id: str
    document_title: str
    page: int
    text: str
    score: float


def tokens(text: str) -> list[str]:
    return [w for w in (m.group(0).lower() for m in _WORD.finditer(text)) if w not in _STOP and len(w) > 1]


class _Cache:
    lock = threading.Lock()
    sig: tuple | None = None
    ids: list[str] = []
    meta: list[tuple[str, int, str, list[str]]] = []  # (document_id, page, text, topic_ids)
    toks: list[list[str]] = []
    mat: np.ndarray | None = None
    has_vec: np.ndarray | None = None
    titles: dict[str, str] = {}


def _reset() -> None:
    _Cache.sig = None


_index._invalidators.append(_reset)


def _load(db: Session) -> None:
    rows = list(db.execute(select(Chunk.id, Chunk.document_id, Chunk.page, Chunk.text, Chunk.topic_ids, Chunk.embedding)))
    docs = {d.id: d for d in db.scalars(select(Document))}
    sig = (len(rows), hash(tuple(r.id for r in rows)), len(docs), sum(1 for r in rows if r.embedding))
    if _Cache.sig == sig:
        return
    dim = next((len(r.embedding) for r in rows if r.embedding), 0)
    mat = np.zeros((len(rows), dim), dtype=np.float32) if dim else None
    has = np.zeros(len(rows), dtype=bool)
    for i, r in enumerate(rows):
        if dim and r.embedding and len(r.embedding) == dim:
            v = np.asarray(r.embedding, dtype=np.float32)
            n = np.linalg.norm(v)
            mat[i] = v / n if n else v
            has[i] = True
    _Cache.ids = [r.id for r in rows]
    _Cache.meta = [(r.document_id, r.page, r.text, list(r.topic_ids or [])) for r in rows]
    _Cache.toks = [tokens(r.text) for r in rows]
    _Cache.mat, _Cache.has_vec = mat, has
    _Cache.titles = {i: d.title for i, d in docs.items() if not d.deleted}
    _Cache.sig = sig


def _bm25(query: list[str], candidates: list[int], k1: float = 1.4, b: float = 0.75) -> dict[int, float]:
    if not query or not candidates:
        return {}
    n = len(candidates)
    avg = sum(len(_Cache.toks[i]) for i in candidates) / n or 1.0
    df: dict[str, int] = {}
    for i in candidates:
        for t in set(_Cache.toks[i]):
            df[t] = df.get(t, 0) + 1
    scores: dict[int, float] = {}
    for i in candidates:
        toks = _Cache.toks[i]
        if not toks:
            continue
        tf: dict[str, int] = {}
        for t in toks:
            tf[t] = tf.get(t, 0) + 1
        s = 0.0
        for q in set(query):
            f = tf.get(q, 0)
            if not f:
                continue
            idf = math.log(1 + (n - df[q] + 0.5) / (df[q] + 0.5))
            s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len(toks) / avg))
        if s > 0:
            scores[i] = s
    return scores


def search(
    db: Session,
    gateway,
    query: str,
    *,
    topic_ids: list[str] | None = None,
    document_ids: list[str] | None = None,
    k: int = 6,
) -> list[Hit]:
    with _Cache.lock:
        _load(db)
        cand = [
            i for i, (doc, _p, _t, tids) in enumerate(_Cache.meta)
            if doc in _Cache.titles
            and (not document_ids or doc in document_ids)
            and (not topic_ids or not tids or set(tids) & set(topic_ids))
        ]
        if not cand:
            return []
        fused: dict[int, float] = {}
        lex = _bm25(tokens(query), cand)
        for rank, i in enumerate(sorted(lex, key=lex.get, reverse=True)[: k * 4]):  # type: ignore[arg-type]
            fused[i] = fused.get(i, 0) + 1 / (60 + rank)
        vec = gateway.embed_texts([query], query=True) if gateway is not None and _Cache.mat is not None else None
        if vec and _Cache.mat is not None and len(vec[0]) == _Cache.mat.shape[1]:
            q = np.asarray(vec[0], dtype=np.float32)
            n = np.linalg.norm(q)
            q = q / n if n else q
            idx = np.array([i for i in cand if _Cache.has_vec[i]], dtype=int)  # type: ignore[index]
            if idx.size:
                sims = _Cache.mat[idx] @ q
                order = np.argsort(-sims)[: k * 4]
                for rank, o in enumerate(order):
                    if sims[o] > 0.2:
                        fused[int(idx[o])] = fused.get(int(idx[o]), 0) + 1 / (60 + rank)
        best = sorted(fused, key=fused.get, reverse=True)[:k]  # type: ignore[arg-type]
        return [
            Hit(_Cache.ids[i], _Cache.meta[i][0], _Cache.titles.get(_Cache.meta[i][0], ""), _Cache.meta[i][1],
                _Cache.meta[i][2], round(fused[i], 5))
            for i in best
        ]
