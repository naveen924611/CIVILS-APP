"""Handwritten answer evaluation (Gemini vision) and the shape of the stored feedback."""
import io
import logging
from pathlib import Path

from app.config import Settings
from app.db.models_v2 import AnswerSubmission
from app.files import data_path
from app.llm import promptlib

from .schemas import EvalOut

log = logging.getLogger(__name__)
PROMPT = "answers_eval_v1"
MAX_PAGES = 8
MAX_SIDE = 1600
_MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}


def load_images(settings: Settings, image_paths: list[str]) -> list[tuple[str, bytes]]:
    """Reads the saved photos (relative to DATA_DIR/uploads), shrinks them and returns (mime, bytes) pairs."""
    images: list[tuple[str, bytes]] = []
    for rel in image_paths[:MAX_PAGES]:
        try:
            path = data_path(settings, "uploads", rel)
            raw = path.read_bytes()
        except (OSError, ValueError):
            log.warning("answer photo missing: %s", rel)
            continue
        images.append(shrink(raw, _MIME.get(Path(rel).suffix.lower(), "image/jpeg")))
    return images


def shrink(raw: bytes, mime: str) -> tuple[str, bytes]:
    try:
        from PIL import Image, ImageOps

        with Image.open(io.BytesIO(raw)) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            im.thumbnail((MAX_SIDE, MAX_SIDE))
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=82)
            return "image/jpeg", buf.getvalue()
    except Exception:  # unreadable picture: send it as it is and let the model decide
        return mime, raw


def evaluate(settings: Settings, gateway, answer: AnswerSubmission) -> dict | None:
    """Returns feedback_json (with "score") or None when the AI was not available. Raises ValueError with no photos."""
    images = load_images(settings, list(answer.image_paths or []))
    if not images:
        raise ValueError("no photos")
    system, user = promptlib.load(PROMPT)
    out = gateway.generate_json(
        feature="answer_eval",
        system=system,
        user=promptlib.render(user, question=answer.question, word_limit=answer.word_limit, pages=len(images)),
        schema=EvalOut,
        max_output_tokens=2500,
        images=images,
    )
    if out is None:
        return None
    assert isinstance(out, EvalOut)
    words = len(out.transcript.split())
    return {
        "structure": out.structure.model_dump(),
        "content_coverage": out.content_coverage,
        "examples_data": out.examples_data,
        "word_limit": {"words": words, "limit": answer.word_limit, "comment": out.word_limit_comment},
        "presentation": out.presentation,
        "strengths": out.strengths,
        "improvements": out.improvements,
        "model_outline": out.model_outline,
        "transcript": out.transcript,
        "readable": out.readable,
        "score": out.score,
    }
