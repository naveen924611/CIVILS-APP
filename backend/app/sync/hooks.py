"""Server-side reactions to rows pushed from the tablet.

    @on_push("highlights")
    def made_highlight(db, row, is_new): ...

Hooks run inside the push request, before commit. Keep them quick (queue a job for anything slow).
"""
from collections.abc import Callable
from typing import Any

from sqlalchemy.orm import Session

Hook = Callable[[Session, Any, bool], None]
HOOKS: dict[str, list[Hook]] = {}


def on_push(table: str) -> Callable[[Hook], Hook]:
    def wrap(fn: Hook) -> Hook:
        HOOKS.setdefault(table, []).append(fn)
        return fn

    return wrap
