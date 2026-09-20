"""Job handlers. The tablet queues a job row (type + payload); the server runs the matching handler.

    @job_handler("tutor_question", feature="tutor_answer", notify="answer")
    def run(ctx: JobContext) -> dict:
        ...
        return {"answer": "..."}          # stored in jobs.result_json

Raise `AiUnavailable` when the AI could not answer (the job is retried later), `JobFailed` for a permanent problem.
"""
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from sqlalchemy.orm import Session

from app.db.models_v2 import Job
from app.services import Services


class AiUnavailable(Exception):
    """The AI providers failed or are over budget. The job stays queued and is tried again later."""


class JobFailed(Exception):
    """Permanent failure. The message is shown to the owner (keep it short and plain)."""


@dataclass
class JobContext:
    db: Session
    job: Job
    payload: dict[str, Any]
    services: Services

    @property
    def gateway(self):
        return self.services.gateway

    @property
    def settings(self):
        return self.services.settings


Handler = Callable[[JobContext], dict[str, Any]]


@dataclass
class HandlerInfo:
    fn: Handler
    feature: str  # budget feature name (see app/llm/budget.py); deferrable ones wait when the day's budget is used up
    notify: str  # "" = no notification | "answer" | "feedback" | "ready"


HANDLERS: dict[str, HandlerInfo] = {}

NOTIFY_TEXT = {
    "answer": ("answer", "answers"),
    "feedback": ("feedback", "feedback items"),
    "ready": ("item", "items"),
}


def job_handler(name: str, *, feature: str = "", notify: str = "") -> Callable[[Handler], Handler]:
    def wrap(fn: Handler) -> Handler:
        HANDLERS[name] = HandlerInfo(fn, feature or name, notify)
        return fn

    return wrap
