"""Strict shapes for what the LLM must return. Anything that does not fit is rejected and retried once."""
from pydantic import BaseModel, Field, field_validator, model_validator


class Fact(BaseModel):
    q: str = Field(min_length=3, max_length=300)
    a: str = Field(min_length=1, max_length=300)


class Mcq(BaseModel):
    question: str = Field(min_length=5, max_length=500)
    options: list[str] = Field(min_length=4, max_length=4)
    answer_index: int = Field(ge=0, le=3)
    explanation: str = Field(default="", max_length=500)


def _clamp10(v):
    try:
        return max(0, min(10, int(round(float(v)))))
    except (TypeError, ValueError):
        return v  # let validation report it


class NewsItemOut(BaseModel):
    title: str = Field(min_length=5, max_length=300)
    summary: str
    relevance_upsc: int
    relevance_appsc: int
    papers: list[str] = Field(default_factory=list, max_length=8)
    prelims_facts: list[Fact] = Field(default_factory=list, max_length=5)
    mains_angle: str = Field(default="", max_length=600)
    keywords: list[str] = Field(default_factory=list, max_length=10)
    is_ap_specific: bool = False
    mcqs: list[Mcq] = Field(default_factory=list, max_length=3)

    _clamp = field_validator("relevance_upsc", "relevance_appsc", mode="before")(_clamp10)

    @model_validator(mode="after")
    def _summary_length(self):
        words = len(self.summary.split())
        if not 30 <= words <= 320:
            raise ValueError(f"summary has {words} words; it must be 30 to 320 (aim for the requested length)")
        return self
