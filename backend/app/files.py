"""Where uploaded and generated files live (all under DATA_DIR)."""
import re
from pathlib import Path

from app.config import Settings


def data_path(settings: Settings, *parts: str) -> Path:
    base = Path(settings.data_dir).resolve()
    path = base.joinpath(*parts).resolve()
    if base not in path.parents and path != base:
        raise ValueError("path escapes the data folder")
    return path


def uploads_dir(settings: Settings) -> Path:
    path = data_path(settings, "uploads")
    path.mkdir(parents=True, exist_ok=True)
    return path


def safe_name(name: str, default: str = "file") -> str:
    name = Path(name).name
    name = re.sub(r"[^A-Za-z0-9._ -]+", "_", name).strip(" .")
    return (name or default)[:120]
