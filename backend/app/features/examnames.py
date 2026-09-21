"""Matching an exam tag or key (UPSC, APPSC, SI, ...) against an exam name, by whole word.

A plain substring test would find 'si' inside 'Mission' or 'Basic'. Boundaries here are anything that is not a letter or
a digit, so 'SI' is in 'SLPRB SI (Civil)' and 'UPSC' is in 'UPSC CSE' and 'UPSC-CSE'.
"""
import re


def has_word(text: str, word: str) -> bool:
    """True when `word` occurs in `text` as a whole word (case-insensitive). An empty word never matches."""
    w = (word or "").strip().lower()
    if not w:
        return False
    return re.search(rf"(?<![a-z0-9]){re.escape(w)}(?![a-z0-9])", (text or "").lower()) is not None


def is_si_exam(name: str) -> bool:
    """True for the AP SLPRB Sub-Inspector exam: the name has the whole word 'slprb' or 'si'."""
    return has_word(name, "slprb") or has_word(name, "si")
