"""Which tables are synced with the tablet, found by looking for models that use SyncMixin."""
from functools import lru_cache

from app.db import models  # noqa: F401  (loads every table, including models_v2)
from app.db.base import Base
from app.db.sync_mixin import SyncMixin


def _all_subclasses(cls):
    for sub in cls.__subclasses__():
        yield sub
        yield from _all_subclasses(sub)


@lru_cache
def _cached() -> tuple:
    return tuple(m for m in _all_subclasses(Base) if issubclass(m, SyncMixin) and m.sync_name)


def sync_tables() -> dict[str, type[SyncMixin]]:
    """name -> model. Feature modules that add their own SyncMixin models must be imported before first use."""
    found = {m.sync_name: m for m in _cached()}
    return dict(sorted(found.items()))


def refresh() -> None:
    _cached.cache_clear()
