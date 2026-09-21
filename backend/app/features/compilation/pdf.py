"""Writes the digest as a PDF with fpdf2 and the IBM Plex fonts that ship in features/reports/fonts (read only).

Those fonts have no Telugu letters, and text shaping (needed for Telugu) is not installed, so characters the font
cannot draw are left out of the PDF. The markdown text on the tablet always shows everything.
"""
import logging
import re
from functools import lru_cache
from pathlib import Path

from fontTools.ttLib import TTFont
from fpdf import FPDF

from app.config import Settings
from app.files import data_path

log = logging.getLogger(__name__)
FONT_DIR = Path(__file__).resolve().parents[1] / "reports" / "fonts"
REGULAR, BOLD = "plex_regular.ttf", "plex_semibold.ttf"
_SWAPS = {"→": "->", "₹": "Rs ", "‌": "", "‍": "", " ": " ", "\t": " "}


@lru_cache(maxsize=2)
def _cmap(path: str) -> frozenset[int]:
    return frozenset(TTFont(path).getBestCmap().keys())


def clean(text: str, covered: frozenset[int] | None) -> str:
    """Replaces a few symbols and drops letters the font cannot draw."""
    for old, new in _SWAPS.items():
        text = text.replace(old, new)
    if covered is None:
        return text
    text = "".join(ch if (ord(ch) in covered or ch == "\n") else "" for ch in text)
    return re.sub(r"[ ]{2,}", " ", text)


def _inline(text: str) -> str:
    return re.sub(r"\*\*(.+?)\*\*", r"\1", text)


def make_pdf(title: str, markdown: str) -> bytes:
    regular, bold = FONT_DIR / REGULAR, FONT_DIR / BOLD
    have_fonts = regular.is_file() and bold.is_file()
    covered = _cmap(str(regular)) if have_fonts else None
    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(True, margin=15)
    pdf.set_margins(15, 15, 15)
    if have_fonts:
        pdf.add_font("Plex", "", str(regular))
        pdf.add_font("Plex", "B", str(bold))
        family = "Plex"
    else:  # no fonts found: built-in Helvetica (Latin-1 only)
        family = "Helvetica"
    pdf.set_title(clean(title, covered))
    pdf.add_page()
    width = pdf.w - pdf.l_margin - pdf.r_margin

    def put(text: str, size: int, style: str = "", indent: float = 0, gap: float = 1.5) -> None:
        text = clean(_inline(text), covered)
        if not have_fonts:
            text = text.encode("latin-1", "replace").decode("latin-1")
        pdf.set_font(family, style, size)
        pdf.set_x(pdf.l_margin + indent)
        pdf.multi_cell(width - indent, size * 0.5, text, new_x="LMARGIN", new_y="NEXT")
        pdf.ln(gap)

    for raw in markdown.splitlines():
        line = raw.rstrip()
        if not line.strip():
            pdf.ln(1)
        elif line.startswith("# "):
            put(line[2:], 18, "B", gap=3)
        elif line.startswith("## "):
            pdf.ln(2)
            put(line[3:], 14, "B", gap=2)
        elif line.startswith("### "):
            put(line[4:], 12, "B", gap=1)
        elif line.startswith("- "):
            put("- " + line[2:], 10, indent=3)
        elif line.startswith("  "):
            put(line.strip(), 9, indent=8, gap=0.5)
        else:
            put(line, 10)
    return bytes(pdf.output())


def write_pdf(settings: Settings, month: str, title: str, markdown: str) -> str | None:
    """Saves data/compilations/<month>.pdf and returns the path relative to the data folder (None on failure)."""
    try:
        target = data_path(settings, "compilations", f"{month}.pdf")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(make_pdf(title, markdown))
        return f"compilations/{month}.pdf"
    except Exception as exc:  # the text digest is still useful without the PDF
        log.warning("compilation PDF not written: %s", exc)
        return None
