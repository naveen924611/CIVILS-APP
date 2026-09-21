"""Deterministic aptitude and reasoning questions for the SI (Civil) drill. No AI, no network: every question is built
from `random.Random(seed)`, so the same seed always gives the same questions.

Public API
    AREAS                       ordered list of area keys; LABELS maps a key to its human label
    generate_questions(area, count, seed)  -> [{question, options (4 distinct), answer_index, explanation, area}]
    area_for_date(day)          the focus area of a day (rotates through all AREAS)
    areas_for_date(day)         the focus area plus three others (a mixed drill)
    mixed_questions(day, count=20)

Exactness: arithmetic uses integers and `fractions.Fraction`, never floats. Answers that are not whole numbers are shown
with at most two decimals and only when they are exact (a template whose answer would need rounding is redrawn).
Where a value needs pi, the question says "take pi = 22/7". Every explanation shows the working and ends with the
correct option text (so `generate.validate_mcq` accepts it). Distractors are plausible wrong values, usually the
result of a common mistake.

Each template is a function `(r: random.Random) -> Q`; `Q.meta` keeps the numbers used (and the template name) so the
unit tests can recompute the answer with a second, independent formula.
"""
from __future__ import annotations

import functools
import itertools
import math
import random
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date
from fractions import Fraction

from .common import norm_text

AREAS: list[str] = [
    "percentage", "profit_loss", "simple_interest", "compound_interest", "ratio_proportion", "average", "time_work",
    "work_wages", "time_distance", "clocks_calendars", "partnership", "mensuration", "number_system", "series",
    "coding_decoding", "blood_relations", "direction_sense", "ranking_order", "odd_one_out", "analogy", "syllogism",
    "statements_conclusions",
]
# Labels of the arithmetic areas follow the titles in data/syllabus/slprb_si_written.json, so a drill can be linked to
# its syllabus topic (matched by title, ignoring case, punctuation and the word "and").
LABELS: dict[str, str] = {
    "percentage": "Percentage", "profit_loss": "Profit & loss", "simple_interest": "Simple interest",
    "compound_interest": "Compound interest", "ratio_proportion": "Ratio & proportion", "average": "Average",
    "time_work": "Time & work", "work_wages": "Work & wages", "time_distance": "Time & distance",
    "clocks_calendars": "Clocks & calendars", "partnership": "Partnership", "mensuration": "Mensuration",
    "number_system": "Number system", "series": "Number and letter series", "coding_decoding": "Coding-decoding",
    "blood_relations": "Blood relations", "direction_sense": "Direction sense", "ranking_order": "Ranking and order",
    "odd_one_out": "Odd one out", "analogy": "Analogies", "syllogism": "Syllogism",
    "statements_conclusions": "Statements and conclusions",
}
MIXED_OFFSETS = (0, 5, 11, 16)  # the focus area and three others, spread over arithmetic and reasoning


class Retry(Exception):
    """The drawn numbers do not make a good question (inexact answer, repeated option, ...): draw again."""


@dataclass
class Q:
    area: str
    kind: str
    question: str
    options: list[str]
    answer_index: int
    explanation: str
    meta: dict = field(default_factory=dict)

    def public(self) -> dict:
        return {"question": self.question, "options": list(self.options), "answer_index": self.answer_index,
                "explanation": self.explanation, "area": self.area}


# --------------------------------------------------------------------------- number formatting


def num(x) -> str:
    """12, 12.5, 0.25 ... an exact value with at most two decimals (Retry when it needs more)."""
    f = Fraction(x)
    if f.denominator == 1:
        return str(f.numerator)
    cents = f * 100
    if cents.denominator != 1:
        raise Retry("needs rounding")
    n = abs(int(cents))
    whole, frac = divmod(n, 100)
    text = f"{whole}.{frac:02d}".rstrip("0")
    return ("-" if cents < 0 else "") + text


def rs(x) -> str:
    return "Rs " + num(x)


def pct(x) -> str:
    return num(x) + "%"


def kmph(x) -> str:
    return num(x) + " km/h"


def mixed(x) -> str:
    """A positive fraction as a mixed number: 5 5/11."""
    f = Fraction(x)
    whole, rest = divmod(f.numerator, f.denominator)
    if rest == 0:
        return str(whole)
    return f"{whole} {rest}/{f.denominator}" if whole else f"{rest}/{f.denominator}"


def ratio_text(a: int, b: int, c: int | None = None) -> str:
    g = math.gcd(math.gcd(a, b), c or 0) if c is not None else math.gcd(a, b)
    parts = [a // g, b // g] + ([c // g] if c is not None else [])
    return " : ".join(str(p) for p in parts)


def _nt(text: str) -> str:
    return norm_text(text)


def _distinct(correct: str, wrongs: list[str]) -> list[str]:
    seen = {_nt(correct)}
    out = []
    for w in wrongs:
        k = _nt(w)
        if w and k and k not in seen:
            seen.add(k)
            out.append(w)
    return out


def numopts(r: random.Random, correct, mistakes, fmt: Callable = num, step=None, positive: bool = True) -> tuple[str, list[str]]:
    """(correct text, three wrong texts): common mistakes first (in random order), then values close to the answer."""
    c = Fraction(correct)
    right = fmt(c)
    seen = {_nt(right)}
    out: list[str] = []

    def add(v) -> None:
        v = Fraction(v)
        if positive and v <= 0:
            return
        try:
            s = fmt(v)
        except Retry:
            return
        k = _nt(s)
        if k not in seen:
            seen.add(k)
            out.append(s)

    ms = list(mistakes)
    r.shuffle(ms)
    for m in ms:
        add(m)
    out = out[:3]
    if step is None:
        if c.denominator == 1:
            step = Fraction(max(1, int(abs(c)) // 12)) if abs(c) >= 24 else Fraction(1)
        else:
            step = Fraction(1, 2) if abs(c) < 10 else Fraction(1)
    k = 1
    while len(out) < 3 and k < 30:
        add(c + step * k)
        if len(out) < 3:
            add(c - step * k)
        k += 1
    if len(out) < 3:
        raise Retry("not enough distractors")
    return right, out[:3]


def _make(r: random.Random, area: str, kind: str, question: str, correct: str, wrongs: list[str], working: str,
          meta: dict) -> Q:
    wrongs = _distinct(correct, wrongs)
    if len(wrongs) < 3:
        raise Retry(kind)
    options = [correct] + wrongs[:3]
    order = list(range(4))
    r.shuffle(order)
    shuffled = [options[i] for i in order]
    return Q(area, kind, question, shuffled, shuffled.index(correct), f"{working} So the answer is {correct}.",
             dict(meta, kind=kind))


def _num_q(r: random.Random, area: str, kind: str, question: str, answer, mistakes, working: str, meta: dict,
           fmt: Callable = num, step=None, positive: bool = True) -> Q:
    right, wrongs = numopts(r, answer, mistakes, fmt, step, positive)
    return _make(r, area, kind, question, right, wrongs, working, meta)


def _lcm(a: int, b: int) -> int:
    return a * b // math.gcd(a, b)


NAMES_M = ["Ravi", "Suresh", "Anil", "Ramesh", "Vijay", "Mahesh", "Naresh", "Prasad", "Srinivas", "Venkat", "Rajesh",
           "Arjun", "Sanjay", "Harish", "Kiran", "Ganesh"]
NAMES_F = ["Sita", "Lakshmi", "Anitha", "Meena", "Radha", "Padma", "Swathi", "Divya", "Kavya", "Sravani", "Bhavani", "Latha"]


def _names(r: random.Random, n: int, pool: list[str] | None = None) -> list[str]:
    return r.sample(pool or (NAMES_M + NAMES_F), n)


# --------------------------------------------------------------------------- percentage


def pct_of(r):
    n = r.choice([120, 150, 200, 240, 250, 360, 400, 450, 480, 500, 600, 720, 800, 1200, 1500, 2400])
    p = r.choice([5, 8, 12, 15, 16, 18, 20, 24, 25, 35, 36, 40, 45, 60, 65, 75, 80])
    ans = Fraction(n * p, 100)
    return _num_q(r, "percentage", "pct_of", f"What is {p}% of {n}?", ans,
                  [Fraction(n * p, 10), Fraction(n, p), Fraction(n * (100 - p), 100), ans + p],
                  f"{p}% of {n} = {n} x {p}/100 = {num(ans)}.", {"n": n, "p": p})


def pct_what(r):
    y = r.choice([40, 60, 80, 120, 160, 200, 250, 400, 500, 800])
    p = r.choice([5, 10, 12, 15, 20, 25, 30, 35, 40, 45, 60, 75, 80, 120, 125, 150])
    if (y * p) % 100:
        raise Retry("x not whole")
    x = y * p // 100
    return _num_q(r, "percentage", "pct_what", f"{x} is what percent of {y}?", p,
                  [100 - p, Fraction(y * 100, x), x, p + 10, p * 2],
                  f"Percent = ({x} / {y}) x 100 = {p}%.", {"x": x, "y": y}, fmt=pct)


def _change_text(v: Fraction) -> str:
    if v == 0:
        return "No change"
    return f"{num(abs(v))}% {'increase' if v > 0 else 'decrease'}"


def pct_successive(r):
    a = r.choice([10, 20, 25, 30, 40, 50])
    b = r.choice([10, 15, 20, 25, 30, 40])
    net = Fraction((100 + a) * (100 - b), 100) - 100
    if net == 0:
        raise Retry("no net change")
    return _num_q(
        r, "percentage", "pct_successive",
        f"The price of an article is first increased by {a}% and then decreased by {b}%. What is the net change in the price?",
        net, [a - b, a + b, b - a, net + 2, -net], f"Net price = 100 x {100 + a}/100 x {100 - b}/100 = {num(Fraction((100 + a) * (100 - b), 100))}, "
        f"a change of {num(net)}% over 100.", {"a": a, "b": b}, fmt=_change_text, step=Fraction(1), positive=False)


def pct_original(r):
    base = r.randrange(20, 400) * 100
    x = r.choice([5, 10, 15, 20, 25, 30])
    n = base * (100 + x) // 100
    return _num_q(
        r, "percentage", "pct_original",
        f"After an increase of {x}%, the population of a town became {n}. What was the population before the increase?",
        base, [Fraction(n * (100 - x), 100), n - x, Fraction(n * (100 + x), 100), n - Fraction(n * x, 100) + x],
        f"Before x (100 + {x})/100 = {n}, so before = {n} x 100/{100 + x} = {base}.", {"n": n, "x": x})


def pct_pass(r):
    m = r.choice([200, 250, 400, 500, 600, 750, 800, 1000])
    p = r.choice([30, 35, 40, 45])
    if (m * p) % 100:
        raise Retry("pass mark not whole")
    need = m * p // 100
    y = r.randrange(5, 41)
    x = need - y
    if x <= 0:
        raise Retry("marks")
    return _num_q(
        r, "percentage", "pct_pass",
        f"A student needs {p}% of the maximum marks to pass. He got {x} marks and failed by {y} marks. What are the maximum marks?",
        m, [x + y, Fraction(x * 100, p), (x + y) * p // 100, x + y + 50],
        f"Pass marks = {x} + {y} = {x + y}, which is {p}% of the maximum. Maximum = {x + y} x 100/{p} = {m}.",
        {"x": x, "y": y, "p": p})


# --------------------------------------------------------------------------- profit and loss


def pl_sp(r):
    cp = r.choice([200, 250, 300, 400, 450, 500, 600, 800, 1200, 1500, 2000])
    p = r.choice([5, 8, 10, 12, 15, 20, 25, 30])
    loss = r.random() < 0.4
    sp = Fraction(cp * (100 - p if loss else 100 + p), 100)
    word = "loss" if loss else "profit"
    return _num_q(
        r, "profit_loss", "pl_sp", f"An article bought for Rs {cp} is sold at a {word} of {p}%. Find the selling price.",
        sp, [Fraction(cp * (100 + p if loss else 100 - p), 100), cp + p if not loss else cp - p, Fraction(cp * p, 100), sp + p],
        f"Selling price = {cp} x {100 - p if loss else 100 + p}/100 = Rs {num(sp)}.", {"cp": cp, "p": p, "loss": loss}, fmt=rs)


def pl_cp(r):
    cp = r.choice([200, 250, 400, 480, 500, 600, 800, 1000, 1200, 2400])
    p = r.choice([10, 20, 25, 5, 15])
    loss = r.random() < 0.4
    sp = Fraction(cp * (100 - p if loss else 100 + p), 100)
    if sp.denominator != 1:
        raise Retry("sp")
    word = "loss" if loss else "profit"
    return _num_q(
        r, "profit_loss", "pl_cp", f"By selling an article for Rs {num(sp)}, a shopkeeper makes a {word} of {p}%. What was the cost price?",
        cp, [Fraction(sp * (100 - p if loss else 100 + p), 100), sp - Fraction(sp * p, 100) if not loss else sp + Fraction(sp * p, 100), sp - p],
        f"Cost price = {num(sp)} x 100/{100 - p if loss else 100 + p} = Rs {num(cp)}.", {"sp": int(sp), "p": p, "loss": loss}, fmt=rs)


def pl_percent(r):
    cp = r.choice([20, 25, 40, 50, 80, 100, 125, 200, 250, 400, 500])
    d = r.choice([-1, 1]) * r.choice([2, 4, 5, 8, 10, 15, 20, 25, 30, 40])
    sp = cp + d * cp // 100 if (d * cp) % 100 == 0 else None
    if sp is None or sp <= 0 or sp == cp:
        raise Retry("sp")
    change = Fraction((sp - cp) * 100, cp)
    word = "profit" if sp > cp else "loss"
    return _num_q(
        r, "profit_loss", "pl_percent", f"An article bought for Rs {cp} is sold for Rs {sp}. Find the {word} percent.",
        abs(change), [Fraction(abs(sp - cp) * 100, sp), abs(sp - cp), abs(change) + 5], f"{word.capitalize()} = {abs(sp - cp)}; {word} % = {abs(sp - cp)}/{cp} x 100 = {num(abs(change))}%.",
        {"cp": cp, "sp": sp}, fmt=pct)


def pl_discount(r):
    cp = r.choice([400, 500, 800, 1000, 1200, 2000])
    x = r.choice([20, 25, 30, 40, 50])
    y = r.choice([10, 15, 20, 25])
    mp = Fraction(cp * (100 + x), 100)
    sp = mp * (100 - y) / 100
    net = Fraction(sp - cp, cp) * 100
    if net == 0:
        raise Retry("no gain")
    return _num_q(
        r, "profit_loss", "pl_discount",
        f"A shopkeeper marks an article {x}% above its cost price of Rs {cp} and allows a discount of {y}%. What is his gain or loss percent?",
        net, [x - y, Fraction(x * (100 - y), 100), x + y, net + 2, -net],
        f"Marked price = Rs {num(mp)}; selling price = {num(mp)} x {100 - y}/100 = Rs {num(sp)}; change on Rs {cp} = {num(net)}%.",
        {"cp": cp, "x": x, "y": y}, fmt=lambda v: (num(abs(v)) + "% " + ("gain" if v > 0 else "loss")) if v else "No gain no loss", step=Fraction(1), positive=False)


def pl_two_articles(r):
    s = r.choice([1200, 1500, 1800, 2000, 2400, 3000])
    a = r.choice([5, 10, 15, 20, 25])
    loss_pct = Fraction(a * a, 100)
    return _num_q(
        r, "profit_loss", "pl_two",
        f"Two articles are sold at Rs {s} each. On one there is a gain of {a}% and on the other a loss of {a}%. What is the overall result?",
        loss_pct, [Fraction(0), a, Fraction(a, 2), loss_pct * 2],
        f"When two articles are sold at the same price with equal gain % and loss %, there is always a loss of ({a} x {a})/100 = {num(loss_pct)}%.",
        {"s": s, "a": a}, fmt=lambda v: "No gain no loss" if v == 0 else (num(v) + "% loss" if v > 0 else num(-v) + "% gain"), positive=False, step=Fraction(1, 2))


# --------------------------------------------------------------------------- simple interest


def si_interest(r):
    p = r.choice([2000, 2500, 4000, 5000, 6000, 8000, 10000, 12000, 15000])
    rate = r.choice([4, 5, 6, 8, 10, 12])
    t = r.choice([2, 3, 4, 5])
    ask_amount = r.random() < 0.5
    si = Fraction(p * rate * t, 100)
    if ask_amount:
        return _num_q(r, "simple_interest", "si_amount",
                      f"What is the amount on Rs {p} at {rate}% simple interest per annum for {t} years?", p + si,
                      [si, p + si + p * rate // 100, p + Fraction(p * rate, 100)],
                      f"SI = {p} x {rate} x {t}/100 = Rs {num(si)}; amount = {p} + {num(si)} = Rs {num(p + si)}.",
                      {"p": p, "r": rate, "t": t, "amount": True}, fmt=rs)
    return _num_q(r, "simple_interest", "si_interest",
                  f"Find the simple interest on Rs {p} at {rate}% per annum for {t} years.", si,
                  [p + si, Fraction(p * rate, 100), Fraction(p * t, 100), si + p // 10],
                  f"SI = P x R x T/100 = {p} x {rate} x {t}/100 = Rs {num(si)}.", {"p": p, "r": rate, "t": t, "amount": False}, fmt=rs)


def si_rate(r):
    p = r.choice([2000, 2500, 4000, 5000, 8000, 10000])
    rate = r.choice([3, 4, 5, 6, 8, 10, 12])
    t = r.choice([2, 3, 4, 5])
    si = p * rate * t // 100
    return _num_q(r, "simple_interest", "si_rate",
                  f"A sum of Rs {p} earns Rs {si} as simple interest in {t} years. What is the rate of interest per annum?", rate,
                  [Fraction(si * 100, p), rate * t, Fraction(rate, 2), rate + 2, rate - 1], f"Rate = SI x 100/(P x T) = {si} x 100/({p} x {t}) = {rate}%.",
                  {"p": p, "si": si, "t": t}, fmt=pct)


def si_time(r):
    p = r.choice([2000, 4000, 5000, 8000, 10000, 12500])
    rate = r.choice([4, 5, 8, 10, 12])
    t = r.choice([2, 3, 4, 5, 6, 8])
    si = p * rate * t // 100
    return _num_q(r, "simple_interest", "si_time",
                  f"In how many years will Rs {p} give Rs {si} as simple interest at {rate}% per annum?", t,
                  [Fraction(si, p), t * 2, t + 1, Fraction(si * rate, p)], f"T = SI x 100/(P x R) = {si} x 100/({p} x {rate}) = {t} years.",
                  {"p": p, "si": si, "r": rate}, fmt=lambda v: num(v) + " years")


def si_multiple(r):
    t = r.choice([4, 5, 8, 10, 12, 15, 20])
    n = r.choice([3, 4, 5])
    return _num_q(r, "simple_interest", "si_multiple",
                  f"A sum of money becomes 2 times itself in {t} years at simple interest. In how many years will it become {n} times itself?",
                  (n - 1) * t, [n * t, t * n // 2 + t, (n + 1) * t, t + n], f"The interest of one sum in {t} years equals the sum, so it grows by the sum every {t} years. "
                  f"To become {n} times the interest must be {n - 1} times the sum: {n - 1} x {t} = {(n - 1) * t} years.", {"t": t, "n": n},
                  fmt=lambda v: num(v) + " years")


# --------------------------------------------------------------------------- compound interest


def ci_interest(r):
    rate = r.choice([5, 10, 20])
    n = r.choice([2, 3])
    p = r.choice([2000, 4000, 8000, 10000, 16000, 20000, 40000]) if rate != 5 else r.choice([8000, 16000, 20000, 40000])
    amount = Fraction(p * (100 + rate) ** n, 100 ** n)
    ci = amount - p
    si = Fraction(p * rate * n, 100)
    return _num_q(r, "compound_interest", "ci_interest",
                  f"Find the compound interest on Rs {p} for {n} years at {rate}% per annum, compounded annually.", ci,
                  [si, amount, ci + rate * 10, ci - Fraction(p * rate, 1000)],
                  f"Amount = {p} x ({100 + rate}/100)^{n} = Rs {num(amount)}; CI = {num(amount)} - {p} = Rs {num(ci)}.",
                  {"p": p, "rate": rate, "n": n, "what": "ci"}, fmt=rs)


def ci_amount(r):
    rate = r.choice([10, 20, 5, 25])
    n = r.choice([2, 3])
    p = r.choice([1600, 2000, 4000, 8000, 10000, 16000, 20000])
    amount = Fraction(p * (100 + rate) ** n, 100 ** n)
    simple = p + Fraction(p * rate * n, 100)
    return _num_q(r, "compound_interest", "ci_amount",
                  f"What sum will Rs {p} amount to in {n} years at {rate}% per annum compound interest (compounded annually)?", amount,
                  [simple, amount - p, p * (100 + rate * n) // 100 + rate, amount + rate * 10],
                  f"Amount = P(1 + R/100)^n = {p} x ({100 + rate}/100)^{n} = Rs {num(amount)}.", {"p": p, "rate": rate, "n": n, "what": "amount"}, fmt=rs)


def ci_difference(r):
    rate = r.choice([5, 10, 20, 8, 4])
    p = r.choice([2500, 5000, 8000, 10000, 12500, 20000, 25000, 40000])
    diff = Fraction(p * rate * rate, 10000)
    return _num_q(r, "compound_interest", "ci_difference",
                  f"Find the difference between the compound interest and the simple interest on Rs {p} for 2 years at {rate}% per annum.", diff,
                  [Fraction(p * rate, 100), Fraction(p * rate * rate, 1000), diff * 2, Fraction(p * rate * rate, 100)],
                  f"For 2 years, CI - SI = P x (R/100)^2 = {p} x {rate} x {rate}/10000 = Rs {num(diff)}.", {"p": p, "rate": rate}, fmt=rs)


def ci_growth(r):
    k = r.choice([2, 3])
    n = r.choice([2, 3, 4, 5])
    m = r.choice([2, 3])
    ans = n * m
    return _num_q(r, "compound_interest", "ci_growth",
                  f"A sum becomes {k} times itself in {n} years at compound interest. In how many years will it become {k ** m} times itself?", ans,
                  [n * k, n + m, n * (k ** m) // k, n * m + n], f"Growth multiplies by {k} every {n} years. {k ** m} = {k}^{m}, so it takes {m} x {n} = {ans} years.",
                  {"k": k, "n": n, "m": m}, fmt=lambda v: num(v) + " years")


# --------------------------------------------------------------------------- ratio and proportion


def rp_share(r):
    parts = r.choice([(2, 3), (3, 5), (4, 5), (2, 3, 5), (1, 2, 3), (3, 4, 5), (2, 5, 7)])
    k = r.choice([100, 120, 150, 200, 250, 300, 400, 500])
    total = sum(parts) * k
    who = r.randrange(len(parts))
    names = ["A", "B", "C"][:len(parts)]
    share = parts[who] * k
    txt = " : ".join(str(p) for p in parts)
    return _num_q(r, "ratio_proportion", "rp_share", f"Rs {total} is divided among {' and '.join(names) if len(parts) == 2 else ', '.join(names[:-1]) + ' and ' + names[-1]} "
                  f"in the ratio {txt}. How much does {names[who]} get?", share,
                  [Fraction(total, len(parts)), total - share, share + k, share + 2 * k],
                  f"Sum of ratio parts = {sum(parts)}; one part = {total}/{sum(parts)} = {k}; {names[who]} = {parts[who]} x {k} = Rs {share}.",
                  {"parts": list(parts), "total": total, "who": who}, fmt=rs)


def rp_chain(r):
    a, b = r.sample(range(1, 9), 2)
    c, d = r.sample(range(1, 9), 2)
    right = ratio_text(a * c, b * d)
    wrong = [ratio_text(a * d, b * c), ratio_text(a * c, b + d), ratio_text(a + c, b + d), ratio_text(b * c, a * d),
             ratio_text(a * b, c * d)]
    return _make(r, "ratio_proportion", "rp_chain", f"If A : B = {a} : {b} and B : C = {c} : {d}, find A : C.", right, wrong,
                 f"Make B equal in both ratios: A : B = {a * c} : {b * c} and B : C = {b * c} : {b * d}. So A : C = {a * c} : {b * d} = {right}.",
                 {"a": a, "b": b, "c": c, "d": d})


def rp_add(r):
    m, n = r.sample(range(2, 13), 2)
    x = r.choice([2, 3, 4, 5, 6, 8, 10])
    kk = r.randrange(2, 8)
    a, b = m * kk, n * kk  # the two numbers
    if a == b:
        raise Retry("equal")
    g1 = math.gcd(a, b)
    ra, rb = a // g1, b // g1
    c, d = a + x, b + x
    g2 = math.gcd(c, d)
    rc, rd = c // g2, d // g2
    if (ra, rb) == (rc, rd) or rc == rd:
        raise Retry("same ratio")
    # solve (ra*k + x) : (rb*k + x) = rc : rd  ->  k = x (rd - rc)/(rc*rb - ra*rd)
    den = rc * rb - ra * rd
    if den == 0:
        raise Retry("no solution")
    k = Fraction(x * (rd - rc), den)
    if k != Fraction(a, ra) or k <= 0:
        raise Retry("not unique")
    small = min(a, b)
    return _num_q(
        r, "ratio_proportion", "rp_add",
        f"Two numbers are in the ratio {ra} : {rb}. If {x} is added to each of them, the ratio becomes {rc} : {rd}. Find the smaller number.",
        small, [max(a, b), small + x, small - x, a + b, small * 2],
        f"Let the numbers be {ra}k and {rb}k. ({ra}k + {x})/({rb}k + {x}) = {rc}/{rd} gives {rd}({ra}k + {x}) = {rc}({rb}k + {x}), so k = {k}. "
        f"The numbers are {a} and {b}; the smaller is {small}.", {"ra": ra, "rb": rb, "rc": rc, "rd": rd, "x": x})


def rp_fourth(r):
    a = r.choice([4, 5, 6, 8, 9, 12])
    b = r.choice([3, 5, 7, 10, 15]) * 2
    m = r.choice([2, 3, 4, 5, 6])
    c = a * m
    ans = b * m
    return _num_q(r, "ratio_proportion", "rp_fourth", f"If {a} : {b} = {c} : x, find x.", ans,
                  [Fraction(b * a, c), b + c - a, c + b, b * (c - a)],
                  f"x = {b} x {c}/{a} = {ans}.", {"a": a, "b": b, "c": c})


# --------------------------------------------------------------------------- average


def av_list(r):
    n = r.choice([4, 5, 6])
    mean = r.randrange(12, 60)
    values = [r.randrange(mean - 10, mean + 10) for _ in range(n - 1)]
    values.append(mean * n - sum(values))
    if values[-1] <= 0:
        raise Retry("value")
    shown = ", ".join(str(v) for v in values)
    return _num_q(r, "average", "av_list", f"Find the average of {shown}.", mean,
                  [Fraction(sum(values), n + 1), Fraction(sum(values), n - 1), max(values), sum(values) // n + 1, Fraction(sum(values), 2)],
                  f"Sum = {sum(values)}; average = {sum(values)}/{n} = {mean}.", {"values": values})


def av_replace(r):
    n = r.choice([5, 6, 8, 10, 12])
    mean = r.randrange(20, 60)
    old = r.randrange(10, 50)
    new = old + r.choice([-1, 1]) * r.choice([n, 2 * n, 3 * n, n // 2 if n % 2 == 0 else n])
    if new <= 0:
        raise Retry("new")
    ans = mean + Fraction(new - old, n)
    return _num_q(r, "average", "av_replace",
                  f"The average of {n} numbers is {mean}. If one number, {old}, is replaced by {new}, what is the new average?", ans,
                  [mean + new - old, mean + Fraction(new - old, n + 1), mean, mean + Fraction(old - new, n)],
                  f"Total changes by {new} - {old} = {new - old}, so the average changes by {new - old}/{n} = {num(Fraction(new - old, n))}; "
                  f"new average = {num(ans)}.", {"n": n, "mean": mean, "old": old, "new": new})


def av_teacher(r):
    n = r.choice([19, 24, 29, 34, 39])
    age = r.choice([12, 13, 14, 15, 16])
    d = r.choice([1, 2])
    teacher = (n + 1) * (age + d) - n * age
    return _num_q(r, "average", "av_teacher",
                  f"The average age of {n} students is {age} years. When the teacher's age is included, the average rises by {d} year{'s' if d > 1 else ''}. What is the teacher's age?",
                  teacher, [age + d, (n + 1) * d, n * d + age, teacher - n],
                  f"Total age with teacher = {n + 1} x {age + d} = {(n + 1) * (age + d)}; students' total = {n} x {age} = {n * age}; teacher = {teacher} years.",
                  {"n": n, "age": age, "d": d}, fmt=lambda v: num(v) + " years")


def av_natural(r):
    n = r.choice([9, 15, 21, 25, 31, 49, 99, 15])
    which = r.choice(["natural numbers", "odd numbers", "even numbers"])
    ans = {"natural numbers": Fraction(n + 1, 2), "odd numbers": Fraction(n), "even numbers": Fraction(n + 1)}[which]
    return _num_q(r, "average", "av_natural", f"What is the average of the first {n} {which}?", ans,
                  [Fraction(n, 2), n + 1, Fraction(n + 1, 2), n - 1, Fraction(n * n, 2)],
                  {"natural numbers": f"Sum = {n}({n}+1)/2, so the average = ({n}+1)/2 = {num(ans)}.",
                   "odd numbers": f"The sum of the first {n} odd numbers is {n}^2, so the average is {n}^2/{n} = {n}.",
                   "even numbers": f"The sum of the first {n} even numbers is {n}({n}+1), so the average is {n}+1 = {n + 1}."}[which],
                  {"n": n, "which": which})


def av_middle(r):
    a = r.randrange(20, 50)
    b = r.randrange(10, 60)
    c = r.randrange(10, 60)
    mid = 5 * a - 2 * b - 2 * c
    if not 1 <= mid <= 100:
        raise Retry("mid")
    return _num_q(r, "average", "av_middle",
                  f"The average of five numbers is {a}. The average of the first two is {b} and of the last two is {c}. Find the middle number.",
                  mid, [5 * a - b - c, mid + 5, 5 * a - 2 * b, a * 2 - b - c],
                  f"Total = 5 x {a} = {5 * a}; first two = {2 * b}; last two = {2 * c}; middle = {5 * a} - {2 * b} - {2 * c} = {mid}.",
                  {"a": a, "b": b, "c": c})


# --------------------------------------------------------------------------- time and work
_TW_PAIRS = [(x, y) for x in range(4, 49) for y in range(x + 1, 61) if (x * y) % (x + y) == 0]
_PIPE_PAIRS = [(x, y) for x in range(2, 31) for y in range(x + 1, 61) if (x * y) % (y - x) == 0 and y - x < x + 5]


def tw_together(r):
    x, y = r.choice(_TW_PAIRS)
    t = Fraction(x * y, x + y)
    return _num_q(r, "time_work", "tw_together",
                  f"A can do a piece of work in {x} days and B can do it in {y} days. In how many days can they finish it working together?", t,
                  [Fraction(x + y, 2), x + y, y - x, t + 1, x * y // math.gcd(x, y)],
                  f"In one day they do 1/{x} + 1/{y} = {x + y}/{x * y} of the work, so the work takes {x * y}/{x + y} = {num(t)} days.",
                  {"x": x, "y": y}, fmt=lambda v: num(v) + " days")


def tw_other(r):
    x, y = r.choice(_TW_PAIRS)
    if r.random() < 0.5:
        x, y = y, x
    t = Fraction(x * y, x + y)
    return _num_q(r, "time_work", "tw_other",
                  f"A and B together can finish a work in {num(t)} days. A alone can finish it in {x} days. In how many days can B alone finish it?", y,
                  [abs(x - t), x + t, t, x * 2, Fraction(x * t, x + t)],
                  f"B's one-day work = 1/{num(t)} - 1/{x} = 1/{y}, so B alone takes {y} days.", {"x": x, "t": t, "y": y}, fmt=lambda v: num(v) + " days")


def tw_leave(r):
    x, y = r.choice(_TW_PAIRS)
    t = Fraction(x * y, x + y)
    d = r.randrange(2, max(3, int(t)))
    if d >= t:
        raise Retry("d")
    left = 1 - d * Fraction(x + y, x * y)
    ans = left * y
    return _num_q(r, "time_work", "tw_leave",
                  f"A can do a work in {x} days and B in {y} days. They work together for {d} days and then A leaves. In how many more days will B finish the rest?",
                  ans, [y - d, left * x, Fraction(y, 2), ans + 1],
                  f"Work done together in {d} days = {d} x {x + y}/{x * y} = {1 - left}; remaining = {left}; B needs {left} x {y} = {num(ans)} days.",
                  {"x": x, "y": y, "d": d}, fmt=lambda v: num(v) + " days")


def tw_pipes(r):
    x, y = r.choice(_PIPE_PAIRS)
    t = Fraction(x * y, y - x)
    return _num_q(r, "time_work", "tw_pipes",
                  f"A tap can fill a tank in {x} hours and an outlet pipe can empty the full tank in {y} hours. If both are opened together, in how many hours is the empty tank filled?",
                  t, [Fraction(x * y, x + y), y - x, x + y, t + 1],
                  f"Net filling in one hour = 1/{x} - 1/{y} = {y - x}/{x * y}, so the tank fills in {x * y}/{y - x} = {num(t)} hours.",
                  {"x": x, "y": y}, fmt=lambda v: num(v) + " hours")


def tw_efficiency(r):
    k = r.choice([2, 3, 4])
    t = k * r.choice([3, 4, 5, 6, 8])
    a_alone = Fraction((k + 1) * t, k)
    return _num_q(r, "time_work", "tw_efficiency",
                  f"A is {k} times as efficient as B. Together they finish a work in {t} days. In how many days can A alone finish it?", a_alone,
                  [(k + 1) * t, t * k, t + k, Fraction(t, k)],
                  f"With B's rate as 1 unit/day, A's is {k}; total work = {k + 1} x {t} = {(k + 1) * t} units. A alone: {(k + 1) * t}/{k} = {num(a_alone)} days.",
                  {"k": k, "t": t}, fmt=lambda v: num(v) + " days")


def tw_men(r):
    m = r.choice([6, 8, 10, 12, 15, 20])
    d = r.choice([6, 8, 9, 10, 12, 15, 18, 20, 24])
    m2 = r.choice([3, 4, 5, 6, 9, 12, 18, 24, 30, 40])
    if m2 == m or (m * d) % m2:
        raise Retry("not whole")
    ans = m * d // m2
    return _num_q(r, "time_work", "tw_men", f"{m} men can complete a work in {d} days. In how many days will {m2} men complete the same work?", ans,
                  [d + (m2 - m), d * m2 // m if (d * m2) % m == 0 else d + 2, d, ans + 2],
                  f"Total work = {m} x {d} = {m * d} man-days; with {m2} men it takes {m * d}/{m2} = {ans} days.", {"m": m, "d": d, "m2": m2},
                  fmt=lambda v: num(v) + " days")


# --------------------------------------------------------------------------- work and wages


def ww_two(r):
    x, y = r.choice(_TW_PAIRS)
    unit = r.choice([20, 25, 40, 50, 60, 100])
    w = (x + y) * unit
    share = y * unit
    return _num_q(r, "work_wages", "ww_two",
                  f"A alone can finish a work in {x} days and B alone in {y} days. They finish it together and are paid Rs {w}. What is A's share?", share,
                  [x * unit, Fraction(w, 2), w - share, share + unit],
                  f"Wages are shared in the ratio of work done, i.e. of one-day work: 1/{x} : 1/{y} = {y} : {x}. A gets {y}/{x + y} of Rs {w} = Rs {share}.",
                  {"x": x, "y": y, "w": w}, fmt=rs)


def ww_three(r):
    x, y, z = r.sample([6, 8, 10, 12, 15, 20, 24, 30], 3)
    s = y * z + x * z + x * y
    m = r.choice([5, 10])
    w = s * m
    share = x * y * m
    return _num_q(r, "work_wages", "ww_three",
                  f"A, B and C can do a job alone in {x}, {y} and {z} days. Working together they finish it and are paid Rs {w} in total. What is C's share?", share,
                  [Fraction(w, 3), Fraction(w * x, x + y + z), z * m * 10, share + w // 20],
                  f"One-day work is 1/{x}, 1/{y} and 1/{z}. Multiplying by {x * y * z}: {y * z}, {x * z}, {x * y}; total {s}. C's share = {x * y}/{s} x {w} = Rs {share}.",
                  {"x": x, "y": y, "z": z, "w": w}, fmt=rs)


def ww_manhours(r):
    m1, h1, d1 = r.choice([8, 10, 12, 15]), r.choice([6, 8, 9]), r.choice([6, 10, 12, 15])
    m2, h2, d2 = r.choice([5, 6, 9, 10, 20]), r.choice([4, 6, 8]), r.choice([5, 8, 9, 12])
    per = r.choice([5, 8, 10, 12, 15])
    x = m1 * h1 * d1 * per
    ans = m2 * h2 * d2 * per
    if ans == x:
        raise Retry("same")
    return _num_q(
        r, "work_wages", "ww_manhours",
        f"{m1} men working {h1} hours a day for {d1} days earn Rs {x}. How much will {m2} men working {h2} hours a day for {d2} days earn at the same rate?",
        ans, [Fraction(x * m2, m1), Fraction(x * m2 * d2, m1 * d1), x + (m2 - m1) * per, ans + per * 10],
        f"Wages are proportional to man-hours. Pay per man-hour = {x}/({m1} x {h1} x {d1}) = Rs {per}; new man-hours = {m2} x {h2} x {d2} = {m2 * h2 * d2}; wages = Rs {ans}.",
        {"m1": m1, "h1": h1, "d1": d1, "m2": m2, "h2": h2, "d2": d2, "x": x}, fmt=rs)


def ww_total(r):
    a, b = r.choice([300, 350, 400, 450, 500, 600]), r.choice([200, 250, 300, 150])
    s, h, d = r.choice([2, 3, 4, 5]), r.choice([3, 4, 6, 8]), r.choice([4, 5, 6, 8, 10])
    ans = d * (s * a + h * b)
    return _num_q(r, "work_wages", "ww_total",
                  f"A contractor pays Rs {a} a day to each skilled worker and Rs {b} a day to each helper. What does a {d}-day job cost with {s} skilled workers and {h} helpers?",
                  ans, [s * a + h * b, d * (s + h) * a, d * (s * a + h * a), ans + 2 * b],
                  f"Daily wages = {s} x {a} + {h} x {b} = {s * a + h * b}; for {d} days = {s * a + h * b} x {d} = Rs {ans}.",
                  {"a": a, "b": b, "s": s, "h": h, "d": d}, fmt=rs)


# --------------------------------------------------------------------------- time and distance
_AVG_PAIRS = [(a, b) for a in (10, 12, 15, 20, 24, 25, 30, 36, 40, 45, 48, 50, 60, 72, 75, 80)
              for b in (10, 12, 15, 20, 24, 25, 30, 36, 40, 45, 48, 50, 60, 72, 75, 80)
              if a < b and (2 * a * b * 100) % (a + b) == 0]
_MPS = (10, 15, 20, 25, 30, 35, 40)  # metres per second; the speed in km/h is 18/5 of it


def td_avg(r):
    a, b = r.choice(_AVG_PAIRS)
    ans = Fraction(2 * a * b, a + b)
    return _num_q(r, "time_distance", "td_avg",
                  f"A man travels from his home to the office at {a} km/h and returns by the same road at {b} km/h. What is his average speed for the whole journey?", ans,
                  [Fraction(a + b, 2), b, Fraction(a * b, a + b), ans + 2],
                  f"For an equal distance each way, average speed = 2ab/(a + b) = 2 x {a} x {b}/({a} + {b}) = {num(ans)} km/h.", {"a": a, "b": b}, fmt=kmph)


def td_pole(r):
    mps = r.choice(_MPS)
    v = mps * 18 // 5
    t = r.randrange(5, 21)
    length = mps * t
    return _num_q(r, "time_distance", "td_pole",
                  f"A train {length} m long runs at {v} km/h. How long does it take to cross a signal post?", t,
                  [Fraction(length, v), t + 5, t * 2, t + 2],
                  f"{v} km/h = {v} x 5/18 = {mps} m/s. Time = {length}/{mps} = {t} seconds.", {"length": length, "v": v},
                  fmt=lambda x: num(x) + " seconds")


def td_platform(r):
    mps = r.choice(_MPS)
    v = mps * 18 // 5
    t = r.randrange(10, 41)
    total = mps * t
    length = r.randrange(2, max(3, total // 10)) * 10
    plat = total - length
    if plat <= 0:
        raise Retry("platform")
    return _num_q(r, "time_distance", "td_platform",
                  f"A train {length} m long, running at {v} km/h, crosses a platform {plat} m long. How much time does it take?", t,
                  [Fraction(length, mps), Fraction(plat, mps), t + 5, t + 1],
                  f"Distance = {length} + {plat} = {total} m; speed = {v} x 5/18 = {mps} m/s; time = {total}/{mps} = {t} seconds.",
                  {"length": length, "plat": plat, "v": v}, fmt=lambda x: num(x) + " seconds")


def td_two_trains(r):
    mps = r.choice(_MPS)
    rel = mps * 18 // 5
    t = r.randrange(4, 21)
    total = mps * t
    l1 = r.randrange(1, max(2, total // 10)) * 10
    l2 = total - l1
    if l2 <= 0:
        raise Retry("length")
    opposite = r.random() < 0.6
    if opposite:
        v1 = r.randrange(10, rel - 9)
        v2 = rel - v1
        text = f"Two trains {l1} m and {l2} m long run on parallel tracks in opposite directions at {v1} km/h and {v2} km/h. In how many seconds do they cross each other?"
        wrong = [t + 2, t * 2, t - 1, Fraction(total * 18, max(abs(v1 - v2), 1) * 5)]
        working = f"Relative speed = {v1} + {v2} = {rel} km/h = {mps} m/s; distance = {l1} + {l2} = {total} m; time = {total}/{mps} = {t} seconds."
    else:
        v2 = r.randrange(10, 60)
        v1 = v2 + rel
        text = f"Two trains {l1} m and {l2} m long run on parallel tracks in the same direction at {v1} km/h and {v2} km/h. In how many seconds does the faster one completely overtake the slower one?"
        wrong = [Fraction(total * 18, (v1 + v2) * 5), t + 2, t - 1, t * 2]
        working = f"Relative speed = {v1} - {v2} = {rel} km/h = {mps} m/s; distance = {l1} + {l2} = {total} m; time = {total}/{mps} = {t} seconds."
    return _num_q(r, "time_distance", "td_two_trains", text, t, wrong, working,
                  {"l1": l1, "l2": l2, "v1": v1, "v2": v2, "opposite": opposite}, fmt=lambda x: num(x) + " seconds")


def td_boat(r):
    u = r.choice([8, 10, 12, 15, 18, 20])
    s = r.choice([2, 3, 4, 5])
    if s >= u:
        raise Retry("stream")
    if r.random() < 0.5:
        down, up = u + s, u - s
        return _num_q(r, "time_distance", "td_boat_speed",
                      f"A boat goes at {down} km/h downstream and {up} km/h upstream. What is the speed of the boat in still water?", u,
                      [s, down, up, u + 1],
                      f"Speed in still water = (downstream + upstream)/2 = ({down} + {up})/2 = {u} km/h.", {"down": down, "up": up, "ask": "still"}, fmt=kmph)
    d = _lcm(u + s, u - s) * r.choice([1, 2])
    total = Fraction(d, u + s) + Fraction(d, u - s)
    return _num_q(r, "time_distance", "td_boat_time",
                  f"A boat's speed in still water is {u} km/h and the stream flows at {s} km/h. How long does it take to go {d} km downstream and come back?",
                  total, [Fraction(2 * d, u), Fraction(d, u + s) * 2, Fraction(d, u - s) * 2, total + 1],
                  f"Downstream speed = {u + s}, upstream speed = {u - s}. Time = {d}/{u + s} + {d}/{u - s} = {num(Fraction(d, u + s))} + {num(Fraction(d, u - s))} = {num(total)} hours.",
                  {"u": u, "s": s, "d": d}, fmt=lambda x: num(x) + " hours")


def td_overtake(r):
    v1 = r.choice([30, 40, 45, 50, 60])
    v2 = v1 + r.choice([10, 15, 20, 30])
    h = r.choice([1, 2, 3, 4])
    t = Fraction(h * v1, v2 - v1)
    return _num_q(r, "time_distance", "td_overtake",
                  f"A car leaves at {v1} km/h. {h} hour{'s' if h > 1 else ''} later a second car leaves the same place in the same direction at {v2} km/h. After how many hours from its start does the second car overtake the first?",
                  t, [Fraction(h * v2, v2 - v1), Fraction(v1, v2 - v1), t + h, Fraction(h * v1, v2)],
                  f"Head start = {v1} x {h} = {v1 * h} km; the gap closes at {v2} - {v1} = {v2 - v1} km/h; time = {v1 * h}/{v2 - v1} = {num(t)} hours.",
                  {"v1": v1, "v2": v2, "h": h}, fmt=lambda x: num(x) + " hours")


# --------------------------------------------------------------------------- clocks and calendars
WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
MONTH_DAYS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
FAMOUS_DATES = [
    ("the day India became independent", date(1947, 8, 15)), ("the day the Constitution came into force", date(1950, 1, 26)),
    ("the day the Constitution was adopted", date(1949, 11, 26)), ("the start of the Dandi March", date(1930, 3, 12)),
    ("the Jallianwala Bagh massacre", date(1919, 4, 13)), ("the formation of Andhra Pradesh state", date(1956, 11, 1)),
    ("the formation of Telangana state", date(2014, 6, 2)), ("the birth of Mahatma Gandhi", date(1869, 10, 2)),
]


def is_leap(y: int) -> bool:
    return y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)


def odd_days_working(d: date) -> tuple[int, str]:
    """(0 = Sunday ... 6 = Saturday, the working in 'odd days'). Counts from 1 January of year 1 (proleptic Gregorian)."""
    y = d.year - 1
    c, rest = divmod(y, 100)
    cent = {0: 0, 1: 5, 2: 3, 3: 1}[c % 4]
    yrs = rest + rest // 4
    months = sum(MONTH_DAYS[: d.month - 1]) + (1 if d.month > 2 and is_leap(d.year) else 0)
    total = cent + yrs + months + d.day
    text = (f"Odd days: {c * 100} years give {cent}; the next {rest} years ({rest // 4} leap) give {yrs} % 7 = {yrs % 7}; "
            f"the days in {d.year} up to {d.day} {MONTHS[d.month - 1]} give {months + d.day} % 7 = {(months + d.day) % 7}. "
            f"Total = {total} % 7 = {total % 7} (0 = Sunday, 1 = Monday, ...).")
    return total % 7, text


def _weekday_options(r, right: str) -> list[str]:
    i = WEEKDAYS.index(right)
    others = [WEEKDAYS[(i + k) % 7] for k in (1, -1, 2, -2, 3)]
    return others


def cc_angle(r):
    h = r.randrange(1, 13)
    m = r.choice([5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 8, 12, 18, 24, 36, 42, 48])
    hour_angle = Fraction(30 * (h % 12)) + Fraction(m, 2)
    diff = abs(hour_angle - 6 * m)
    ang = min(diff, 360 - diff)
    if ang == 0:
        raise Retry("zero")
    return _num_q(r, "clocks_calendars", "cc_angle", f"What is the smaller angle between the hands of a clock at {h}:{m:02d}?", ang,
                  [360 - ang, abs(30 * h - 6 * m), abs(30 * h - 5 * m), ang + 15, ang + 30 if ang + 30 < 180 else ang - 30, ang + Fraction(15, 2)],
                  f"Hour hand: {30 * (h % 12)} + {m}/2 = {num(hour_angle)} degrees from 12; minute hand: 6 x {m} = {6 * m} degrees. "
                  f"Difference = {num(diff)}; the smaller angle is {num(ang)} degrees.", {"h": h, "m": m}, fmt=lambda v: num(v) + " degrees", step=Fraction(15, 2))


def cc_coincide(r):
    h = r.randrange(1, 11)
    t = Fraction(60 * h, 11)
    right = f"{mixed(t)} minutes past {h}"
    wrong = [f"{mixed(t + Fraction(1, 11) * k)} minutes past {h}" for k in (-3, 3, 5)] + [f"{5 * h} minutes past {h}", f"{mixed(t + 1)} minutes past {h}"]
    return _make(r, "clocks_calendars", "cc_coincide", f"At what time between {h} and {h + 1} o'clock will the hands of a clock coincide?", right, wrong,
                 f"The minute hand gains 55 minute-spaces per hour. At {h} o'clock the gap is {5 * h} spaces, so the time is {5 * h} x 60/55 = {mixed(t)} minutes past {h}.",
                 {"h": h})


def cc_weekday_ref(r):
    y = r.randrange(2001, 2100)
    ref = date(y, r.randrange(1, 13), r.randrange(1, 29))
    delta = r.randrange(20, 700)
    target = date.fromordinal(ref.toordinal() + delta)
    ans = WEEKDAYS[target.weekday()]
    right = ans
    wrong = _weekday_options(r, right)
    rem = delta % 7
    fmt = lambda d: f"{d.day} {MONTHS[d.month - 1]} {d.year}"  # noqa: E731
    return _make(r, "clocks_calendars", "cc_weekday_ref", f"If {fmt(ref)} is a {WEEKDAYS[ref.weekday()]}, what day of the week is {fmt(target)}?", right, wrong,
                 f"The number of days from {fmt(ref)} to {fmt(target)} is {delta} = 7 x {delta // 7} + {rem}, so there are {rem} odd days: "
                 f"{WEEKDAYS[ref.weekday()]} + {rem} = {ans}.", {"ref": ref.isoformat(), "target": target.isoformat()})


def cc_weekday_famous(r):
    what, d = r.choice(FAMOUS_DATES)
    idx, working = odd_days_working(d)
    ans = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"][idx]
    return _make(r, "clocks_calendars", "cc_weekday_famous",
                 f"On which day of the week did {d.day} {MONTHS[d.month - 1]} {d.year} fall ({what})?", ans, _weekday_options(r, ans),
                 working + f" That is {ans}.", {"date": d.isoformat()})


def cc_leap(r):
    right = r.choice([y for y in range(1600, 2401) if is_leap(y) and r.random() < 0.5] or [2000])
    traps = [y for y in (1700, 1800, 1900, 2100, 2200, 2300) if y != right]
    plain = [y for y in range(1601, 2400) if y % 4 != 0]
    wrong = r.sample(traps, 2) + [r.choice(plain)]
    return _make(r, "clocks_calendars", "cc_leap", "Which of the following is a leap year?", str(right), [str(w) for w in wrong],
                 f"A year is a leap year if it is divisible by 4, except a century year, which must be divisible by 400. {right} qualifies; "
                 f"the century years {', '.join(str(w) for w in wrong if w % 100 == 0)} are not divisible by 400.", {"right": right, "wrong": wrong})


def cc_counts(r):
    kind, per12 = r.choice([("coincide", 11), ("point in opposite directions", 11), ("form a right angle", 22)])
    hours = r.choice([12, 24])
    ans = per12 * hours // 12
    return _num_q(r, "clocks_calendars", "cc_counts", f"How many times in {hours} hours do the hands of a clock {kind}?", ans,
                  [per12 * hours // 12 + hours // 12, per12 * hours // 12 - 2, hours, per12 * 2 * hours // 12 + 2, ans * 2],
                  f"In 12 hours the hands of a clock {kind} {per12} times, so in {hours} hours: {per12} x {hours // 12} = {ans}.", {"what": kind, "hours": hours})


# --------------------------------------------------------------------------- partnership


def pt_two(r):
    a, b = r.choice([(2, 3), (3, 4), (3, 5), (4, 5), (2, 5), (1, 3), (5, 7)])
    u = r.choice([1000, 2000, 2500, 3000, 4000])
    m = r.choice([300, 400, 500, 600, 800, 1000])
    x, y, profit = a * u, b * u, (a + b) * m
    return _num_q(r, "partnership", "pt_two",
                  f"A and B start a business with Rs {x} and Rs {y}. At the end of the year the profit is Rs {profit}. What is B's share?", b * m,
                  [a * m, Fraction(profit, 2), profit - a * m + m, b * m + m],
                  f"Profit is shared in the ratio of investments = {a} : {b}. B's share = {b}/{a + b} x {profit} = Rs {b * m}.", {"a": a, "b": b, "profit": profit}, fmt=rs)


def pt_months(r):
    x, y = r.choice([2000, 3000, 4000, 5000, 6000, 8000]), r.choice([3000, 4500, 6000, 9000, 10000])
    m, n = r.choice([12, 8, 6, 9, 10]), r.choice([12, 4, 6, 8, 9, 3])
    wa, wb = x * m, y * n
    g = math.gcd(wa, wb)
    pa, pb = wa // g, wb // g
    mult = r.choice([50, 100, 200, 250])
    profit = (pa + pb) * mult
    if profit > 200000:
        raise Retry("big")
    return _num_q(r, "partnership", "pt_months",
                  f"A invests Rs {x} for {m} months and B invests Rs {y} for {n} months. They earn a profit of Rs {profit}. What is B's share?", pb * mult,
                  [pa * mult, Fraction(profit, 2), Fraction(profit * y, x + y), pb * mult + mult],
                  f"Profit ratio = {x} x {m} : {y} x {n} = {wa} : {wb} = {pa} : {pb}. B's share = {pb}/{pa + pb} x {profit} = Rs {pb * mult}.",
                  {"x": x, "m": m, "y": y, "n": n, "profit": profit}, fmt=rs)


def pt_join(r):
    x = r.choice([4000, 5000, 6000, 8000, 10000])
    y = r.choice([6000, 8000, 9000, 10000, 12000])
    k = r.choice([2, 3, 4, 6])
    wa, wb = x * 12, y * (12 - k)
    right = ratio_text(wa, wb)
    wrong = [ratio_text(x, y), ratio_text(x * 12, y * k), ratio_text(x * 12, y * 12), ratio_text(x * (12 - k), y * 12)]
    return _make(r, "partnership", "pt_join",
                 f"A starts a business with Rs {x}. After {k} months B joins with Rs {y}. What is the ratio of their profits at the end of the year?", right, wrong,
                 f"A's investment lasts 12 months and B's {12 - k} months. Ratio = {x} x 12 : {y} x {12 - k} = {wa} : {wb} = {right}.", {"x": x, "y": y, "k": k})


def pt_find(r):
    x = r.choice([4000, 5000, 6000, 8000, 9000, 10000])
    y = r.choice([3000, 4000, 5000, 6000, 8000, 9000, 12000])
    k = r.choice([2, 3, 4, 6])
    wa, wb = x * 12, y * (12 - k)
    g = math.gcd(wa, wb)
    a, b = wa // g, wb // g
    return _num_q(r, "partnership", "pt_find",
                  f"A invests Rs {x} for a whole year. B joins after {k} months. At the end of the year their profits are in the ratio {a} : {b}. How much did B invest?", y,
                  [Fraction(x * b, a), Fraction(x * 12 * b, a * 12), Fraction(x * 12 * b, a * k), y + 1000],
                  f"{x} x 12 : B x {12 - k} = {a} : {b}, so B = {x} x 12 x {b}/({a} x {12 - k}) = Rs {y}.", {"x": x, "k": k, "a": a, "b": b}, fmt=rs)


def pt_three(r):
    x, y, z = r.sample([6000, 8000, 9000, 10000, 12000, 15000], 3)
    wa, wb, wc = x * 12, y * 8, z * 4
    g = math.gcd(math.gcd(wa, wb), wc)
    pa, pb, pc = wa // g, wb // g, wc // g
    mult = r.choice([100, 200, 250, 500])
    profit = (pa + pb + pc) * mult
    return _num_q(r, "partnership", "pt_three",
                  f"A invests Rs {x} at the start, B invests Rs {y} after 4 months and C invests Rs {z} after 8 months. The annual profit is Rs {profit}. What is C's share?",
                  pc * mult, [pa * mult, pb * mult, Fraction(profit, 3), pc * mult + mult],
                  f"Ratio = {x} x 12 : {y} x 8 : {z} x 4 = {pa} : {pb} : {pc} (dividing by {g}). C's share = {pc}/{pa + pb + pc} x {profit} = Rs {pc * mult}.",
                  {"x": x, "y": y, "z": z, "profit": profit}, fmt=rs)


# --------------------------------------------------------------------------- mensuration
TRIPLES = [(3, 4, 5), (5, 12, 13), (8, 15, 17), (7, 24, 25), (20, 21, 29), (9, 40, 41)]
BOXES = [(2, 3, 6, 7), (1, 4, 8, 9), (2, 10, 11, 15), (4, 4, 7, 9), (3, 4, 12, 13), (6, 6, 7, 11), (4, 8, 8, 12)]  # l, b, h, diagonal
PI = Fraction(22, 7)


def _heron_triangles() -> list[tuple[int, int, int, int]]:
    out = []
    for a in range(3, 31):
        for b in range(a, 31):
            for c in range(b, 31):
                if a + b <= c:
                    continue
                v = (a + b + c) * (-a + b + c) * (a - b + c) * (a + b - c)
                root = math.isqrt(v)
                if root * root == v and root % 4 == 0 and (a, b, c) not in [(a, a, a)]:
                    out.append((a, b, c, root // 4))
    return out


_HERON = _heron_triangles()


def me_rect(r):
    p, q, h = r.choice(TRIPLES)
    k = r.randrange(1, 5)
    ln, b, d = p * k, q * k, h * k
    which = r.choice(["diagonal", "area", "perimeter"])
    if which == "diagonal":
        return _num_q(r, "mensuration", "me_rect_diag", f"The sides of a rectangle are {ln} cm and {b} cm. Find the length of its diagonal.", d,
                      [ln + b, Fraction(ln + b, 2), d + k, d - k], f"Diagonal = sqrt({ln}^2 + {b}^2) = sqrt({ln * ln + b * b}) = {d} cm.",
                      {"l": ln, "b": b, "ask": "diagonal"}, fmt=lambda v: num(v) + " cm")
    if which == "area":
        return _num_q(r, "mensuration", "me_rect_area", f"The sides of a rectangle are {ln} cm and {b} cm. Find its area.", ln * b,
                      [2 * (ln + b), ln + b, ln * b + ln, (ln * b) // 2 if (ln * b) % 2 == 0 else ln * b + b], f"Area = {ln} x {b} = {ln * b} square cm.", {"l": ln, "b": b, "ask": "area"},
                      fmt=lambda v: num(v) + " sq cm")
    return _num_q(r, "mensuration", "me_rect_perimeter", f"The sides of a rectangle are {ln} cm and {b} cm. Find its perimeter.", 2 * (ln + b),
                  [ln + b, ln * b, 2 * ln * b, 2 * (ln + b) + 2], f"Perimeter = 2 x ({ln} + {b}) = {2 * (ln + b)} cm.", {"l": ln, "b": b, "ask": "perimeter"},
                  fmt=lambda v: num(v) + " cm")


def me_circle(r):
    rad = 7 * r.randrange(1, 7)
    which = r.choice(["area", "circumference", "radius"])
    if which == "area":
        ans = PI * rad * rad
        return _num_q(r, "mensuration", "me_circle_area", f"Find the area of a circle of radius {rad} cm (take pi = 22/7).", ans,
                      [2 * PI * rad, PI * 2 * rad * rad, PI * rad, ans + 22], f"Area = (22/7) x {rad} x {rad} = {num(ans)} sq cm.",
                      {"r": rad, "ask": "area"}, fmt=lambda v: num(v) + " sq cm")
    if which == "circumference":
        ans = 2 * PI * rad
        return _num_q(r, "mensuration", "me_circle_circ", f"Find the circumference of a circle of radius {rad} cm (take pi = 22/7).", ans,
                      [PI * rad * rad, PI * rad, ans + 22, ans - 22], f"Circumference = 2 x (22/7) x {rad} = {num(ans)} cm.",
                      {"r": rad, "ask": "circumference"}, fmt=lambda v: num(v) + " cm")
    c = 2 * PI * rad
    return _num_q(r, "mensuration", "me_circle_radius", f"The circumference of a circle is {num(c)} cm. Find its radius (take pi = 22/7).", rad,
                  [Fraction(rad, 2), rad * 2, c / 7, rad + 7], f"Radius = C/(2 pi) = {num(c)} x 7/(2 x 22) = {rad} cm.", {"c": c, "ask": "radius"},
                  fmt=lambda v: num(v) + " cm")


def me_cylinder(r):
    k = r.randrange(1, 5)
    rad = 7 * k
    h = r.randrange(2, 16)
    which = r.choice(["volume", "curved", "total"])
    if which == "volume":
        ans = PI * rad * rad * h
        return _num_q(r, "mensuration", "me_cyl_vol", f"Find the volume of a cylinder of radius {rad} cm and height {h} cm (take pi = 22/7).", ans,
                      [2 * PI * rad * h, PI * rad * h, ans / 3, ans + 22 * h],
                      f"Volume = pi r^2 h = (22/7) x {rad} x {rad} x {h} = {num(ans)} cubic cm.", {"r": rad, "h": h, "ask": "volume"},
                      fmt=lambda v: num(v) + " cubic cm")
    if which == "curved":
        ans = 2 * PI * rad * h
        return _num_q(r, "mensuration", "me_cyl_csa", f"Find the curved surface area of a cylinder of radius {rad} cm and height {h} cm (take pi = 22/7).", ans,
                      [PI * rad * rad * h, PI * rad * h, 2 * PI * rad * (h + rad), ans + 44], f"Curved surface area = 2 pi r h = 2 x (22/7) x {rad} x {h} = {num(ans)} sq cm.",
                      {"r": rad, "h": h, "ask": "curved"}, fmt=lambda v: num(v) + " sq cm")
    ans = 2 * PI * rad * (h + rad)
    return _num_q(r, "mensuration", "me_cyl_tsa", f"Find the total surface area of a closed cylinder of radius {rad} cm and height {h} cm (take pi = 22/7).", ans,
                  [2 * PI * rad * h, PI * rad * (h + rad), 2 * PI * rad * h + PI * rad * rad, ans + 44],
                  f"Total surface area = 2 pi r (h + r) = 2 x (22/7) x {rad} x ({h} + {rad}) = {num(ans)} sq cm.", {"r": rad, "h": h, "ask": "total"},
                  fmt=lambda v: num(v) + " sq cm")


def me_cuboid(r):
    ln, b, h, d = r.choice(BOXES)
    k = r.choice([1, 1, 2])
    ln, b, h, d = ln * k, b * k, h * k, d * k
    which = r.choice(["volume", "surface", "diagonal"])
    if which == "volume":
        return _num_q(r, "mensuration", "me_cuboid_vol", f"Find the volume of a cuboid {ln} cm long, {b} cm wide and {h} cm high.", ln * b * h,
                      [2 * (ln * b + b * h + h * ln), ln + b + h, ln * b * h + ln * b, ln * b + b * h + h * ln], f"Volume = {ln} x {b} x {h} = {ln * b * h} cubic cm.",
                      {"dims": [ln, b, h], "ask": "volume"}, fmt=lambda v: num(v) + " cubic cm")
    if which == "surface":
        ans = 2 * (ln * b + b * h + h * ln)
        return _num_q(r, "mensuration", "me_cuboid_tsa", f"Find the total surface area of a cuboid {ln} cm long, {b} cm wide and {h} cm high.", ans,
                      [ln * b + b * h + h * ln, ln * b * h, 2 * (ln * b + b * h), ans + 2 * ln], f"Total surface area = 2(lb + bh + hl) = 2({ln * b} + {b * h} + {h * ln}) = {ans} sq cm.",
                      {"dims": [ln, b, h], "ask": "surface"}, fmt=lambda v: num(v) + " sq cm")
    return _num_q(r, "mensuration", "me_cuboid_diag", f"Find the length of the diagonal of a cuboid {ln} cm by {b} cm by {h} cm.", d,
                  [ln + b + h, d + k, d - k, Fraction(ln + b + h, 2)], f"Diagonal = sqrt(ln^2 + b^2 + h^2) = sqrt({ln * ln} + {b * b} + {h * h}) = sqrt({d * d}) = {d} cm.",
                  {"dims": [ln, b, h], "ask": "diagonal"}, fmt=lambda v: num(v) + " cm")


def me_melt(r):
    b = r.choice([1, 2, 3, 4])
    n = r.choice([2, 3, 4, 5])
    a = b * n
    return _num_q(r, "mensuration", "me_melt", f"A solid metal cube of edge {a} cm is melted and recast into cubes of edge {b} cm. How many cubes are made?", n ** 3,
                  [n ** 2, n * 3, n ** 3 - 1, n ** 3 + n], f"Number = ({a}/{b})^3 = {n}^3 = {n ** 3}.", {"a": a, "b": b})


def me_triangle(r):
    a, b, c, area = r.choice(_HERON)
    return _num_q(r, "mensuration", "me_triangle", f"The sides of a triangle are {a} cm, {b} cm and {c} cm. Find its area.", area,
                  [Fraction(a * b, 2) if (a * b) % 2 == 0 else a + b, a + b + c, (a + b + c) // 2, area + a],
                  f"s = ({a} + {b} + {c})/2 = {Fraction(a + b + c, 2)}; area = sqrt(s(s - a)(s - b)(s - c)) = {area} sq cm.",
                  {"sides": [a, b, c]}, fmt=lambda v: num(v) + " sq cm")


def me_rhombus(r):
    p, q, h = r.choice(TRIPLES[:4])
    k = r.randrange(1, 4)
    d1, d2 = 2 * p * k, 2 * q * k
    side = h * k
    if r.random() < 0.5:
        return _num_q(r, "mensuration", "me_rhombus_area", f"The diagonals of a rhombus are {d1} cm and {d2} cm. Find its area.", Fraction(d1 * d2, 2),
                      [d1 * d2, d1 + d2, 4 * side, Fraction(d1 * d2, 4)], f"Area = (1/2) x d1 x d2 = (1/2) x {d1} x {d2} = {d1 * d2 // 2} sq cm.",
                      {"d1": d1, "d2": d2, "ask": "area"}, fmt=lambda v: num(v) + " sq cm")
    return _num_q(r, "mensuration", "me_rhombus_side", f"The diagonals of a rhombus are {d1} cm and {d2} cm. Find the length of its side.", side,
                  [Fraction(d1 + d2, 2), Fraction(d1 * d2, 2), side + k, d1 // 2 + d2 // 2 - k],
                  f"The diagonals bisect each other at right angles, so side = sqrt(({d1}/2)^2 + ({d2}/2)^2) = sqrt({p * k}^2 + {q * k}^2) = {side} cm.",
                  {"d1": d1, "d2": d2, "ask": "side"}, fmt=lambda v: num(v) + " cm")


def me_sphere(r):
    if r.random() < 0.5:
        rad = 7 * r.randrange(1, 4)
        ans = 4 * PI * rad * rad
        return _num_q(r, "mensuration", "me_sphere_area", f"Find the surface area of a sphere of radius {rad} cm (take pi = 22/7).", ans,
                      [PI * rad * rad, 2 * PI * rad * rad, Fraction(4, 3) * PI * rad ** 3, ans + 22],
                      f"Surface area = 4 pi r^2 = 4 x (22/7) x {rad} x {rad} = {num(ans)} sq cm.", {"r": rad, "ask": "area"}, fmt=lambda v: num(v) + " sq cm")
    rad = 21 * r.choice([1, 2])
    ans = Fraction(4, 3) * PI * rad ** 3
    return _num_q(r, "mensuration", "me_sphere_vol", f"Find the volume of a sphere of radius {rad} cm (take pi = 22/7).", ans,
                  [4 * PI * rad * rad, PI * rad ** 3, Fraction(2, 3) * PI * rad ** 3, ans + 1000],
                  f"Volume = (4/3) pi r^3 = (4/3) x (22/7) x {rad}^3 = {num(ans)} cubic cm.", {"r": rad, "ask": "volume"}, fmt=lambda v: num(v) + " cubic cm")


# --------------------------------------------------------------------------- number system


def _powers_text(a: int, m: int, n: int) -> tuple[int, str]:
    """(a^n mod m, the working: the cycle of remainders and where n falls in it)."""
    seq, seen = [], {}
    v = 1 % m
    k = 0
    while True:
        v = (v * a) % m
        k += 1
        if v in seen:
            mu = seen[v]  # 0-based index where the cycle starts
            break
        seen[v] = len(seq)
        seq.append(v)
    period = len(seq) - mu
    idx = n - 1 if n - 1 < mu else mu + (n - 1 - mu) % period
    text = (f"The remainders of {a}^1, {a}^2, ... divided by {m} are {', '.join(str(x) for x in seq)} and then repeat "
            f"(cycle length {period}" + (f", after the first {mu}" if mu else "") + f"). Position {n} in this list gives {seq[idx]}.")
    return seq[idx], text


def ns_hcf_lcm(r):
    g = r.choice([2, 3, 4, 5, 6, 7, 8, 9, 12, 15])
    ms = r.sample(range(2, 10), 3)
    count = r.choice([2, 3])
    ms = ms[:count]
    nums = [g * m for m in ms]
    hcf = math.gcd(*nums)
    lcm = nums[0]
    for v in nums[1:]:
        lcm = _lcm(lcm, v)
    listed = ", ".join(str(v) for v in nums)
    if r.random() < 0.5:
        return _num_q(r, "number_system", "ns_hcf", f"Find the HCF of {listed}.", hcf,
                      [lcm, math.prod(nums) // max(1, lcm), min(nums), hcf * 2, g + 1], f"Take out the common factor: {listed} = {hcf} x ({', '.join(str(v // hcf) for v in nums)}), and the quotients have no common factor. HCF = {hcf}.",
                      {"nums": nums, "ask": "hcf"})
    return _num_q(r, "number_system", "ns_lcm", f"Find the LCM of {listed}.", lcm, [math.prod(nums), hcf, max(nums) * 2, lcm + hcf, lcm - hcf],
                  f"HCF = {hcf}; the numbers are {hcf} x ({', '.join(str(v // hcf) for v in nums)}). LCM = {hcf} x lcm of the quotients = {lcm}.", {"nums": nums, "ask": "lcm"})


def ns_other(r):
    h = r.choice([2, 3, 4, 5, 6, 7, 8, 9])
    m, n = r.choice([(2, 3), (3, 4), (2, 5), (3, 5), (4, 5), (2, 7), (3, 7), (4, 7), (5, 6)])
    a, b, ln = h * m, h * n, h * m * n
    return _num_q(r, "number_system", "ns_other", f"The HCF of two numbers is {h} and their LCM is {ln}. If one number is {a}, what is the other?", b,
                  [Fraction(ln, a), h * ln, ln - a, b + h, Fraction(a * ln, h)],
                  f"Product of two numbers = HCF x LCM, so the other number = {h} x {ln}/{a} = {b}.", {"h": h, "l": ln, "a": a})


def ns_least_rem(r):
    xs = r.choice([(6, 8, 12), (4, 6, 9), (12, 15, 20), (5, 6, 8), (8, 12, 18), (10, 15, 20), (6, 9, 12), (4, 5, 6), (9, 12, 15)])
    rem = r.randrange(1, min(xs))
    lcm = _lcm(_lcm(xs[0], xs[1]), xs[2])
    return _num_q(r, "number_system", "ns_least_rem",
                  f"What is the least number which, when divided by {xs[0]}, {xs[1]} and {xs[2]}, leaves a remainder {rem} in each case?", lcm + rem,
                  [lcm, lcm - rem, lcm * 2 + rem, lcm + rem + xs[0]], f"LCM of {xs[0]}, {xs[1]}, {xs[2]} = {lcm}. Required number = LCM + remainder = {lcm} + {rem} = {lcm + rem}.",
                  {"xs": list(xs), "rem": rem})


def ns_greatest(r):
    h = r.randrange(6, 30)
    while True:
        p, q = r.randrange(3, 15), r.randrange(3, 15)
        if p != q and math.gcd(p, q) == 1:
            break
    r1, r2 = r.randrange(1, min(h, 6)), r.randrange(1, min(h, 6))
    a, b = h * p + r1, h * q + r2
    return _num_q(r, "number_system", "ns_greatest", f"What is the greatest number that divides {a} and {b} leaving remainders {r1} and {r2} respectively?", h,
                  [math.gcd(a, b), h - 1, h + 1, h * 2, math.gcd(a - r1, b)],
                  f"The number divides {a} - {r1} = {a - r1} and {b} - {r2} = {b - r2} exactly, so it is their HCF = {h}.", {"a": a, "b": b, "r1": r1, "r2": r2})


def ns_divisible(r):
    d = r.choice([3, 4, 6, 8, 9, 11])
    right = d * r.randrange(1000 // d, 99999 // d)
    wrong = []
    while len(wrong) < 3:
        v = r.randrange(1000, 99999)
        if v % d and v not in wrong:
            wrong.append(v)
    rule = {3: "the digit sum is a multiple of 3", 9: "the digit sum is a multiple of 9", 4: "the last two digits form a multiple of 4",
            8: "the last three digits form a multiple of 8", 6: "it is even and its digit sum is a multiple of 3",
            11: "the difference between the sums of alternate digits is 0 or a multiple of 11"}[d]
    return _make(r, "number_system", "ns_divisible", f"Which of the following numbers is divisible by {d}?", str(right), [str(w) for w in wrong],
                 f"A number is divisible by {d} when {rule}. Check: {right} = {d} x {right // d}; each of the others leaves a remainder when divided by {d}.",
                 {"d": d, "right": right, "wrong": wrong})


def ns_remainder(r):
    m = r.choice([3, 4, 5, 6, 7, 9, 10, 11, 13])
    a = r.randrange(2, 13)
    if math.gcd(a, m) != 1 and r.random() < 0.7:
        raise Retry("cycle")
    n = r.randrange(20, 250)
    ans, working = _powers_text(a, m, n)
    return _num_q(r, "number_system", "ns_remainder", f"What is the remainder when {a}^{n} is divided by {m}?", ans,
                  [(a * n) % m, (a % m), (ans + 1) % m, (ans + 2) % m, (n % m)], working, {"a": a, "n": n, "m": m}, positive=False, step=Fraction(1))


def ns_unit_digit(r):
    a, b = r.randrange(2, 10), r.randrange(2, 10)
    n, k = r.randrange(11, 200), r.randrange(11, 200)
    if r.random() < 0.5:
        ans = pow(a, n, 10)
        text = f"What is the unit digit of {a}^{n}?"
        _v, working = _powers_text(a, 10, n)
        meta = {"a": a, "n": n}
    else:
        ans = (pow(a, n, 10) * pow(b, k, 10)) % 10
        text = f"What is the unit digit of {a}^{n} x {b}^{k}?"
        u1, w1 = _powers_text(a, 10, n)
        u2, w2 = _powers_text(b, 10, k)
        working = f"For {a}^{n}: {w1} For {b}^{k}: {w2} Unit digit of the product = last digit of {u1} x {u2} = {ans}."
        meta = {"a": a, "n": n, "b": b, "k": k}
    return _num_q(r, "number_system", "ns_unit", text, ans, [(ans + 1) % 10, (ans + 3) % 10, (ans + 5) % 10, (ans + 7) % 10, (ans * 3) % 10],
                  working, meta, positive=False, step=Fraction(1))


def ns_sum(r):
    n = r.randrange(8, 60)
    kind = r.choice(["natural", "odd", "even", "squares", "cubes"])
    if kind == "natural":
        ans, text, why = n * (n + 1) // 2, f"Find the sum of the first {n} natural numbers.", f"n(n + 1)/2 = {n} x {n + 1}/2"
    elif kind == "odd":
        ans, text, why = n * n, f"Find the sum of the first {n} odd numbers.", f"n^2 = {n}^2"
    elif kind == "even":
        ans, text, why = n * (n + 1), f"Find the sum of the first {n} even numbers.", f"n(n + 1) = {n} x {n + 1}"
    elif kind == "squares":
        ans, text, why = n * (n + 1) * (2 * n + 1) // 6, f"Find the sum of the squares of the first {n} natural numbers.", f"n(n + 1)(2n + 1)/6 = {n} x {n + 1} x {2 * n + 1}/6"
    else:
        ans, text, why = (n * (n + 1) // 2) ** 2, f"Find the sum of the cubes of the first {n} natural numbers.", f"(n(n + 1)/2)^2 = ({n * (n + 1) // 2})^2"
    return _num_q(r, "number_system", "ns_sum", text, ans, [n * (n + 1), n * n, n * (n + 1) // 2, ans + n, ans - n],
                  f"Using the formula {why} = {ans}.", {"n": n, "form": kind})


def ns_count(r):
    d = r.choice([3, 4, 5, 6, 7, 8, 9, 11, 12, 13])
    lo = r.randrange(1, 200)
    hi = lo + r.randrange(100, 900)
    ans = hi // d - (lo - 1) // d
    return _num_q(r, "number_system", "ns_count", f"How many numbers from {lo} to {hi} (both included) are divisible by {d}?", ans,
                  [(hi - lo) // d, (hi - lo) // d + 1 if (hi - lo) // d + 1 != ans else ans + 1, hi // d, ans - 1, ans + 1],
                  f"Multiples of {d} up to {hi}: {hi // d}; up to {lo - 1}: {(lo - 1) // d}. Count = {hi // d} - {(lo - 1) // d} = {ans}.", {"lo": lo, "hi": hi, "d": d})


# --------------------------------------------------------------------------- series


def _letters(*pos: int) -> str:
    return ", ".join(chr(64 + p) for p in pos)


def _letter_opts(r, ans_pos: int) -> list[str]:
    cands = [ans_pos + k for k in (1, -1, 2, -2, 3, -3)]
    return [chr(64 + c) for c in cands if 1 <= c <= 26]


def se_arith(r):
    a, d = r.randrange(2, 41), r.randrange(2, 16)
    t = [a + k * d for k in range(5)]
    return _num_q(r, "series", "se_arith", f"Find the next term: {', '.join(map(str, t))}, ?", a + 5 * d, [a + 5 * d + 1, a + 4 * d + 1, t[-1] + d + 2, t[-1] * 2 - t[-2] + d],
                  f"Each term is {d} more than the one before, so the next is {t[-1]} + {d} = {a + 5 * d}.", {"terms": t, "rule": "arith", "d": d})


def se_geo(r):
    a, m = r.randrange(1, 6), r.choice([2, 3, 4])
    t = [a * m ** k for k in range(5)]
    return _num_q(r, "series", "se_geo", f"Find the next term: {', '.join(map(str, t))}, ?", a * m ** 5, [t[-1] + t[-1] - t[-2], t[-1] * (m + 1), t[-1] * m + m, t[-1] * m - 1],
                  f"Each term is {m} times the one before, so the next is {t[-1]} x {m} = {a * m ** 5}.", {"terms": t, "rule": "geo", "m": m})


def se_square(r):
    s, c = r.randrange(2, 12), r.choice([-3, -2, -1, 0, 1, 2, 3, 5])
    t = [(s + k) ** 2 + c for k in range(5)]
    ans = (s + 5) ** 2 + c
    return _num_q(r, "series", "se_square", f"Find the next term: {', '.join(map(str, t))}, ?", ans, [ans + 2, ans - 2, t[-1] + (t[-1] - t[-2]), ans + 2 * (s + 5)],
                  f"The terms are squares {'plus' if c >= 0 else 'minus'} {abs(c)}: ({s})^2, ({s + 1})^2, ... so the next is ({s + 5})^2 + ({c}) = {ans}.",
                  {"terms": t, "rule": "square", "s": s, "c": c})


def se_second(r):
    a, d, e = r.randrange(1, 20), r.randrange(1, 6), r.randrange(1, 5)
    t = [a]
    for k in range(5):
        t.append(t[-1] + d + k * e)
    shown = t[:5]
    ans = t[5]
    diffs = [shown[i + 1] - shown[i] for i in range(4)]
    return _num_q(r, "series", "se_second", f"Find the next term: {', '.join(map(str, shown))}, ?", ans, [shown[-1] + diffs[-1], ans + e, ans - e, shown[-1] + diffs[-1] + 2 * e],
                  f"The differences are {', '.join(map(str, diffs))}: each is {e} more than the last, so the next difference is {diffs[-1] + e} and the next term is {shown[-1]} + {diffs[-1] + e} = {ans}.",
                  {"terms": shown, "rule": "second", "d": d, "e": e})


def se_fib(r):
    a, b = r.randrange(1, 8), r.randrange(1, 9)
    t = [a, b]
    for _ in range(4):
        t.append(t[-1] + t[-2])
    shown, ans = t[:5], t[5]
    return _num_q(r, "series", "se_fib", f"Find the next term: {', '.join(map(str, shown))}, ?", ans, [shown[-1] * 2, ans + 1, ans - 1, shown[-1] + shown[-3]],
                  f"Each term is the sum of the two terms before it: {shown[-2]} + {shown[-1]} = {ans}.", {"terms": shown, "rule": "fib"})


def se_muladd(r):
    a, m, c = r.randrange(1, 6), r.choice([2, 3]), r.choice([-2, -1, 1, 2, 3])
    t = [a]
    for _ in range(5):
        t.append(t[-1] * m + c)
    shown, ans = t[:5], t[5]
    return _num_q(r, "series", "se_muladd", f"Find the next term: {', '.join(map(str, shown))}, ?", ans, [shown[-1] * m, shown[-1] * m - c, ans + 1, ans - 1],
                  f"The rule is 'multiply by {m}, then {'add' if c > 0 else 'subtract'} {abs(c)}': {shown[-1]} x {m} + ({c}) = {ans}.", {"terms": shown, "rule": "muladd", "m": m, "c": c})


def se_letter_step(r):
    d = r.choice([1, 2, 3, 4])
    s = r.randrange(1, 26 - 5 * d + 1)
    pos = [s + k * d for k in range(5)]
    ans = s + 5 * d
    return _make(r, "series", "se_letter_step", f"Find the next letter: {_letters(*pos)}, ?", chr(64 + ans), _letter_opts(r, ans),
                 f"Positions in the alphabet: {', '.join(map(str, pos))}; each letter is {d} place{'s' if d > 1 else ''} after the last, so the next position is {ans}, the letter {chr(64 + ans)}.",
                 {"pos": pos, "rule": "letter_step", "d": d})


def se_letter_alt(r):
    a, b = r.choice([(2, 3), (1, 3), (3, 2), (2, 4), (3, 1), (1, 2), (4, 2)])
    p0 = r.randrange(1, 26 - (3 * a + 2 * b))
    pos = [p0]
    for k in range(5):
        pos.append(pos[-1] + (a if k % 2 == 0 else b))
    shown, ans = pos[:5], pos[5]
    return _make(r, "series", "se_letter_alt", f"Find the next letter: {_letters(*shown)}, ?", chr(64 + ans), _letter_opts(r, ans),
                 f"Positions: {', '.join(map(str, shown))}. The gaps alternate +{a}, +{b}, +{a}, +{b}, so the next gap is +{a}: position {ans}, the letter {chr(64 + ans)}.",
                 {"pos": shown, "rule": "letter_alt", "a": a, "b": b})


def se_letter_pair(r):
    dx, dy = r.choice([(1, -1), (1, 1), (2, 2), (2, -2), (3, 3), (1, 2)])
    for _ in range(50):
        x0, y0 = r.randrange(1, 27), r.randrange(1, 27)
        xs = [x0 + k * dx for k in range(5)]
        ys = [y0 + k * dy for k in range(5)]
        if all(1 <= v <= 26 for v in xs + ys):
            break
    else:
        raise Retry("pairs")
    shown = [chr(64 + x) + chr(64 + y) for x, y in zip(xs[:4], ys[:4], strict=True)]
    ans = chr(64 + xs[4]) + chr(64 + ys[4])
    wrong = [chr(64 + xs[4]) + chr(64 + min(26, max(1, ys[4] + 1))), chr(64 + min(26, xs[4] + 1)) + chr(64 + ys[4]), chr(64 + ys[4]) + chr(64 + xs[4]),
             chr(64 + xs[3]) + chr(64 + ys[4])]
    return _make(r, "series", "se_letter_pair", f"Find the next pair: {', '.join(shown)}, ?", ans, wrong,
                 f"The first letters move by {dx:+d} each time and the second letters by {dy:+d}. After {shown[-1]} comes {ans}.",
                 {"xs": xs[:4], "ys": ys[:4], "rule": "letter_pair", "dx": dx, "dy": dy})


# --------------------------------------------------------------------------- coding and decoding
CODE_WORDS = ["CAT", "DOG", "PEN", "BOOK", "MILK", "LAMP", "FISH", "TREE", "RAIN", "SHIP", "DOOR", "GOLD", "SAND", "KITE", "ROAD", "WIND"]


def _shift(word: str, k: int) -> str:
    return "".join(chr((ord(c) - 65 + k) % 26 + 65) for c in word)


def _mirror(word: str) -> str:
    return "".join(chr(90 - (ord(c) - 65)) for c in word)


def cd_shift(r):
    w1, w2 = r.sample(CODE_WORDS, 2)
    k = r.choice([1, 2, 3, 4, 5, -1, -2, -3])
    c1, c2 = _shift(w1, k), _shift(w2, k)
    right = c2
    wrong = [_shift(w2, k + 1), _shift(w2, k - 1), _shift(w2, -k), _mirror(w2), _shift(w2, k + 2)]
    return _make(r, "coding_decoding", "cd_shift", f"In a certain code, {w1} is written as {c1}. How is {w2} written in that code?", right, wrong,
                 f"Each letter of {w1} moves {abs(k)} place{'s' if abs(k) > 1 else ''} {'forward' if k > 0 else 'back'} ({w1[0]} -> {c1[0]}). "
                 f"Applying the same rule to {w2} gives {c2}.", {"w1": w1, "w2": w2, "k": k})


def cd_mirror(r):
    w1, w2 = r.sample(CODE_WORDS, 2)
    right = _mirror(w2)
    wrong = [_shift(w2, 1), _shift(w2, -1), w2[::-1], _mirror(w2[::-1])]
    return _make(r, "coding_decoding", "cd_mirror", f"In a certain code, {w1} is written as {_mirror(w1)}. How is {w2} written in that code?", right, wrong,
                 f"Each letter is replaced by its mirror letter in the alphabet (A <-> Z, B <-> Y, ...): {w1[0]} -> {_mirror(w1)[0]}. So {w2} becomes {right}.",
                 {"w1": w1, "w2": w2})


def cd_value(r):
    w = r.choice(CODE_WORDS + ["PAPER", "STONE", "WATER", "PLANT", "CLOTH"])
    total = sum(ord(c) - 64 for c in w)
    parts = " + ".join(str(ord(c) - 64) for c in w)
    return _num_q(r, "coding_decoding", "cd_value", f"If A = 1, B = 2, C = 3, ... Z = 26, what is the sum of the values of the letters of the word {w}?", total,
                  [total + 1, total - 1, total + 2, total - 2, total + 10], f"{' + '.join(f'{c}({ord(c) - 64})' for c in w)} = {parts} = {total}.", {"w": w})


_FAKE_CODES = ["ta", "ko", "mi", "pu", "ze", "lo", "ra", "vi", "su", "ne", "ba", "di"]
_CODE_MEANINGS = ["red", "blue", "green", "sun", "moon", "sky", "tree", "book", "pen", "road", "sand", "rain"]


def cd_word(r):
    words = r.sample(_CODE_MEANINGS, 3)
    codes = r.sample(_FAKE_CODES, 4)
    w1, w2, w3 = words
    c1, c2, c3 = codes[:3]
    s1 = [c1, c2]
    s2 = [c2, c3]
    r.shuffle(s1)
    r.shuffle(s2)
    ask = r.choice([0, 2])
    target = [w1, w3][ask // 2]
    right = [c1, c3][ask // 2]
    wrong = [c2, [c3, c1][ask // 2], codes[3]]
    q = (f"In a certain code, '{w1} {w2}' is written as '{' '.join(s1)}' and '{w2} {w3}' is written as '{' '.join(s2)}'. "
         f"What is the code for '{target}'?")
    return _make(r, "coding_decoding", "cd_word", q, right, wrong,
                 f"'{w2}' is common to both sentences, and '{c2}' is common to both codes, so '{w2}' = '{c2}'. "
                 f"Then '{target}' = the other code in its sentence = '{right}'.", {"w": words, "c": [c1, c2, c3], "ask": target, "s1": s1, "s2": s2})


# --------------------------------------------------------------------------- blood relations
# (phrase, gender of the person described, relation, gender the speaker must have or "")
KIN = [
    ("my father's father", "m", "Grandfather", ""), ("my mother's mother", "f", "Grandmother", ""),
    ("my father's mother", "f", "Grandmother", ""), ("my mother's father", "m", "Grandfather", ""),
    ("my father's brother", "m", "Uncle", ""), ("my mother's brother", "m", "Uncle", ""),
    ("my father's sister", "f", "Aunt", ""), ("my mother's sister", "f", "Aunt", ""),
    ("my father's sister's husband", "m", "Uncle", ""), ("my mother's brother's wife", "f", "Aunt", ""),
    ("my father's brother's son", "m", "Cousin", ""), ("my mother's sister's daughter", "f", "Cousin", ""),
    ("my father's sister's son", "m", "Cousin", ""), ("my brother's son", "m", "Nephew", ""), ("my sister's daughter", "f", "Niece", ""),
    ("my brother's daughter", "f", "Niece", ""), ("my sister's son", "m", "Nephew", ""), ("my son's son", "m", "Grandson", ""),
    ("my daughter's daughter", "f", "Granddaughter", ""), ("my son's wife", "f", "Daughter-in-law", ""),
    ("my daughter's husband", "m", "Son-in-law", ""), ("my sister's husband", "m", "Brother-in-law", ""),
    ("my brother's wife", "f", "Sister-in-law", ""), ("my wife's brother", "m", "Brother-in-law", "m"),
    ("my wife's father", "m", "Father-in-law", "m"), ("my husband's mother", "f", "Mother-in-law", "f"),
    ("my husband's sister", "f", "Sister-in-law", "f"), ("my wife's sister", "f", "Sister-in-law", "m"),
]
# (statement 1, statement 2, relation of A to C); A, B, C are three people
KIN_CHAIN = [
    ("A is the father of B. B is the sister of C.", "Father"), ("A is the brother of B. B is the mother of C.", "Uncle"),
    ("A is the sister of B. B is the father of C.", "Aunt"), ("A is the son of B. B is the brother of C.", "Nephew"),
    ("A is the daughter of B. B is the sister of C.", "Niece"), ("A is the mother of B. B is the brother of C.", "Mother"),
    ("A is the wife of B. B is the son of C.", "Daughter-in-law"), ("A is the husband of B. B is the daughter of C.", "Son-in-law"),
    ("A is the father of B. B is the mother of C.", "Grandfather"), ("A is the mother of B. B is the father of C.", "Grandmother"),
    ("A is the brother of B. B is the wife of C.", "Brother-in-law"), ("A is the sister of B. B is the husband of C.", "Sister-in-law"),
    ("A is the son of B. B is the daughter of C.", "Grandson"), ("A is the daughter of B. B is the son of C.", "Granddaughter"),
    ("A is the brother of B. B is the sister of C.", "Brother"),
]
KIN_TERMS = sorted({k[2] for k in KIN} | {k[1] for k in KIN_CHAIN})


def bl_phrase(r):
    phrase, tg, rel, sg = r.choice(KIN)
    speaker = r.choice(NAMES_F if sg == "f" else NAMES_M if sg == "m" else NAMES_M + NAMES_F)
    pron = "He" if tg == "m" else "She"
    noun = "man" if tg == "m" else "woman"
    text = f"Pointing to a {noun}, {speaker} said, \"{pron} is {phrase}.\" How is the {noun} related to {speaker}?"
    wrong = r.sample([t for t in KIN_TERMS if t != rel], 6)
    return _make(r, "blood_relations", "bl_phrase", text, rel, wrong, f"{phrase.replace('my', speaker + chr(39) + 's', 1).capitalize()} is {speaker}'s {rel.lower()}, so the {noun} is {speaker}'s {rel.lower()}.",
                 {"phrase": phrase, "gender": tg, "answer": rel})


def bl_chain(r):
    stmt, rel = r.choice(KIN_CHAIN)
    a, b, c = r.sample("PQRSTUVWXYZ", 3)
    text = stmt.replace("A", a).replace("B", b).replace("C", c)
    wrong = r.sample([t for t in KIN_TERMS if t != rel], 6)
    return _make(r, "blood_relations", "bl_chain", f"{text} How is {a} related to {c}?", rel, wrong,
                 f"Follow the chain from {a} to {b} to {c}: {text} Putting the two together, {a} is {c}'s {rel.lower()}.", {"stmt": stmt, "answer": rel, "letters": [a, b, c]})


# --------------------------------------------------------------------------- direction sense
COMPASS = ["North", "East", "South", "West"]
VEC = [(0, 1), (1, 0), (0, -1), (-1, 0)]
EIGHT = ["North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West"]


def _walk(start: int, legs: list[tuple[str, int]]) -> tuple[int, int, int]:
    """(dx, dy, final heading index). legs = [(turn, distance)]; turn is 'none' for the first leg, else 'left', 'right' or 'back'."""
    h, x, y = start, 0, 0
    for turn, dist in legs:
        h = (h + {"none": 0, "left": -1, "right": 1, "back": 2}[turn]) % 4
        x += VEC[h][0] * dist
        y += VEC[h][1] * dist
    return x, y, h


def _direction_word(dx: int, dy: int) -> str:
    ns = "North" if dy > 0 else "South" if dy < 0 else ""
    ew = "East" if dx > 0 else "West" if dx < 0 else ""
    return f"{ns}-{ew}" if ns and ew else (ns or ew)


def ds_face(r):
    start = r.randrange(4)
    turns = [r.choice(["left", "right", "left", "right", "back"]) for _ in range(r.randrange(3, 6))]
    _x, _y, h = _walk(start, [("none", 1)] + [(t, 1) for t in turns])
    words = {"left": "turns left", "right": "turns right", "back": "turns back (about turn)"}
    seq = ", then ".join(words[t] for t in turns)
    return _make(r, "direction_sense", "ds_face", f"A man is facing {COMPASS[start]}. He {seq}. Which direction is he facing now?", COMPASS[h],
                 [c for c in COMPASS if c != COMPASS[h]], f"Starting {COMPASS[start]}: " + " -> ".join([COMPASS[start]] + [COMPASS[_walk(start, [('none', 1)] + [(t, 1) for t in turns[:i + 1]])[2]] for i in range(len(turns))]) + ".",
                 {"start": start, "turns": turns})


def _random_path(r: random.Random, start: int, n: int, whole_distance: bool) -> tuple[list[tuple[str, int]], int, int]:
    for _ in range(500):
        legs = [("none", r.randrange(2, 16))] + [(r.choice(["left", "right"]), r.randrange(2, 16)) for _ in range(n - 1)]
        dx, dy, _h = _walk(start, legs)
        d2 = dx * dx + dy * dy
        if d2 == 0:
            continue
        if whole_distance and (math.isqrt(d2) ** 2 != d2 or dx == 0 or dy == 0):
            continue
        return legs, dx, dy
    raise Retry("no path")


def ds_path(r):
    start = r.randrange(4)
    n = r.choice([3, 3, 4])
    ask_dist = r.random() < 0.5
    legs, dx, dy = _random_path(r, start, n, ask_dist)
    dist2 = dx * dx + dy * dy
    parts = [f"walks {legs[0][1]} km towards {COMPASS[start]}"]
    for turn, dist in legs[1:]:
        parts.append(f"turns {turn} and walks {dist} km")
    story = "A man starts from a point and " + ", then ".join(parts) + ". "
    h = start
    trail = ["(0, 0)"]
    x = y = 0
    for turn, dist in legs:
        h = (h + {"none": 0, "left": -1, "right": 1}[turn]) % 4
        x += VEC[h][0] * dist
        y += VEC[h][1] * dist
        trail.append(f"({x}, {y})")
    work = f"Taking East as +x and North as +y, his positions are {' -> '.join(trail)}."
    meta = {"start": start, "legs": legs}
    if ask_dist:
        d = math.isqrt(dist2)
        return _num_q(r, "direction_sense", "ds_path_dist", story + "How far is he from the starting point?", d,
                      [abs(dx) + abs(dy), abs(dx), abs(dy), d + 1, d - 1], f"{work} Distance = sqrt({dx}^2 + {dy}^2) = sqrt({dist2}) = {d} km.", dict(meta, ask="dist"),
                      fmt=lambda v: num(v) + " km")
    word = _direction_word(dx, dy)
    i = EIGHT.index(word)
    wrong = [EIGHT[(i + 4) % 8], EIGHT[(i + 1) % 8], EIGHT[(i - 1) % 8], EIGHT[(i + 2) % 8]]
    where = []
    if dx:
        where.append(f"{abs(dx)} km {'East' if dx > 0 else 'West'}")
    if dy:
        where.append(f"{abs(dy)} km {'North' if dy > 0 else 'South'}")
    return _make(r, "direction_sense", "ds_path_dir", story + "In which direction is he from the starting point?", word, wrong,
                 f"{work} He ends {' and '.join(where)} of the start, so he is towards the {word}.", dict(meta, ask="dir"))


# --------------------------------------------------------------------------- ranking and order
COMPARE = [("taller", "shorter"), ("heavier", "lighter"), ("older", "younger"), ("richer", "poorer"), ("faster", "slower"), ("stronger", "weaker")]


def ro_row(r):
    kind = r.choice(["total", "between", "bottom", "shift"])
    if kind == "total":
        p, q = r.randrange(5, 25), r.randrange(5, 25)
        who = r.choice(NAMES_M + NAMES_F)
        return _num_q(r, "ranking_order", "ro_total", f"In a row of students, {who} is {p}th from the left end and {q}th from the right end. How many students are in the row?", p + q - 1,
                      [p + q, p + q - 2, max(p, q), p + q + 1], f"Total = position from left + position from right - 1 = {p} + {q} - 1 = {p + q - 1}.", {"p": p, "q": q})
    if kind == "between":
        p, q = r.sample(range(3, 40), 2)
        if abs(p - q) < 2:
            raise Retry("adjacent")
        return _num_q(r, "ranking_order", "ro_between", f"In a row, Anil is {p}th and Kiran is {q}th from the left end. How many persons are between them?", abs(p - q) - 1,
                      [abs(p - q), abs(p - q) + 1, p + q - 1, abs(p - q) - 2], f"Persons between = |{p} - {q}| - 1 = {abs(p - q) - 1}.", {"p": p, "q": q})
    if kind == "bottom":
        n, p = r.randrange(30, 80), r.randrange(3, 28)
        return _num_q(r, "ranking_order", "ro_bottom", f"In a class of {n} students, Meena ranks {p}th from the top. What is her rank from the bottom?", n - p + 1,
                      [n - p, n - p + 2, n + p, n - p - 1], f"Rank from bottom = {n} - {p} + 1 = {n - p + 1}.", {"n": n, "p": p})
    p, x, q = r.randrange(5, 20), r.randrange(2, 8), r.randrange(5, 20)
    total = p + x + q - 1
    return _num_q(r, "ranking_order", "ro_shift", f"Suresh is {p}th from the left in a row. After he moves {x} places to the right, he is {q}th from the right end. How many persons are in the row?", total,
                  [p + q + x, p + q - 1, total + 1, p + q + x - 2], f"His new position from the left is {p} + {x} = {p + x}. Total = {p + x} + {q} - 1 = {total}.",
                  {"p": p, "x": x, "q": q})


SUPERLATIVE = {"taller": "tallest", "shorter": "shortest", "heavier": "heaviest", "lighter": "lightest", "older": "oldest",
               "younger": "youngest", "richer": "richest", "poorer": "poorest", "faster": "fastest", "slower": "slowest",
               "stronger": "strongest", "weaker": "weakest"}


def ro_order(r):
    more, less = r.choice(COMPARE)
    names = r.sample(NAMES_M + NAMES_F, 3)  # names[0] is at the top of the order (chains: 0 > 1 > 2)
    shape = r.choice(["chain", "fork_down", "fork_up"])
    pairs = {"chain": [(0, 1), (1, 2)], "fork_down": [(0, 1), (0, 2)], "fork_up": [(0, 2), (1, 2)]}[shape]
    sentences = []
    for hi, lo in pairs:
        sentences.append(f"{names[hi]} is {more} than {names[lo]}." if r.random() < 0.5 else f"{names[lo]} is {less} than {names[hi]}.")
    r.shuffle(sentences)
    orders = _consistent_orders(pairs)
    tops, bottoms = {o[0] for o in orders}, {o[2] for o in orders}
    asks = []
    if len(tops) == 1:
        asks.append((f"Who is the {SUPERLATIVE[more]}?", names[next(iter(tops))]))
    if len(bottoms) == 1:
        asks.append((f"Who is the {SUPERLATIVE[less]}?", names[next(iter(bottoms))]))
    question, right = r.choice(asks)
    if len(orders) == 1:
        why = f"The order from the top is {' > '.join(names[i] for i in orders[0])}."
    else:
        why = "Every order that fits the statements puts the same person there."
    return _make(r, "ranking_order", "ro_order", " ".join(sentences) + " " + question, right, [n for n in names if n != right] + ["Cannot be determined"],
                 why, {"names": names, "pairs": pairs, "more": more, "less": less, "question": question})


def _consistent_orders(pairs: list[tuple[int, int]]) -> list[tuple[int, ...]]:
    """All orderings (highest first) of the three people 0, 1, 2 that respect pairs (hi, lo)."""
    out = []
    for a in range(3):
        for b in range(3):
            for c in range(3):
                if len({a, b, c}) == 3:
                    pos = {a: 0, b: 1, c: 2}
                    if all(pos[hi] < pos[lo] for hi, lo in pairs):
                        out.append((a, b, c))
    return out


# --------------------------------------------------------------------------- odd one out
POOLS: dict[str, list[str]] = {
    "planets": ["Mars", "Venus", "Jupiter", "Saturn", "Neptune", "Uranus"],
    "metals": ["Iron", "Copper", "Zinc", "Silver", "Gold", "Nickel", "Lead", "Tin"],
    "gases": ["Oxygen", "Nitrogen", "Hydrogen", "Helium", "Neon", "Argon"],
    "rivers": ["Ganga", "Yamuna", "Godavari", "Krishna", "Kaveri", "Narmada", "Mahanadi", "Brahmaputra"],
    "fruits": ["Mango", "Banana", "Guava", "Papaya", "Grapes", "Apple", "Pineapple", "Pomegranate"],
    "spices": ["Cardamom", "Cloves", "Cinnamon", "Turmeric", "Pepper", "Cumin"],
    "birds": ["Sparrow", "Crow", "Eagle", "Peacock", "Parrot", "Pigeon", "Owl"],
    "mammals": ["Dog", "Cow", "Horse", "Elephant", "Tiger", "Lion", "Goat"],
    "vehicles": ["Bus", "Car", "Truck", "Train", "Scooter", "Tractor"],
    "currencies": ["Rupee", "Dollar", "Euro", "Yen", "Pound", "Rouble"],
    "states": ["Kerala", "Punjab", "Assam", "Odisha", "Gujarat", "Bihar", "Rajasthan"],
    "hill ranges": ["Himalaya", "Aravalli", "Vindhya", "Satpura", "Nilgiri", "Sahyadri"],
    "languages": ["Telugu", "Tamil", "Kannada", "Malayalam", "Hindi", "Bengali"],
    "organs": ["Heart", "Liver", "Kidney", "Lung", "Brain", "Stomach"],
    "shapes": ["Circle", "Triangle", "Rectangle", "Pentagon", "Hexagon", "Octagon"],
}
_ARTICLE = {"planets": "planets", "metals": "metals", "gases": "gases", "rivers": "rivers", "fruits": "fruits", "spices": "spices", "birds": "birds",
            "mammals": "mammals", "vehicles": "vehicles", "currencies": "currencies", "states": "states of India", "hill ranges": "hill ranges",
            "languages": "languages", "organs": "organs of the human body", "shapes": "shapes"}
_SINGLE = {"planets": "a planet", "metals": "a metal", "gases": "a gas", "rivers": "a river", "fruits": "a fruit", "spices": "a spice", "birds": "a bird",
           "mammals": "a mammal", "vehicles": "a vehicle", "currencies": "a currency", "states": "a state of India", "hill ranges": "a hill range",
           "languages": "a language", "organs": "an organ of the human body", "shapes": "a shape"}


def _is_prime(n: int) -> bool:
    return n > 1 and all(n % i for i in range(2, math.isqrt(n) + 1))


def oo_words(r):
    a, b = r.sample(sorted(POOLS), 2)
    majority = r.sample(POOLS[a], 3)
    odd = r.choice(POOLS[b])
    items = majority + [odd]
    r.shuffle(items)
    return _make(r, "odd_one_out", "oo_words", f"Find the odd one out: {', '.join(items)}.", odd, majority,
                 f"{', '.join(majority[:-1])} and {majority[-1]} are all {_ARTICLE[a]}, while {odd} is {_SINGLE[b]}.",
                 {"a": a, "b": b, "majority": majority, "odd": odd})


_NUM_FEATURES: dict[str, Callable[[int], bool]] = {
    "prime": _is_prime, "square": lambda n: math.isqrt(n) ** 2 == n, "cube": lambda n: round(n ** (1 / 3)) ** 3 == n, "even": lambda n: n % 2 == 0,
    "mult3": lambda n: n % 3 == 0, "mult4": lambda n: n % 4 == 0, "mult5": lambda n: n % 5 == 0, "mult6": lambda n: n % 6 == 0,
    "mult7": lambda n: n % 7 == 0, "mult9": lambda n: n % 9 == 0, "mult11": lambda n: n % 11 == 0,
}
_NUM_WHY = {"prime": "prime numbers", "square": "perfect squares", "cube": "perfect cubes", "even": "even numbers", "mult3": "multiples of 3",
            "mult4": "multiples of 4", "mult5": "multiples of 5", "mult6": "multiples of 6", "mult7": "multiples of 7", "mult9": "multiples of 9",
            "mult11": "multiples of 11"}


def oo_numbers(r):
    rule = r.choice(["prime", "square", "cube", "even", "mult3", "mult4", "mult5", "mult6", "mult7", "mult9", "mult11"])
    pred = _NUM_FEATURES[rule]
    why = _NUM_WHY[rule]
    top = 300 if rule == "cube" else 200
    yes = [n for n in range(10, top) if pred(n)]
    no = [n for n in range(10, top) if not pred(n)]
    flip = r.random() < 0.5  # True: three that fit and one that does not; False: three that do not fit and one that does
    for _ in range(600):
        trio = r.sample(yes if flip else no, 3)
        odd = r.choice(no if flip else yes)
        items = trio + [odd]
        if len(set(items)) != 4:
            continue
        # no other simple property may split the four numbers three against one, or the answer would be arguable
        if all(sum(1 for n in items if fn(n)) not in (1, 3) for name, fn in _NUM_FEATURES.items() if name != rule):
            break
    else:
        raise Retry("second pattern")
    shown = items[:]
    r.shuffle(shown)
    if flip:
        working = f"{', '.join(map(str, trio))} are all {why}; {odd} is not."
    else:
        working = f"{', '.join(map(str, trio))} are all not {why}; {odd} is."
    return _make(r, "odd_one_out", "oo_numbers", f"Find the odd one out: {', '.join(map(str, shown))}.", str(odd), [str(t) for t in trio],
                 working, {"rule": rule, "items": items, "odd": odd})


def oo_letters(r):
    g = r.choice([1, 2, 3, 4])
    h = g + r.choice([1, 2, -1]) if g > 1 else g + r.choice([1, 2])
    if h == g or h < 1:
        raise Retry("gap")
    pairs = []
    for gap in [g, g, g, h]:
        x = r.randrange(1, 27 - gap)
        pairs.append((x, x + gap))
    items = [chr(64 + x) + chr(64 + y) for x, y in pairs]
    if len(set(items)) != 4:
        raise Retry("same pair")
    odd = items[3]
    shuffled = items[:]
    r.shuffle(shuffled)
    return _make(r, "odd_one_out", "oo_letters", f"Find the odd one out: {', '.join(shuffled)}.", odd, items[:3],
                 f"In {', '.join(items[:3])} the second letter is {g} place{'s' if g > 1 else ''} after the first; in {odd} it is {h} places after.",
                 {"pairs": pairs, "odd": odd, "g": g, "h": h})


# --------------------------------------------------------------------------- analogy
RELATIONS: dict[str, tuple[str, list[tuple[str, str]]]] = {
    "capital": ("{} : {}", [("India", "New Delhi"), ("Japan", "Tokyo"), ("France", "Paris"), ("Italy", "Rome"), ("Egypt", "Cairo"), ("Nepal", "Kathmandu"),
                            ("Canada", "Ottawa"), ("Australia", "Canberra"), ("Germany", "Berlin"), ("China", "Beijing")]),
    "young": ("{} : {}", [("Cow", "Calf"), ("Dog", "Puppy"), ("Horse", "Foal"), ("Cat", "Kitten"), ("Sheep", "Lamb"), ("Goat", "Kid"), ("Hen", "Chick")]),
    "sound": ("{} : {}", [("Lion", "Roar"), ("Dog", "Bark"), ("Cat", "Meow"), ("Cow", "Moo"), ("Horse", "Neigh"), ("Elephant", "Trumpet"), ("Snake", "Hiss")]),
    "instrument": ("{} : {}", [("Thermometer", "Temperature"), ("Barometer", "Atmospheric pressure"), ("Ammeter", "Electric current"), ("Seismograph", "Earthquakes"),
                               ("Hygrometer", "Humidity"), ("Speedometer", "Speed")]),
    "state_capital": ("{} : {}", [("Tamil Nadu", "Chennai"), ("Karnataka", "Bengaluru"), ("Kerala", "Thiruvananthapuram"), ("Odisha", "Bhubaneswar"),
                                  ("Maharashtra", "Mumbai"), ("Gujarat", "Gandhinagar"), ("Rajasthan", "Jaipur"), ("Bihar", "Patna"), ("West Bengal", "Kolkata"),
                                  ("Uttar Pradesh", "Lucknow")]),
    "author": ("{} : {}", [("Rabindranath Tagore", "Gitanjali"), ("Kalidasa", "Meghaduta"), ("Valmiki", "Ramayana"), ("Vyasa", "Mahabharata"),
                           ("Tulsidas", "Ramcharitmanas"), ("Bankim Chandra Chatterjee", "Anandamath"), ("Kautilya", "Arthashastra")]),
    "unit": ("{} : {}", [("Ampere", "Electric current"), ("Volt", "Potential difference"), ("Newton", "Force"), ("Joule", "Energy"), ("Pascal", "Pressure"),
                         ("Watt", "Power"), ("Hertz", "Frequency"), ("Ohm", "Resistance")]),
}
_REL_WORDS = {"capital": "the capital of the country", "young": "the young one of the animal", "sound": "the sound made by the animal",
              "instrument": "what the instrument measures", "state_capital": "the capital of the state", "author": "the work written by the author",
              "unit": "the quantity measured in the unit"}
_NUMFUNCS = {
    "the square of the number": lambda n: n * n, "the cube of the number": lambda n: n ** 3, "the square of the number plus 1": lambda n: n * n + 1,
    "the square of the number minus 1": lambda n: n * n - 1, "n(n + 1)": lambda n: n * (n + 1), "n(n - 1)": lambda n: n * (n - 1),
    "double the number": lambda n: 2 * n, "triple the number": lambda n: 3 * n, "double the number plus 1": lambda n: 2 * n + 1,
    "n^2 + n + 1": lambda n: n * n + n + 1,
}


def an_words(r):
    rel = r.choice(sorted(RELATIONS))
    pairs = RELATIONS[rel][1]
    (k1, v1), (k2, v2) = r.sample(pairs, 2)
    reverse = r.random() < 0.3
    others = [p for p in pairs if p[0] not in (k1, k2)]
    if reverse:
        wrong = [p[0] for p in r.sample(others, 3)]
        return _make(r, "analogy", "an_words", f"{v1} : {k1} :: {v2} : ?", k2, wrong,
                     f"{v1} is {_REL_WORDS[rel]} '{k1}', so the answer is the item whose {_REL_WORDS[rel].split(' of ')[0]} is {v2}: {k2}.",
                     {"rel": rel, "k1": k1, "v1": v1, "k2": k2, "v2": v2, "reverse": True})
    wrong = [p[1] for p in r.sample(others, 3)]
    return _make(r, "analogy", "an_words", f"{k1} : {v1} :: {k2} : ?", v2, wrong,
                 f"{v1} is {_REL_WORDS[rel]} {k1}; in the same way the answer for {k2} is {v2}.", {"rel": rel, "k1": k1, "v1": v1, "k2": k2, "v2": v2, "reverse": False})


def an_numbers(r):
    name = r.choice(sorted(_NUMFUNCS))
    f = _NUMFUNCS[name]
    a, b, c = r.sample(range(2, 13), 3)
    answer = f(c)
    # the rule must be the only one in the family that fits both examples
    fits = [nm for nm, g in _NUMFUNCS.items() if g(a) == f(a) and g(b) == f(b)]
    if any(_NUMFUNCS[nm](c) != answer for nm in fits):
        raise Retry("ambiguous")
    return _num_q(r, "analogy", "an_numbers", f"{a} : {f(a)} :: {b} : {f(b)} :: {c} : ?", answer, [c * c, c ** 3, answer + c, answer - c, 2 * c],
                  f"The rule is {name}: {a} -> {f(a)}, {b} -> {f(b)}. So {c} -> {answer}.",
                  {"rule": name, "a": a, "b": b, "c": c})


# --------------------------------------------------------------------------- syllogism and statements
NOUNS = ["cats", "dogs", "birds", "tables", "chairs", "pens", "books", "roads", "flowers", "trees", "buses", "cars", "doctors", "teachers", "engineers",
         "lawyers", "farmers", "poets", "boats", "clocks"]
FORMS = "AEIO"


def _regions(term_count: int = 3) -> range:
    return range(1 << term_count)


def _holds(model: int, stmt: tuple[str, int, int]) -> bool:
    """`model` is a bitmask of the non-empty regions; a region is a bitmask of the terms it lies in.
    stmt = (form, i, j): A all i are j, E no i is j, I some i are j, O some i are not j."""
    form, i, j = stmt
    inside = [reg for reg in range(8) if model >> reg & 1]
    if form == "A":
        return not any(reg >> i & 1 and not reg >> j & 1 for reg in inside)
    if form == "E":
        return not any(reg >> i & 1 and reg >> j & 1 for reg in inside)
    if form == "I":
        return any(reg >> i & 1 and reg >> j & 1 for reg in inside)
    return any(reg >> i & 1 and not reg >> j & 1 for reg in inside)


def _models(premises: tuple[tuple[str, int, int], ...]) -> list[int]:
    """Every arrangement of three groups (as a set of non-empty regions) where the premises hold and each group has a member."""
    out = []
    for m in range(256):
        if all(any(m >> reg & 1 and reg >> t & 1 for reg in range(8)) for t in range(3)) and all(_holds(m, p) for p in premises):
            out.append(m)
    return out


def follows(premises: tuple[tuple[str, int, int], ...], conclusion: tuple[str, int, int]) -> bool:
    """True when the conclusion holds in every arrangement that satisfies the premises (groups are never empty)."""
    models = _models(tuple(premises))
    return bool(models) and all(_holds(m, conclusion) for m in models)


def _stmt_text(stmt: tuple[str, int, int], names: list[str]) -> str:
    form, i, j = stmt
    a, b = names[i], names[j]
    return {"A": f"All {a} are {b}", "E": f"No {a} are {b}", "I": f"Some {a} are {b}", "O": f"Some {a} are not {b}"}[form]


ANSWERS4 = ["Only I follows", "Only II follows", "Both I and II follow", "Neither I nor II follows"]


def _verdict(f1: bool, f2: bool) -> str:
    return ANSWERS4[2] if f1 and f2 else ANSWERS4[0] if f1 else ANSWERS4[1] if f2 else ANSWERS4[3]


def _mirror_pair(a: tuple[str, int, int], b: tuple[str, int, int]) -> bool:
    """'Some X are Y' and 'Some Y are X' (also 'No X are Y' and 'No Y are X') say the same thing."""
    return a[0] == b[0] and a[0] in "EI" and (a[1], a[2]) == (b[2], b[1])


@functools.lru_cache(maxsize=1)
def _sy_table() -> dict[str, list[tuple]]:
    """Every (premises, conclusion I, conclusion II) of the two-statement family, sorted by the correct answer. Each answer is found by
    checking all arrangements of the three groups (see `follows`), so it is proven, not guessed."""
    table: dict[str, list[tuple]] = {v: [] for v in ANSWERS4}
    cands = [(f, i, j) for f in FORMS for (i, j) in ((0, 2), (2, 0))]
    for f1 in FORMS:
        for o1 in ((0, 1), (1, 0)):
            for f2 in FORMS:
                for o2 in ((1, 2), (2, 1)):
                    premises = ((f1,) + o1, (f2,) + o2)
                    models = _models(premises)
                    if not models:
                        continue
                    for c1, c2 in itertools.combinations(cands, 2):
                        if _mirror_pair(c1, c2):
                            continue
                        t1, t2 = [_holds(m, c1) for m in models], [_holds(m, c2) for m in models]
                        f1_, f2_ = all(t1), all(t2)
                        if not f1_ and not f2_ and all(a or b for a, b in zip(t1, t2, strict=True)):
                            continue  # "either I or II" would be the right answer, which is not offered
                        table[_verdict(f1_, f2_)].append((premises, c1, c2))
    return table


def sy_two(r):
    names = r.sample(NOUNS, 3)
    table = _sy_table()
    right = r.choice(ANSWERS4)
    premises, c1, c2 = r.choice(table[right])
    if r.random() < 0.5:
        c1, c2 = c2, c1
    p1, p2 = premises
    f1, f2 = follows(premises, c1), follows(premises, c2)
    text = (f"Statements: 1. {_stmt_text(p1, names)}. 2. {_stmt_text(p2, names)}. Conclusions: I. {_stmt_text(c1, names)}. II. {_stmt_text(c2, names)}. "
            "Take the statements as true and assume every group has at least one member. Which conclusion follows?")
    right = _verdict(f1, f2)
    return _make(r, "syllogism", "sy_two", text, right, [a for a in ANSWERS4 if a != right],
                 f"Draw every possible arrangement of the three groups that fits both statements. Conclusion I {'holds in all of them' if f1 else 'fails in at least one'}; "
                 f"conclusion II {'holds in all of them' if f2 else 'fails in at least one'}.",
                 {"premises": [list(p) for p in premises], "conclusions": [list(c1), list(c2)], "names": names})


def sc_order(r):
    more, less = r.choice(COMPARE)
    names = r.sample(NAMES_M + NAMES_F, 3)
    shape = r.choice(["chain", "fork_down", "fork_up"])
    pairs = {"chain": [(0, 1), (1, 2)], "fork_down": [(0, 1), (0, 2)], "fork_up": [(0, 2), (1, 2)]}[shape]
    sentences = []
    for hi, lo in pairs:
        sentences.append(f"{names[hi]} is {more} than {names[lo]}." if r.random() < 0.5 else f"{names[lo]} is {less} than {names[hi]}.")
    r.shuffle(sentences)
    orders = _consistent_orders(pairs)
    cands = [(x, y) for x in range(3) for y in range(3) if x != y]
    want = r.choice(ANSWERS4)
    for _ in range(200):
        c1, c2 = r.sample(cands, 2)

        def holds(c):
            return all(o.index(c[0]) < o.index(c[1]) for o in orders)

        f1, f2 = holds(c1), holds(c2)
        if {c1, c2} in ({(0, 1), (1, 0)}, {(0, 2), (2, 0)}, {(1, 2), (2, 1)}) and not (f1 or f2):
            continue  # opposite claims about the same two people: "either" would be right
        if _verdict(f1, f2) != want:
            continue
        return _make(r, "statements_conclusions", "sc_order",
                     f"Statements: {' '.join(sentences)} Conclusions: I. {names[c1[0]]} is {more} than {names[c1[1]]}. II. {names[c2[0]]} is {more} than {names[c2[1]]}. Which conclusion follows?",
                     _verdict(f1, f2), [a for a in ANSWERS4 if a != _verdict(f1, f2)],
                     "The statements fix these facts: " + "; ".join(f"{names[hi]} is {more} than {names[lo]}" for hi, lo in _closure(pairs)) +
                     f". Conclusion I {'follows' if f1 else 'does not follow'}; conclusion II {'follows' if f2 else 'does not follow'}.",
                     {"names": names, "pairs": pairs, "c": [list(c1), list(c2)]})
    raise Retry("no conclusions")


def _closure(pairs: list[tuple[int, int]]) -> list[tuple[int, int]]:
    known = set(pairs)
    changed = True
    while changed:
        changed = False
        for a, b in list(known):
            for c, d in list(known):
                if b == c and (a, d) not in known:
                    known.add((a, d))
                    changed = True
    return sorted(known)


def sc_individual(r):
    x, y = r.sample(NOUNS, 2)
    person = r.choice(NAMES_M + NAMES_F)
    names = [person, x, y]  # terms: 0 = the person, 1 = group X, 2 = group Y
    major = (r.choice(["A", "E"]), 1, 2)
    minor = ("A", 0, 1)
    premises = (major, minor)
    if not _models(premises):
        raise Retry("inconsistent")
    text1 = _stmt_text(major, names)
    text2 = f"{person} is one of the {x}"
    cands = [("A", 0, 2), ("E", 0, 2), ("A", 2, 1), ("I", 2, 1), ("E", 2, 1), ("O", 2, 1)]
    ctext = {("A", 0, 2): f"{person} is one of the {y}", ("E", 0, 2): f"{person} is not one of the {y}", ("A", 2, 1): f"All {y} are {x}",
             ("I", 2, 1): f"Some {y} are {x}", ("E", 2, 1): f"No {y} are {x}", ("O", 2, 1): f"Some {y} are not {x}"}
    models = _models(premises)
    want = r.choice(ANSWERS4)
    for _ in range(200):
        c1, c2 = r.sample(cands, 2)
        if _mirror_pair(c1, c2):
            continue
        t1, t2 = [_holds(m, c1) for m in models], [_holds(m, c2) for m in models]
        f1, f2 = all(t1), all(t2)
        if not f1 and not f2 and all(a or b for a, b in zip(t1, t2, strict=True)):
            continue
        if _verdict(f1, f2) != want:
            continue
        return _make(r, "statements_conclusions", "sc_individual",
                     f"Statements: 1. {text1}. 2. {text2}. Conclusions: I. {ctext[c1]}. II. {ctext[c2]}. Take the statements as true. Which conclusion follows?",
                     _verdict(f1, f2), [a for a in ANSWERS4 if a != _verdict(f1, f2)],
                     f"Check each conclusion against every arrangement that fits the statements: I {'follows' if f1 else 'does not follow'}, "
                     f"II {'follows' if f2 else 'does not follow'}.",
                     {"premises": [list(major), list(minor)], "conclusions": [list(c1), list(c2)], "names": names})
    raise Retry("no conclusions")


_AVG_CONCLUSIONS = [
    ("The sum of the numbers is {s}", True), ("At least one number is not less than {m}", True), ("At least one number is not more than {m}", True),
    ("Every number is equal to {m}", False), ("All the numbers are greater than {m}", False), ("The sum of the numbers is {s2}", False),
    ("Every number is less than {m2}", False), ("The largest number is more than {m}", False),
]


def sc_average(r):
    n, m = r.randrange(4, 12), r.randrange(10, 60)
    fill = {"n": n, "m": m, "s": n * m, "s2": n * m + n, "m2": m + 1}
    want = r.choice(ANSWERS4)
    for _ in range(200):
        c1, c2 = r.sample(_AVG_CONCLUSIONS, 2)
        f1, f2 = c1[1], c2[1]
        if _verdict(f1, f2) != want:
            continue
        return _make(r, "statements_conclusions", "sc_average",
                     f"Statement: The average of {n} numbers is {m}. Conclusions: I. {c1[0].format(**fill)}. II. {c2[0].format(**fill)}. Which conclusion follows?",
                     _verdict(f1, f2), [a for a in ANSWERS4 if a != _verdict(f1, f2)],
                     f"The sum of {n} numbers with average {m} is {n * m}. The numbers could all be equal to {m} (then none is above or below it) or spread around it, "
                     f"so only what holds in every case follows. I {'follows' if f1 else 'does not follow'}; II {'follows' if f2 else 'does not follow'}.",
                     {"n": n, "m": m, "c": [c1[0], c2[0]], "flags": [f1, f2]})
    raise Retry("no conclusions")


# --------------------------------------------------------------------------- registry and public functions
TEMPLATES: dict[str, list[Callable[[random.Random], Q]]] = {
    "percentage": [pct_of, pct_what, pct_successive, pct_original, pct_pass],
    "profit_loss": [pl_sp, pl_cp, pl_percent, pl_discount, pl_two_articles],
    "simple_interest": [si_interest, si_rate, si_time, si_multiple],
    "compound_interest": [ci_interest, ci_amount, ci_difference, ci_growth],
    "ratio_proportion": [rp_share, rp_chain, rp_add, rp_fourth],
    "average": [av_list, av_replace, av_teacher, av_natural, av_middle],
    "time_work": [tw_together, tw_other, tw_leave, tw_pipes, tw_efficiency, tw_men],
    "work_wages": [ww_two, ww_three, ww_manhours, ww_total],
    "time_distance": [td_avg, td_pole, td_platform, td_two_trains, td_boat, td_overtake],
    "clocks_calendars": [cc_angle, cc_coincide, cc_weekday_ref, cc_weekday_famous, cc_leap, cc_counts],
    "partnership": [pt_two, pt_months, pt_join, pt_find, pt_three],
    "mensuration": [me_rect, me_circle, me_cylinder, me_cuboid, me_melt, me_triangle, me_rhombus, me_sphere],
    "number_system": [ns_hcf_lcm, ns_other, ns_least_rem, ns_greatest, ns_divisible, ns_remainder, ns_unit_digit, ns_sum, ns_count],
    "series": [se_arith, se_geo, se_square, se_second, se_fib, se_muladd, se_letter_step, se_letter_alt, se_letter_pair],
    "coding_decoding": [cd_shift, cd_mirror, cd_value, cd_word],
    "blood_relations": [bl_phrase, bl_chain],
    "direction_sense": [ds_face, ds_path],
    "ranking_order": [ro_row, ro_order],
    "odd_one_out": [oo_words, oo_numbers, oo_letters],
    "analogy": [an_words, an_numbers],
    "syllogism": [sy_two],
    "statements_conclusions": [sc_order, sc_individual, sc_average],
}


def draw(area: str, rng: random.Random, skip: set[str] | None = None) -> Q:
    """One question of the area, redrawing until a template gives a usable one that is not in `skip` (question texts)."""
    if area not in TEMPLATES:
        raise ValueError(f"unknown aptitude area: {area}")
    templates = TEMPLATES[area]
    for _ in range(500):
        try:
            q = rng.choice(templates)(rng)
        except Retry:
            continue
        if skip and q.question in skip:
            continue
        return q
    raise RuntimeError(f"could not draw a question for {area}")


def generate_questions(area: str, count: int, seed: int) -> list[dict]:
    """`count` different questions of one area. The same (area, count, seed) always gives the same questions."""
    rng = random.Random(f"{area}:{seed}")
    seen: set[str] = set()
    out = []
    for _ in range(max(0, int(count))):
        q = draw(area, rng, seen)
        seen.add(q.question)
        out.append(q.public())
    return out


def area_for_date(day: date) -> str:
    """The focus area of a day: rotates through all AREAS, one per day."""
    return AREAS[day.toordinal() % len(AREAS)]


def areas_for_date(day: date) -> list[str]:
    i = day.toordinal() % len(AREAS)
    return [AREAS[(i + off) % len(AREAS)] for off in MIXED_OFFSETS]


def area_seed(day: date, area: str) -> int:
    return day.toordinal() * 100 + AREAS.index(area)


def mixed_questions(day: date, count: int = 20) -> list[dict]:
    """A drill mixing four areas: the focus area of the day gets the most questions (40 percent), the others share the rest.
    Deterministic for a given day."""
    areas = areas_for_date(day)
    count = max(0, int(count))
    focus = math.ceil(count * 0.4)
    rest = count - focus
    shares = [focus] + [rest // 3 + (1 if k < rest % 3 else 0) for k in range(3)]
    seen: set[str] = set()
    out: list[dict] = []
    for area, share in zip(areas, shares, strict=True):
        rng = random.Random(f"{area}:{area_seed(day, area)}")
        for _ in range(share):
            q = draw(area, rng, seen)
            seen.add(q.question)
            out.append(q.public())
    random.Random(day.toordinal()).shuffle(out)
    return out


def label_key(text: str) -> str:
    """Comparison key for a syllabus title and an area label ('Ratio & proportion' == 'Ratio and proportion')."""
    return " ".join(w for w in norm_text(text).split() if w != "and")
