"""Splits page text into pieces of roughly 900 characters, on paragraph and sentence edges."""
import re

TARGET = 900
OVERLAP = 120


def chunk_pages(pages: list[tuple[int, str]]) -> list[tuple[int, int, str]]:
    """pages: [(page_number, text)] -> [(page_number, index, chunk_text)]"""
    out: list[tuple[int, int, str]] = []
    idx = 0
    for page, text in pages:
        text = re.sub(r"[ \t]+", " ", text or "").strip()
        if not text:
            continue
        paras = [p.strip() for p in re.split(r"\n\s*\n|\n(?=[-•*\d]+[.)]?\s)", text) if p.strip()]
        buf = ""
        for para in paras:
            for piece in _split_long(para):
                if buf and len(buf) + len(piece) + 1 > TARGET:
                    out.append((page, idx, buf.strip()))
                    idx += 1
                    buf = buf[-OVERLAP:] + " " if len(buf) > OVERLAP else ""
                buf += piece + "\n"
        if buf.strip():
            out.append((page, idx, buf.strip()))
            idx += 1
    return out


def _split_long(para: str) -> list[str]:
    if len(para) <= TARGET:
        return [para]
    sentences = re.split(r"(?<=[.!?।])\s+", para)
    pieces, cur = [], ""
    for s in sentences:
        while len(s) > TARGET:  # a sentence with no full stop (tables, lists)
            pieces.append(s[:TARGET])
            s = s[TARGET:]
        if cur and len(cur) + len(s) + 1 > TARGET:
            pieces.append(cur)
            cur = ""
        cur += (" " if cur else "") + s
    if cur:
        pieces.append(cur)
    return pieces
