"""Loads the Telugu practice files (data/telugu/*.yaml) into the `telugu_items` table.

Safe to run at every start-up: an item is found by its stable `key` (the row id is derived from the key), and it is
only written when something changed. Items whose key disappeared from the files are soft-deleted (only when at
least one item was read, so a wrong folder never wipes the table).
"""
import logging
import uuid
from pathlib import Path
from typing import Any

import yaml
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models_v2 import TeluguItem

log = logging.getLogger(__name__)

_NS = uuid.UUID("6f0c2d0e-7a51-4c1b-9d5e-2f6a0b7d3c11")
KINDS = ("vocab", "passage", "translation", "template")
_REQUIRED = {
    "vocab": ("te", "en"),
    "passage": ("title", "text_te", "questions"),
    "translation": ("direction", "reference"),
    "template": ("title_en", "task_en", "structure"),
}


def item_id(key: str) -> str:
    """The same key always gives the same row id (on every server and after a restore)."""
    return str(uuid.uuid5(_NS, f"telugu:{key}"))


def seed_dir(settings: Settings) -> Path:
    """`<folder of feeds.yaml>/telugu` (the mounted data folder), else the repo's data/telugu."""
    beside_feeds = Path(settings.feeds_file).parent / "telugu"
    if beside_feeds.is_dir():
        return beside_feeds
    return Path(__file__).resolve().parents[4] / "data" / "telugu"


def _valid(item: Any) -> str | None:
    """Returns why an item is unusable, or None when it is fine."""
    if not isinstance(item, dict):
        return "not a mapping"
    if item.get("kind") not in KINDS:
        return "unknown kind"
    if not str(item.get("key") or "").strip():
        return "no key"
    for field in _REQUIRED[item["kind"]]:
        if not item.get(field):
            return f"missing {field}"
    if item["kind"] == "translation" and not (item.get("en") or item.get("te")):
        return "missing sentence"
    if item["kind"] == "passage":
        for q in item["questions"]:
            opts = q.get("options") if isinstance(q, dict) else None
            ans = q.get("answer") if isinstance(q, dict) else None
            if not isinstance(opts, list) or not isinstance(ans, int) or not 0 <= ans < len(opts):
                return "bad question"
    return None


def load_items(directory: Path) -> list[dict]:
    """Reads every *.yaml file (sorted by name). Duplicate or broken items are skipped with a warning."""
    items: list[dict] = []
    seen: set[str] = set()
    for path in sorted(directory.glob("*.yaml")):
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as exc:
            log.warning("telugu seed file %s not read: %s", path.name, exc)
            continue
        for raw in data.get("items") or []:
            problem = _valid(raw)
            if problem:
                log.warning("telugu seed item skipped in %s (%s)", path.name, problem)
                continue
            key = str(raw["key"]).strip()
            if key in seen:
                log.warning("telugu seed key %s repeated in %s, skipped", key, path.name)
                continue
            seen.add(key)
            items.append({**raw, "key": key})
    return items


def _content(item: dict) -> dict:
    content = {k: v for k, v in item.items() if k not in ("kind", "level")}
    content["source"] = str(item.get("source") or "general")
    # official = checked against the real syllabus or a past paper; everything else is general practice
    content["official"] = content["source"] in ("syllabus", "pyq")
    content["seeded"] = True
    return content


def seed(db: Session, directory: Path) -> dict[str, int]:
    """Upserts the items. Returns {"added", "updated", "removed", "total"}."""
    items = load_items(directory) if directory.is_dir() else []
    counts = {"added": 0, "updated": 0, "removed": 0, "total": len(items)}
    if not items:
        return counts
    existing = {row.id: row for row in db.scalars(select(TeluguItem))}
    position: dict[str, int] = {}
    wanted: set[str] = set()
    for item in items:
        kind = item["kind"]
        position[kind] = position.get(kind, 0) + 1
        rid = item_id(item["key"])
        wanted.add(rid)
        content, level = _content(item), int(item.get("level") or 1)
        row = existing.get(rid)
        if row is None:
            db.add(TeluguItem(id=rid, kind=kind, level=level, content_json=content, position=position[kind]))
            counts["added"] += 1
        elif (row.kind, row.level, row.content_json, row.position, row.deleted) != (kind, level, content, position[kind], False):
            row.kind, row.level, row.content_json, row.position, row.deleted = kind, level, content, position[kind], False
            counts["updated"] += 1
    for rid, row in existing.items():
        if rid not in wanted and not row.deleted and (row.content_json or {}).get("seeded"):
            row.deleted = True
            counts["removed"] += 1
    db.commit()
    return counts
