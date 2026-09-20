"""Reads text and page pictures out of PDFs and photos (no network, no AI)."""
import io
import logging
import re
import statistics
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps
from pypdf import PdfReader

log = logging.getLogger(__name__)

MIN_TEXT_CHARS = 20  # a page with fewer letters than this is treated as "no text layer" (a scan)
MAX_IMAGE_SIDE = 2200  # photos are shrunk to this before saving / OCR (keeps files and AI calls small)
TELUGU_RE = re.compile("[ఀ-౿]")
LETTER_RE = re.compile(r"[^\W\d_]", re.UNICODE)


class ExtractError(Exception):
    """The file could not be read as a PDF or picture (message is short and plain)."""


@dataclass
class PageText:
    page: int  # 1-based
    text: str
    has_image: bool
    needs_ocr: bool


def telugu_ratio(text: str) -> float:
    letters = LETTER_RE.findall(text)
    if not letters:
        return 0.0
    return len(TELUGU_RE.findall(text)) / len(letters)


def is_telugu(text: str) -> bool:
    """True when most letters are in the Telugu Unicode block."""
    return len(TELUGU_RE.findall(text)) >= 3 and telugu_ratio(text) > 0.4


def reflow(text: str) -> str:
    """Turns PDF lines into paragraphs: joins wrapped lines, removes end-of-line hyphens, keeps blank-line breaks."""
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace(" ", " ")
    blocks = re.split(r"\n\s*\n", text)
    paragraphs: list[str] = []
    for block in blocks:
        lines = [ln.strip() for ln in block.split("\n") if ln.strip()]
        if not lines:
            continue
        lengths = [len(ln) for ln in lines]
        typical = statistics.median(lengths) if lengths else 0
        current = ""
        for line in lines:
            if not current:
                current = line
                continue
            if current.endswith("-") and line[:1].islower():
                current = current[:-1] + line  # "constitu-" + "tion"
            elif (
                current[-1:] in ".?!:;\"'”’)" and len(current) < 0.6 * typical and line[:1].isupper() and len(lines) > 3
            ):
                paragraphs.append(current)  # a short line that ends a sentence: the paragraph ended here
                current = line
            else:
                current += " " + line
        paragraphs.append(current)
    out = "\n\n".join(re.sub(r"[ \t]+", " ", p).strip() for p in paragraphs)
    return out.strip()


def _image_names(page) -> list:
    try:
        xobjects = page["/Resources"]["/XObject"].get_object()
    except (KeyError, TypeError, AttributeError):
        return []
    found = []
    for name in xobjects:
        try:
            if xobjects[name].get_object().get("/Subtype") == "/Image":
                found.append(name)
        except Exception:  # damaged object: ignore this one
            continue
    return found


def open_pdf(path: Path) -> PdfReader:
    try:
        reader = PdfReader(str(path))
        if reader.is_encrypted:
            try:
                reader.decrypt("")
            except Exception as exc:
                raise ExtractError("This PDF is locked with a password.") from exc
        len(reader.pages)  # forces the page tree to be read
        return reader
    except ExtractError:
        raise
    except Exception as exc:
        raise ExtractError("This PDF could not be read. It may be damaged.") from exc


def page_count(path: Path) -> int:
    return len(open_pdf(path).pages)


def extract_pages(path: Path, progress=None) -> list[PageText]:
    """Text of every page. `progress(done, total)` is called now and then."""
    reader = open_pdf(path)
    total = len(reader.pages)
    out: list[PageText] = []
    for i, page in enumerate(reader.pages, start=1):
        try:
            raw = page.extract_text() or ""
        except Exception as exc:  # one bad page must not stop the whole book
            log.warning("page %d text failed: %s", i, type(exc).__name__)
            raw = ""
        text = reflow(raw)
        letters = len(LETTER_RE.findall(text))
        has_image = bool(_image_names(page))
        out.append(PageText(i, text, has_image, needs_ocr=letters < MIN_TEXT_CHARS and has_image))
        if progress and (i % 10 == 0 or i == total):
            progress(i, total)
    return out


def normalize_image(data: bytes) -> tuple[bytes, int, int]:
    """Any common photo format -> upright RGB JPEG, at most MAX_IMAGE_SIDE on the long side."""
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Exception as exc:
        raise ExtractError("This picture could not be read. Use JPG or PNG.") from exc
    img = ImageOps.exif_transpose(img)
    if img.mode not in ("RGB", "L"):
        background = Image.new("RGB", img.size, "white")
        if img.mode in ("RGBA", "LA", "P"):
            img = img.convert("RGBA")
            background.paste(img, mask=img.split()[-1])
            img = background
        else:
            img = img.convert("RGB")
    elif img.mode == "L":
        img = img.convert("RGB")
    if max(img.size) > MAX_IMAGE_SIDE:
        img.thumbnail((MAX_IMAGE_SIDE, MAX_IMAGE_SIDE))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=88)
    return buf.getvalue(), img.size[0], img.size[1]


def images_to_pdf(jpegs: list[bytes], dest: Path) -> None:
    """One PDF page per photo (so every document can be downloaded and opened as a PDF)."""
    frames = [Image.open(io.BytesIO(j)).convert("RGB") for j in jpegs]
    frames[0].save(str(dest), "PDF", save_all=True, append_images=frames[1:], resolution=150.0, quality=88)


def page_image(path: Path, page: int) -> bytes | None:
    """The biggest picture on one PDF page, as a JPEG (None when the page has no readable picture)."""
    try:
        reader = open_pdf(path)
        pdf_page = reader.pages[page - 1]
    except (ExtractError, IndexError):
        return None
    best: bytes | None = None
    best_area = 0
    try:
        images = list(pdf_page.images)
    except Exception as exc:
        log.warning("page %d images failed: %s", page, type(exc).__name__)
        return None
    for im in images:
        try:
            jpeg, w, h = normalize_image(im.data)
        except ExtractError:
            continue
        if w * h > best_area:
            best, best_area = jpeg, w * h
    return best
