"""Shapes the AI must return for explain-back feedback, handwritten answer evaluation and question setting."""
from pydantic import BaseModel, Field, field_validator


def _strings(v):
    if v is None:
        return []
    if isinstance(v, str):
        return [v]
    return [str(x).strip() for x in v if str(x).strip()]


class Correction(BaseModel):
    said: str = ""
    correct: str = ""


class ExplainOut(BaseModel):
    covered: list[str] = Field(default_factory=list)
    missed: list[str] = Field(default_factory=list)
    needs_correcting: list[Correction] = Field(default_factory=list)
    model_explanation: str = ""
    general_knowledge: bool = False

    _lists = field_validator("covered", "missed", mode="before")(_strings)


class Structure(BaseModel):
    intro: str = ""
    body: str = ""
    conclusion: str = ""


class EvalOut(BaseModel):
    readable: bool = True
    transcript: str = ""
    structure: Structure = Field(default_factory=Structure)
    content_coverage: str = ""
    examples_data: str = ""
    word_limit_comment: str = ""
    presentation: str = ""
    strengths: list[str] = Field(default_factory=list)
    improvements: list[str] = Field(default_factory=list)
    model_outline: list[str] = Field(default_factory=list)
    score: float = 0.0

    _lists = field_validator("strengths", "improvements", "model_outline", mode="before")(_strings)

    @field_validator("score", mode="before")
    @classmethod
    def _clamp(cls, v):
        try:
            return round(max(0.0, min(10.0, float(v))), 1)
        except (TypeError, ValueError):
            return 0.0


class QuestionOut(BaseModel):
    question: str = Field(min_length=10, max_length=800)
    word_limit: int = 250

    @field_validator("word_limit", mode="before")
    @classmethod
    def _limit(cls, v):
        try:
            n = int(v)
        except (TypeError, ValueError):
            return 250
        return n if 50 <= n <= 1500 else 250
