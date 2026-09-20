"""Shared behaviour of every table that is synced with the tablet.

Every synced row has id (UUID text), updated_at (UTC) and deleted (soft delete).
`push_fields` lists the columns the tablet is allowed to write. None means the table is server-owned and
pushes are refused (the tablet only reads it). `id` and `updated_at` are handled by the sync engine.
"""
from datetime import datetime, timezone
from typing import Any, ClassVar

from sqlalchemy import DateTime

from app.db.util import as_utc, iso


class SyncMixin:
    sync_name: ClassVar[str] = ""
    push_fields: ClassVar[frozenset[str] | None] = None
    # Rows the tablet should not receive (return a SQLAlchemy condition or None)
    pull_hidden: ClassVar[Any] = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        for col in self.__table__.columns:  # type: ignore[attr-defined]
            value = getattr(self, col.key)
            if isinstance(value, datetime):
                value = iso(value)
            out[col.key] = value
        return out

    @classmethod
    def coerce(cls, key: str, value: Any) -> Any:
        """Turns JSON values from the tablet into what the column expects (ISO text -> datetime)."""
        col = cls.__table__.columns[key]  # type: ignore[attr-defined]
        if isinstance(col.type, DateTime) and isinstance(value, str):
            text = value.replace("Z", "+00:00")
            return as_utc(datetime.fromisoformat(text))
        return value

    def apply_push(self, data: dict[str, Any]) -> None:
        allowed = self.push_fields or frozenset()
        for key in allowed:
            if key in data:
                setattr(self, key, self.coerce(key, data[key]))
        if "deleted" in data:
            self.deleted = bool(data["deleted"])


def utcnow() -> datetime:
    return datetime.now(timezone.utc)
