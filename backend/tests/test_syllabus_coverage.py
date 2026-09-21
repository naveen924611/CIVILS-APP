"""The coverage files (data/exam-specs/coverage/*.json) list every official syllabus line and the outline node that covers it.
This test fails when a listed node is missing from the outline, when an official line has no text or page, or when a paper of
the outline is not covered by any line."""
import json
from pathlib import Path

import pytest

from app.features.syllabus.trees import norm

DATA = Path(__file__).resolve().parents[2] / "data"
COVERAGE = sorted((DATA / "exam-specs" / "coverage").glob("*.json"))


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _resolve(tree: list[dict], path: list[str]) -> dict | None:
    """Walks down the tree matching each title (ignoring case and punctuation); None when a step is missing."""
    nodes, node = tree, None
    for title in path:
        node = next((n for n in nodes if norm(n["title"]) == norm(title)), None)
        if node is None:
            return None
        nodes = node.get("children", [])
    return node


def test_coverage_files_exist():
    assert len(COVERAGE) >= 3
    assert {p.stem for p in COVERAGE} >= {"appsc-g1-prelims-2026", "appsc-g1-mains-2026", "slprb-si-written"}


@pytest.mark.parametrize("path", COVERAGE, ids=lambda p: p.stem)
def test_every_official_line_maps_to_an_outline_node(path):
    cov = _load(path)
    assert cov["key"] == path.stem and cov["items"], "the file needs its key and items"
    outline_path = DATA / "syllabus" / cov["outline_file"]
    assert outline_path.is_file(), f"outline {cov['outline_file']} not found"
    outline = _load(outline_path)
    assert outline["key"] == cov["key"], "the coverage file and its outline share one key"
    assert outline.get("verified") is True, f"{cov['outline_file']} is not marked verified"
    tree = outline["tree"]
    missing = []
    for i, item in enumerate(cov["items"]):
        official = item.get("official")
        assert isinstance(official, str) and official.strip(), f"item {i}: 'official' must be a non-empty string"
        assert isinstance(item.get("page"), int) and not isinstance(item["page"], bool), f"item {i} ({official[:40]}): 'page' must be an int"
        steps = item.get("path")
        assert isinstance(steps, list) and steps and all(isinstance(s, str) and s.strip() for s in steps), f"item {i}: 'path' must be a list of titles"
        if _resolve(tree, steps) is None:
            missing.append(f"{official[:60]!r} -> {' > '.join(steps)}")
    assert not missing, f"{len(missing)} coverage line(s) point at nodes that are not in the outline:\n" + "\n".join(missing[:15])


@pytest.mark.parametrize("path", COVERAGE, ids=lambda p: p.stem)
def test_every_paper_of_the_outline_is_covered(path):
    cov = _load(path)
    outline = _load(DATA / "syllabus" / cov["outline_file"])
    used = {norm(item["path"][0]) for item in cov["items"] if item.get("path")}
    uncovered = [paper["title"] for paper in outline["tree"] if norm(paper["title"]) not in used]
    assert not uncovered, f"papers with no official line pointing at them: {uncovered}"


def test_resolver_finds_and_rejects_paths():
    tree = [{"title": "Paper I - GS", "children": [{"title": "A. History", "children": [{"title": "Vedic Age", "children": []}]}]}]
    assert _resolve(tree, ["paper i gs", "A History", "vedic age"])["title"] == "Vedic Age"
    assert _resolve(tree, ["Paper I - GS", "B. Geography"]) is None and _resolve(tree, ["Nope"]) is None
