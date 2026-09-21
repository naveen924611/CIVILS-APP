"""PDF files for revision sheets and the weekly report (fpdf2, IBM Plex Sans from ./fonts).

Telugu: the bundled font has no Telugu letters and fpdf2 needs the optional 'uharfbuzz' package to join Telugu letters
correctly (not installed), so Telugu text is replaced by "[Telugu]" in a PDF. Listening and reading in the app show it fine.
"""
from functools import lru_cache
from pathlib import Path

from fpdf import FPDF
from fpdf.enums import XPos, YPos

FONTS = Path(__file__).parent / "fonts"
REGULAR = FONTS / "plex_regular.ttf"
BOLD = FONTS / "plex_semibold.ttf"
PUNCT = {"‘": "'", "’": "'", "“": '"', "”": '"', "–": "-", "—": "-", "•": "-",
         "…": "...", " ": " ", "→": "->", "←": "<-", "✓": "ok", "₹": "Rs "}


@lru_cache
def _glyphs() -> frozenset[int]:
    try:
        from fontTools.ttLib import TTFont

        return frozenset(TTFont(str(REGULAR)).getBestCmap())
    except Exception:  # without the check every printable character is tried
        return frozenset(range(32, 127))


def clean(text: str) -> str:
    """Text the font can draw: punctuation simplified, Telugu shown as [Telugu], other unknown letters as '?'."""
    out: list[str] = []
    glyphs = _glyphs()
    telugu = False
    for ch in str(text or ""):
        ch = PUNCT.get(ch, ch)
        for c in ch:
            if 0x0C00 <= ord(c) <= 0x0C7F:
                if not telugu:
                    out.append("[Telugu]")
                telugu = True
                continue
            telugu = False
            if c in "\n\t" or ord(c) in glyphs:
                out.append(c)
            else:
                out.append("?")
    return "".join(out)


class _Pdf(FPDF):
    def __init__(self, footer_text: str):
        super().__init__(format="A4")
        self.footer_text = clean(footer_text)
        self.set_margins(16, 16, 16)
        self.set_auto_page_break(True, margin=16)
        self.add_font("Plex", "", str(REGULAR))
        self.add_font("Plex", "B", str(BOLD))
        self.add_page()

    def footer(self):
        self.set_y(-12)
        self.set_font("Plex", "", 8)
        self.set_text_color(120, 120, 120)
        self.cell(0, 6, f"{self.footer_text}   page {self.page_no()}", align="C")

    def title_line(self, text: str, size: int = 20):
        self.set_font("Plex", "B", size)
        self.set_text_color(15, 94, 90)
        self.multi_cell(0, size * 0.5, clean(text), new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(1)

    def sub(self, text: str):
        self.set_font("Plex", "", 10)
        self.set_text_color(110, 110, 110)
        self.multi_cell(0, 5, clean(text), new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(2)

    def heading(self, text: str):
        self.ln(3)
        self.set_font("Plex", "B", 13)
        self.set_text_color(15, 94, 90)
        self.multi_cell(0, 7, clean(text), new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.set_draw_color(220, 214, 200)
        self.line(self.l_margin, self.get_y(), self.w - self.r_margin, self.get_y())
        self.ln(2)

    def para(self, text: str):
        self.set_font("Plex", "", 11)
        self.set_text_color(28, 27, 25)
        self.multi_cell(0, 6, clean(text), new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(1)

    def bullets(self, items: list[str]):
        self.set_font("Plex", "", 11)
        self.set_text_color(28, 27, 25)
        for item in items:
            text = clean(item)
            if not text.strip():
                continue
            self.set_x(self.l_margin + 3)
            self.multi_cell(0, 6, "- " + text, new_x=XPos.LMARGIN, new_y=YPos.NEXT)
            self.ln(0.8)


def _save(pdf: FPDF, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    pdf.output(str(path))
    return path


def render_sheet(title: str, subject: str, sections: dict, path: Path) -> Path:
    pdf = _Pdf("Civils Companion, revision sheet")
    pdf.title_line(title)
    if subject:
        pdf.sub(subject)
    blocks = [
        ("Key facts", sections.get("key_facts")),
        ("Must remember", sections.get("must_remember")),
        ("Past-paper themes", sections.get("past_paper_themes")),
        ("In the news", [n.get("title", "") for n in sections.get("in_the_news") or [] if isinstance(n, dict)]),
        ("Memory hooks", sections.get("memory_hooks")),
    ]
    for name, items in blocks:
        items = [str(i) for i in (items or []) if str(i).strip()]
        if items:
            pdf.heading(name)
            pdf.bullets(items)
    if str(sections.get("mains_angle") or "").strip():
        pdf.heading("Mains angle")
        pdf.para(str(sections["mains_angle"]))
    return _save(pdf, path)


def render_report(data: dict, path: Path) -> Path:
    pdf = _Pdf("Civils Companion, weekly report")
    pdf.title_line("Weekly report")
    pdf.sub(f"Week of {data.get('week_start', '')} to {data.get('week_end', '')}")
    if data.get("narrative"):
        pdf.para(str(data["narrative"]))
    h = data.get("hours") or {}
    pdf.heading("This week in numbers")
    pdf.bullets([
        f"Study time: {h.get('done_minutes', 0)} of {h.get('planned_minutes', 0)} planned minutes done",
        f"Focused time: {h.get('focus_minutes', 0)} minutes",
        f"Topics finished: {len(data.get('topics_finished') or [])}",
        f"Cards revised: {(data.get('cards') or {}).get('revised', 0)}",
        f"Questions answered: {(data.get('mcq') or {}).get('attempted', 0)}, "
        f"accuracy {round(100 * float((data.get('mcq') or {}).get('accuracy', 0)))} percent",
    ])
    for t in (data.get("mcq") or {}).get("tests") or []:
        pdf.bullets([f"Test: {t.get('title', '')}, score {t.get('score', '')} of {t.get('total', '')}"])
    covered = [c.get("title", "") for c in data.get("covered") or []]
    if covered:
        pdf.heading("Covered this week")
        pdf.bullets(covered)
    weak = data.get("weak_spots") or {}
    lines = [f"{w.get('title', '')} (memory strength {round(100 * float(w.get('strength', 0)))} percent)" for w in weak.get("topics") or []]
    if weak.get("fading_cards"):
        lines.append(f"{weak['fading_cards']} cards are fading (overdue for revision)")
    if weak.get("low_days"):
        lines.append("Low days: " + ", ".join(weak["low_days"]))
    if lines:
        pdf.heading("Weak spots")
        pdf.bullets(lines)
    nxt = data.get("next_week") or {}
    if nxt.get("changes"):
        pdf.heading("Next week adjusts")
        pdf.bullets([str(c) for c in nxt["changes"]])
    lm = data.get("last_month") or {}
    if lm.get("active"):
        pdf.heading("Last-month mode")
        pdf.para(f"{lm.get('exam', 'Your exam')} is in {lm.get('days_left', '?')} days. Revision now uses revision sheets plus due cards.")
    return _save(pdf, path)
