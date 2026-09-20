"""Pure text helpers for notes: rendering, de-duplication and the 'Suggested additions' block."""
import re
from typing import Any

SUGGESTED_HEADING = "## Suggested additions"
SUGGESTED_INTRO = "_Proposed from your material. Move what you want into your notes above, then delete this block._"
NO_MATERIAL_TEXT = (
    "Not found in your material yet. Add a book, PDF or scanned pages about this topic in the Library, "
    "then tap Generate notes again."
)
_NORM = re.compile(r"[^a-z0-9]+")


def norm(text: str) -> str:
    return _NORM.sub(" ", (text or "").lower()).strip()


def unique_new(existing: list[str], new: list[str], limit: int | None = None) -> list[str]:
    """Items of `new` whose normalised text is not already in `existing` (or repeated inside `new`)."""
    seen = {norm(x) for x in existing}
    out: list[str] = []
    for item in new:
        key = norm(item)
        if key and key not in seen:
            seen.add(key)
            out.append(item.strip())
    return out[:limit] if limit else out


def render_content_md(sections: dict[str, Any]) -> str:
    """The note body shown on the tablet. Must-remember facts are shown in their own box, so they are not repeated here."""
    parts: list[str] = []
    overview = str(sections.get("overview") or "").strip()
    if overview:
        parts.append("## Overview\n\n" + overview)
    points = [str(p) for p in sections.get("key_points") or []]
    if points:
        parts.append("## Key points\n\n" + "\n".join(f"- {p}" for p in points))
    mains = str(sections.get("mains_angle") or "").strip()
    if mains:
        parts.append("## Mains angle\n\n" + mains)
    return "\n\n".join(parts).strip()


def _split_suggestions(content: str) -> tuple[str, list[str]]:
    """Returns (text before the block, item lines inside it). The block runs from its heading to the next heading."""
    idx = content.find(SUGGESTED_HEADING)
    if idx < 0:
        return content, []
    before = content[:idx].rstrip()
    rest = content[idx + len(SUGGESTED_HEADING):]
    nxt = re.search(r"\n#{1,2} ", rest)
    block = rest[: nxt.start()] if nxt else rest
    after = rest[nxt.start():] if nxt else ""
    items = [ln[2:].strip() for ln in block.splitlines() if ln.startswith("- ")]
    return (before + ("\n\n" + after.strip() if after.strip() else "")).strip(), items


def append_suggestions(content: str, items: list[str]) -> str:
    """Adds proposed items under a marked 'Suggested additions' block at the END of the note.
    The owner's text before the block is never changed. Items already in the note or the block are skipped."""
    body, block_items = _split_suggestions(content)
    fresh = [i for i in unique_new(block_items, items) if norm(i) not in norm(body)]
    if not fresh and not block_items:
        return content
    lines = block_items + fresh
    block = SUGGESTED_HEADING + "\n\n" + SUGGESTED_INTRO + "\n\n" + "\n".join(f"- {i}" for i in lines)
    return (body + "\n\n" + block).strip() + "\n"


def count_suggestions(content: str) -> int:
    return len(_split_suggestions(content)[1])


def short(text: str, n: int) -> str:
    text = re.sub(r"\s+", " ", text or "").strip()
    return text if len(text) <= n else text[: n - 1].rstrip() + "…"


def strip_markdown(md: str) -> str:
    text = re.sub(r"[#>*_`]+", "", md or "")
    return re.sub(r"\s+", " ", text).strip()


_CLOZE = [
    re.compile(r"\bArt(?:icle)?s?\.?\s?\d+[A-Z]?(?:\(\d+\))?", re.IGNORECASE),
    re.compile(r"\b\d+(?:st|nd|rd|th)\s+(?:Constitutional\s+)?Amendment\b", re.IGNORECASE),
    re.compile(r"\b(?:1[5-9]|20)\d{2}(?:-\d{2,4})?\b"),
    re.compile(r"\b\d[\d,.]*\s?(?:%|per cent|percent|crore|lakh|km|sq\.? ?km|hectares?|years?|members?)", re.IGNORECASE),
]


def make_cloze(text: str, topic_title: str = "") -> tuple[str, str]:
    """A flashcard (front, back) from one highlighted fact. The first article, amendment, year or number is hidden;
    with none of those the front asks the owner to recall the point."""
    fact = re.sub(r"\s+", " ", text or "").strip()
    for pattern in _CLOZE:
        m = pattern.search(fact)
        if m and m.group(0).strip():
            hidden = m.group(0).strip()
            return fact[: m.start()] + "_____" + fact[m.end():], f"{hidden}\n\n{fact}"
    lead = topic_title.strip() or "this topic"
    return f"Recall this point about {lead}: {short(fact, 60)}", fact
