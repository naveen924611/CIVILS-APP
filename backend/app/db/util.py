from datetime import datetime, timezone


def as_utc(dt: datetime | None) -> datetime | None:
    """SQLite returns naive datetimes; everything in this app is UTC."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def iso(dt: datetime | None) -> str | None:
    d = as_utc(dt)
    return d.isoformat().replace("+00:00", "Z") if d else None
