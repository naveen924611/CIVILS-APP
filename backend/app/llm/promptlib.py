"""Prompts live in versioned files (app/llm/prompts/<name>.system.md and .user.md)."""
from pathlib import Path

PROMPT_DIR = Path(__file__).parent / "prompts"


def load(name: str) -> tuple[str, str]:
    return (
        (PROMPT_DIR / f"{name}.system.md").read_text(encoding="utf-8"),
        (PROMPT_DIR / f"{name}.user.md").read_text(encoding="utf-8"),
    )


def render(template: str, **values: str) -> str:
    for key, val in values.items():
        template = template.replace("{{" + key + "}}", str(val))
    return template
