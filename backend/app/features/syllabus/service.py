"""Seeding starter outlines, and turning an approved SyllabusImport into Topic rows."""
import json
import logging
import os
import uuid
from pathlib import Path
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models_v2 import SyllabusImport, Topic
from app.features.notes import service as notes_service
from app.features.syllabus import importance
from app.features.syllabus.trees import clean_tree, count_nodes, exam_tags_for, filter_tree, norm

log = logging.getLogger(__name__)
SEED_NAMESPACE = uuid.UUID("6f0d3c1e-5b7a-4c1f-9a55-2f4d6d1a7b10")


def syllabus_dir(settings: Settings) -> Path:
    """data/syllabus next to feeds.yaml (the server container mounts ./data at /app/config). SYLLABUS_DIR overrides."""
    override = os.environ.get("SYLLABUS_DIR")
    if override:
        return Path(override)
    return Path(settings.feeds_file).resolve().parent / "syllabus"


def seed_id(key: str) -> str:
    return str(uuid.uuid5(SEED_NAMESPACE, key))


def load_starters(settings: Settings) -> list[dict]:
    folder = syllabus_dir(settings)
    out = []
    if not folder.is_dir():
        return out
    for path in sorted(folder.glob("*.json")):
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            log.warning("syllabus file %s skipped: %s", path.name, exc)
            continue
        if isinstance(doc, dict) and doc.get("key") and isinstance(doc.get("tree"), list):
            out.append(doc)
    return out


def seed_starters(db: Session, settings: Settings) -> int:
    """Adds each starter outline once, as a pending SyllabusImport with a stable id. Safe to run at every start-up.
    Never touches an import that already exists (pending, approved, deleted or edited by the owner). Returns how many were added."""
    added = 0
    for doc in load_starters(settings):
        row_id = seed_id(str(doc["key"]))
        if db.get(SyllabusImport, row_id) is not None:
            continue
        note = "Starter outline"
        if not doc.get("verified"):
            note += " (not checked against the official syllabus: please compare before approving)"
        db.add(SyllabusImport(
            id=row_id, exam=str(doc.get("exam", "")), title=str(doc.get("title", doc["key"])),
            status="pending", tree_json=clean_tree(doc["tree"]), note=note[:300],
        ))
        added += 1
    if added:
        db.commit()
    return added


def _find_existing(db: Session, parent_id: str | None, level: int, title: str, syllabus_id: str, merge: bool) -> Topic | None:
    q = select(Topic).where(Topic.deleted.is_(False), Topic.level == level)
    q = q.where(Topic.parent_id.is_(None)) if parent_id is None else q.where(Topic.parent_id == parent_id)
    want = norm(title)
    for row in db.scalars(q):
        if norm(row.title) != want:
            continue
        if row.syllabus_id == syllabus_id or merge:
            return row
    return None


def approve(db: Session, imp: SyllabusImport, exam_filter: str | None = None, merge: bool = True,
            tree: Any = None) -> dict:
    """Creates (or merges into) approved Topic rows. Running it again changes nothing."""
    nodes = clean_tree(tree if tree is not None else imp.tree_json)
    default_tags = exam_tags_for(imp.exam)
    nodes = clean_tree(_with_tags(nodes, default_tags))
    if tree is not None:
        imp.tree_json = nodes  # keep the owner's edits with the import
    if exam_filter:
        nodes = filter_tree(nodes, exam_filter)
    stats = {"created": 0, "merged": 0}
    priors: dict[str, float] = {}
    touched: list[str] = []

    def walk(children: list[dict], parent: Topic | None, paper: str) -> None:
        for pos, node in enumerate(children):
            level = int(node["level"])
            row = _find_existing(db, parent.id if parent else None, level, node["title"], imp.id, merge)
            paper_name = node["title"] if level == 0 else paper
            tags = list(node.get("exam_tags") or default_tags)
            if row is None:
                row = Topic(
                    parent_id=parent.id if parent else None, syllabus_id=imp.id, title=node["title"], level=level,
                    paper=paper_name, exam_tags=tags, est_hours=node["est_hours"] if node["est_hours"] is not None else 1.0,
                    approved=True, position=pos, importance=node["importance"] if node["importance"] is not None else 0.0,
                )
                db.add(row)
                db.flush()
                stats["created"] += 1
            else:
                merged_tags = list(dict.fromkeys(list(row.exam_tags or []) + tags))
                if merged_tags != list(row.exam_tags or []):
                    row.exam_tags = merged_tags
                if not row.approved:
                    row.approved = True
                if not row.paper:
                    row.paper = paper_name
                stats["merged"] += 1
            if level >= 1:
                touched.append(row.id)
            if node["importance"] is not None:
                priors.setdefault(row.id, node["importance"])
            walk(node.get("children", []), row, paper_name)

    walk(nodes, None, "")
    notes_service.ensure_notes(db, touched)  # every topic gets its (empty) note row: the tablet edits notes, it cannot create them
    imp.status = "approved"
    imp.note = f"Approved: {stats['created']} new, {stats['merged']} already there."[:300]
    db.commit()
    importance.remember_priors(db, priors)
    importance.recompute(db)
    return {"import_id": imp.id, "status": imp.status, "total": count_nodes(nodes), **stats}


def _with_tags(nodes: list[dict], tags: list[str]) -> list[dict]:
    """Nodes with no tags get the exam's tags (so 'UPSC CSE' topics are tagged UPSC)."""
    out = []
    for n in nodes:
        own = list(n.get("exam_tags") or tags)
        out.append(dict(n, exam_tags=own, children=_with_tags(n.get("children", []), own)))
    return out
