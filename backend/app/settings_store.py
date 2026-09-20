"""Small key/value settings kept in the database (brief times, etc.)."""
import re
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator
from sqlalchemy.orm import Session

from app.db.models import Setting

DAYS = [0, 1, 2, 3, 4, 5, 6]  # Monday = 0


class BriefSlot(BaseModel):
    id: Literal["morning", "evening", "extra1", "extra2"]
    time: str
    enabled: bool = True
    days: list[int] = Field(default_factory=lambda: list(DAYS))

    @field_validator("time")
    @classmethod
    def _time(cls, v: str) -> str:
        if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", v):
            raise ValueError("time must look like 07:00 (24-hour)")
        return v

    @field_validator("days")
    @classmethod
    def _days(cls, v: list[int]) -> list[int]:
        if any(d not in DAYS for d in v):
            raise ValueError("days must be 0 (Monday) to 6 (Sunday)")
        return sorted(set(v))


class BriefSettings(BaseModel):
    briefs: list[BriefSlot]

    @model_validator(mode="after")
    def _check(self):
        ids = [b.id for b in self.briefs]
        if len(ids) != len(set(ids)):
            raise ValueError("each brief may appear once")
        if "morning" not in ids or "evening" not in ids:
            raise ValueError("morning and evening briefs must both be present (they can be switched off)")
        return self


DEFAULT_BRIEFS = BriefSettings(
    briefs=[BriefSlot(id="morning", time="07:00"), BriefSlot(id="evening", time="19:00")]
)


def get_brief_settings(db: Session) -> BriefSettings:
    row = db.get(Setting, "briefs")
    if row is None:
        return DEFAULT_BRIEFS
    try:
        return BriefSettings.model_validate(row.value_json)
    except ValueError:
        return DEFAULT_BRIEFS


def set_brief_settings(db: Session, value: BriefSettings) -> None:
    row = db.get(Setting, "briefs")
    data = value.model_dump()
    if row is None:
        db.add(Setting(key="briefs", value_json=data))
    else:
        row.value_json = data
    db.commit()
