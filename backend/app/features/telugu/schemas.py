"""What the AI must return for Telugu practice feedback."""
from pydantic import BaseModel, Field, field_validator


def _strings(v):
    if v is None:
        return []
    if isinstance(v, str):
        return [v] if v.strip() else []
    return [str(x).strip() for x in v if str(x).strip()]


class Correction(BaseModel):
    said: str = ""
    better: str = ""
    why: str = ""


class FeedbackOut(BaseModel):
    score: float = 0.0
    summary: str = ""
    strengths: list[str] = Field(default_factory=list)
    corrections: list[Correction] = Field(default_factory=list)
    model_answer: str = ""

    _lists = field_validator("strengths", mode="before")(_strings)

    @field_validator("score", mode="before")
    @classmethod
    def _score(cls, v):
        try:
            value = float(v)
        except (TypeError, ValueError):
            return 0.0
        return max(0.0, min(10.0, value))
