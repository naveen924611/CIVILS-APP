"""The tablet's Kotlin models must know every column the server sends (android/.../data/model/Models.kt)."""
import re
from pathlib import Path

from app.sync.registry import sync_tables

ANDROID = Path(__file__).resolve().parents[2] / "android/app/src/main/java/com/naveen/civilscompanion/data"


def kotlin_models() -> dict[str, set[str]]:
    text = (ANDROID / "model/Models.kt").read_text(encoding="utf-8")
    out: dict[str, set[str]] = {}
    for m in re.finditer(r"data class (\w+)\((.*?)\n\)", text, re.S):
        keys = set()
        for line in m.group(2).splitlines():
            line = line.strip()
            sn = re.search(r'@SerialName\("([a-z_0-9]+)"\)', line)
            if sn:
                keys.add(sn.group(1))
            else:
                plain = re.match(r"val (\w+):", line)
                if plain:
                    keys.add(plain.group(1))
        out[m.group(1)] = keys
    return out


def kotlin_tables() -> dict[str, str]:
    text = (ANDROID / "records/Tables.kt").read_text(encoding="utf-8")
    return dict(re.findall(r'Table\("([a-z_]+)", (\w+)\.serializer\(\)', text))


def test_every_synced_table_has_a_kotlin_table_and_model():
    tables = kotlin_tables()
    assert set(tables) == set(sync_tables())


def test_kotlin_models_cover_every_server_column():
    models = kotlin_models()
    tables = kotlin_tables()
    missing = {}
    for name, model in sync_tables().items():
        cols = {c.key for c in model.__table__.columns}
        known = models[tables[name]]
        gap = cols - known
        if gap:
            missing[name] = sorted(gap)
    assert not missing, f"Kotlin models lack columns: {missing}"


def test_kotlin_index_keys_exist_on_the_server_table():
    text = (ANDROID / "records/Tables.kt").read_text(encoding="utf-8")
    for m in re.finditer(r'Table\("([a-z_]+)", .*?\)\n', text):
        name, line = m.group(1), m.group(0)
        cols = {c.key for c in sync_tables()[name].__table__.columns}
        for key in re.findall(r'(?:k1|k2|n1) = "([a-z_]+)"', line):
            assert key in cols, f"{name}: index key {key} is not a column"
        for group in re.findall(r"text = listOf\(([^)]*)\)", line):
            for key in re.findall(r'"([a-z_]+)"', group):
                assert key in cols, f"{name}: text key {key} is not a column"
