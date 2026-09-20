"""Tutor (M6): answers the owner's questions from his own material, with sources.

The tablet queues a `tutor_question` job (works offline); the handler in `jobs.py` answers it and writes the
assistant ChatMessage row, which syncs back to the tablet. There are no HTTP routes.
"""
from . import jobs  # noqa: F401  (importing registers the job handler)
