"""Shapes the AI must return for questions. They are lenient on purpose: every question is checked one by one
(see generate.validate_mcq) so one bad question does not throw the whole batch away."""
from pydantic import BaseModel, Field, field_validator

LETTERS = {"a": 0, "b": 1, "c": 2, "d": 3}


def _index(value):
    """Accepts 2, "2", "C", "c)" or "(c)" as an answer position; anything else is left to fail validation."""
    if isinstance(value, str):
        text = value.strip().strip("()").strip(". )").lower()
        if text in LETTERS:
            return LETTERS[text]
        if text.lstrip("-").isdigit():
            return int(text)
    return value


class GenMcq(BaseModel):
    question: str = ""
    options: list[str] = Field(default_factory=list)
    answer_index: int = -1
    explanation: str = ""
    topic_ref: int = 1  # which numbered material block the question came from

    _fix_index = field_validator("answer_index", mode="before")(_index)


class McqBatch(BaseModel):
    questions: list[GenMcq] = Field(default_factory=list)


class PyqItem(BaseModel):
    number: int | None = None  # the question number as printed in the paper
    question: str = ""
    options: list[str] = Field(default_factory=list)
    answer_index: int | None = None  # from the answer key when it is in the text, else null

    _fix_index = field_validator("answer_index", mode="before")(_index)


class PyqBatch(BaseModel):
    questions: list[PyqItem] = Field(default_factory=list)
