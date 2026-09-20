from typing import Any

from pydantic import BaseModel, Field, field_validator


class McqOut(BaseModel):
    question: str = Field(min_length=5, max_length=500)
    options: list[str] = Field(min_length=4, max_length=4)
    answer_index: int = Field(ge=0, le=3)
    explanation: str = Field(default="", max_length=600)


class CardOut(BaseModel):
    front: str = Field(min_length=3, max_length=400)
    back: str = Field(min_length=1, max_length=600)


def _strings(limit: int):
    def clean(value: Any) -> Any:
        if not isinstance(value, list):
            return value
        out = [str(v).strip() for v in value if v is not None and str(v).strip()]
        return out[:limit]

    return clean


class NoteOut(BaseModel):
    """What the AI returns for generate / merge / fix. Lists are trimmed rather than rejected; broken MCQs are dropped."""

    found: bool = True
    overview: str = ""
    key_points: list[str] = Field(default_factory=list)
    must_remember: list[str] = Field(default_factory=list)
    mains_angle: str = ""
    cards: list[CardOut] = Field(default_factory=list)
    mcqs: list[McqOut] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)
    summary: str = ""
    correction: str = ""

    _kp = field_validator("key_points", mode="before")(_strings(15))
    _mr = field_validator("must_remember", mode="before")(_strings(10))
    _kw = field_validator("keywords", mode="before")(_strings(12))

    @field_validator("overview", "mains_angle", "summary", "correction", mode="before")
    @classmethod
    def _text(cls, v: Any) -> Any:
        return "" if v is None else v

    @field_validator("cards", mode="before")
    @classmethod
    def _cards(cls, v: Any) -> Any:
        if not isinstance(v, list):
            return v
        ok = [c for c in v if isinstance(c, dict) and str(c.get("front", "")).strip() and str(c.get("back", "")).strip()]
        return ok[:8]

    @field_validator("mcqs", mode="before")
    @classmethod
    def _mcqs(cls, v: Any) -> Any:
        if not isinstance(v, list):
            return v
        ok = []
        for q in v:
            if (isinstance(q, dict) and isinstance(q.get("options"), list) and len(q["options"]) == 4
                    and isinstance(q.get("answer_index"), int) and 0 <= q["answer_index"] <= 3
                    and len(str(q.get("question", ""))) >= 5):
                ok.append(q)
        return ok[:5]
