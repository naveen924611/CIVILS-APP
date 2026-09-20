"""Pure helpers for syllabus trees (no database). A tree is a list of nested dicts:

    {"title", "level" (0 paper, 1 subject, 2 topic, 3 subtopic), "exam_tags", "est_hours", "importance", "children": [...]}
"""
import re
from typing import Any

MAX_LEVEL = 3
_NORM = re.compile(r"[^a-z0-9]+")


def norm(text: str) -> str:
    """Lower case, letters and digits only: used to find 'the same title'."""
    return _NORM.sub(" ", (text or "").lower()).strip()


def exam_tags_for(exam: str) -> list[str]:
    low = (exam or "").lower()
    tags = []
    if "upsc" in low:
        tags.append("UPSC")
    if "appsc" in low:
        tags.append("APPSC")
    if not tags and any(w in low for w in ("both", "combined", "common")):
        tags = ["APPSC", "UPSC"]
    return tags


def _num(value: Any, default: float | None) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def clean_tree(nodes: Any, depth: int = 0, inherit_tags: list[str] | None = None) -> list[dict]:
    """Makes any tree-like input safe: strips empty titles, sets level from depth, caps level at 3, merges twin siblings."""
    out: list[dict] = []
    if not isinstance(nodes, list):
        return out
    for raw in nodes:
        if not isinstance(raw, dict):
            continue
        title = re.sub(r"\s+", " ", str(raw.get("title") or "")).strip()[:300]
        if not title:
            continue
        tags = [str(t).strip().upper() for t in (raw.get("exam_tags") or []) if str(t).strip()]
        tags = [t for t in dict.fromkeys(tags) if t in ("UPSC", "APPSC")] or list(inherit_tags or [])
        hours = _num(raw.get("est_hours"), None)
        imp = _num(raw.get("importance"), None)
        node = {
            "title": title,
            "level": min(depth, MAX_LEVEL),
            "exam_tags": tags,
            "est_hours": max(0.0, min(hours, 500.0)) if hours is not None else None,
            "importance": max(0.0, min(imp, 10.0)) if imp is not None else None,
            "children": clean_tree(raw.get("children"), depth + 1, tags),
        }
        twin = next((n for n in out if norm(n["title"]) == norm(title)), None)
        if twin is not None:
            twin["children"] = merge_trees(twin["children"], node["children"])
            twin["exam_tags"] = list(dict.fromkeys(twin["exam_tags"] + node["exam_tags"]))
        else:
            out.append(node)
    return out


def merge_trees(a: list[dict], b: list[dict]) -> list[dict]:
    """Adds the nodes of b to a. Nodes with the same title are merged (children merged too)."""
    result = [dict(n, children=list(n.get("children", []))) for n in a]
    for node in b:
        twin = next((n for n in result if norm(n["title"]) == norm(node["title"])), None)
        if twin is None:
            result.append(dict(node, children=list(node.get("children", []))))
            continue
        twin["children"] = merge_trees(twin["children"], node.get("children", []))
        twin["exam_tags"] = list(dict.fromkeys(list(twin.get("exam_tags", [])) + list(node.get("exam_tags", []))))
        for key in ("est_hours", "importance"):
            if twin.get(key) is None and node.get(key) is not None:
                twin[key] = node[key]
    return result


def count_nodes(nodes: list[dict]) -> int:
    return sum(1 + count_nodes(n.get("children", [])) for n in nodes)


def count_leaves(nodes: list[dict]) -> int:
    return sum(count_leaves(n["children"]) if n.get("children") else 1 for n in nodes)


def filter_tree(nodes: list[dict], exam: str | None, inherited: list[str] | None = None) -> list[dict]:
    """Keeps nodes tagged with `exam` (untagged nodes inherit their parent's tags), and the ancestors of kept nodes."""
    if not exam:
        return nodes
    want = exam.upper()
    out = []
    for n in nodes:
        tags = n.get("exam_tags") or inherited or []
        kids = filter_tree(n.get("children", []), exam, tags)
        if want in tags or kids:
            out.append(dict(n, children=kids))
    return out


_PAPER_HEAD = re.compile(
    r"^\s*(?:paper|section|part|general studies|gs)[\s\-:]*(?:[ivx]+|\d+|[a-d])\b.*$", re.IGNORECASE
)


def split_papers(text: str) -> list[str]:
    """Splits pasted syllabus text at lines that look like a paper heading ('Paper II', 'GS-3', 'Section A')."""
    lines = text.replace("\r\n", "\n").split("\n")
    pieces: list[list[str]] = [[]]
    for line in lines:
        if _PAPER_HEAD.match(line) and len(line) < 200 and any(x.strip() for x in pieces[-1]):
            pieces.append([])
        pieces[-1].append(line)
    return ["\n".join(p).strip() for p in pieces if "".join(p).strip()]


def chunk_text(text: str, max_chars: int = 6000) -> list[str]:
    """Chunks per paper first, then by size on paragraph boundaries so a very long paper is not cut mid-sentence."""
    chunks: list[str] = []
    for piece in split_papers(text):
        if len(piece) <= max_chars:
            chunks.append(piece)
            continue
        current = ""
        for para in re.split(r"\n\s*\n|\n(?=\s*(?:[-*•]|\d+[.)]))", piece):
            if current and len(current) + len(para) + 1 > max_chars:
                chunks.append(current.strip())
                current = ""
            while len(para) > max_chars:  # one enormous line
                chunks.append(para[:max_chars])
                para = para[max_chars:]
            current += para + "\n"
        if current.strip():
            chunks.append(current.strip())
    return chunks


COVERED = frozenset({"studied", "revised", "strong"})


def nest_topics(rows: list[dict]) -> list[dict]:
    """Flat topic rows (dicts with id, parent_id, title, position) -> nested tree with `children`. Orphans become roots."""
    ids = {r["id"] for r in rows}
    by_parent: dict[str | None, list[dict]] = {}
    for r in rows:
        parent = r.get("parent_id")
        by_parent.setdefault(parent if parent in ids else None, []).append(r)

    def build(parent_id: str | None, seen: frozenset) -> list[dict]:
        kids = sorted(by_parent.get(parent_id, []), key=lambda r: (r.get("position") or 0, r.get("title") or ""))
        return [dict(r, children=build(r["id"], seen | {r["id"]})) for r in kids if r["id"] not in seen]

    return build(None, frozenset())


def add_coverage(nodes: list[dict]) -> tuple[int, int]:
    """Adds `leaves` and `coverage` (0-100, share of leaf topics studied or better) to every node. Returns (covered, leaves)."""
    total_covered = total_leaves = 0
    for n in nodes:
        if n.get("children"):
            covered, leaves = add_coverage(n["children"])
        else:
            leaves, covered = 1, 1 if n.get("status") in COVERED else 0
        n["leaves"] = leaves
        n["coverage"] = round(100.0 * covered / leaves) if leaves else 0
        total_covered += covered
        total_leaves += leaves
    return total_covered, total_leaves
