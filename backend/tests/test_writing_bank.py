"""The writing prompt bank: the tablet ships a byte-identical copy, and every prompt has what the picker needs."""
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "data" / "writing" / "si_and_group1_prompts.json"
ASSET = REPO / "android" / "app" / "src" / "main" / "assets" / "writing_prompts.json"
FORMS = {
    "essay", "letter", "application", "precis", "report", "press_release", "speech", "paragraph",
    "translation_en_te", "translation_te_en", "comprehension", "visual_information", "verse_meaning", "word_meanings",
}


def test_asset_copy_is_identical():
    assert ASSET.read_bytes() == DATA.read_bytes(), "run: cp data/writing/si_and_group1_prompts.json android/app/src/main/assets/writing_prompts.json"


def test_every_prompt_is_complete():
    items = json.loads(DATA.read_text(encoding="utf-8"))["items"]
    keys = [i["key"] for i in items]
    assert len(keys) == len(set(keys)) >= 90
    for i in items:
        assert i["exam"] in ("SI", "APPSC", "BOTH") and i["language"] in ("en", "te") and i["form"] in FORMS
        assert len(i["prompt"].strip()) > 20 and int(i["word_limit"]) > 0 and int(i["marks"]) > 0
        if i["language"] == "te":
            assert any("ఀ" <= ch <= "౿" for ch in i["prompt"]), i["key"]
