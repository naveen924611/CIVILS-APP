"""The deterministic aptitude and reasoning generator (features/tests/aptitude.py).

Every question is checked by recomputing its answer with a second, independent method (a different formula, brute force,
the calendar module, Zeller's congruence, a model checker over elements, ...) from the numbers the template kept in `Q.meta`.
"""
import calendar
import itertools
import math
import random
import string
from collections import Counter
from datetime import date
from fractions import Fraction

import pytest

from app.features.tests import aptitude as A
from app.features.tests.common import norm_text
from app.features.tests.generate import validate_mcq
from app.features.tests.schemas import GenMcq

SEEDS = 200


def n(x):
    return A.num(Fraction(x))


def rs(x):
    return "Rs " + n(x)


def pc(x):
    return n(x) + "%"


def days(x):
    return n(x) + " days"


def hours(x):
    return n(x) + " hours"


def secs(x):
    return n(x) + " seconds"


def isqrt_exact(v: Fraction) -> Fraction:
    v = Fraction(v)
    a, b = math.isqrt(v.numerator), math.isqrt(v.denominator)
    assert a * a == v.numerator and b * b == v.denominator, f"{v} is not a perfect square"
    return Fraction(a, b)


def zeller_weekday(d: date) -> str:
    """Zeller's congruence (Gregorian): 0 = Saturday, 1 = Sunday, ..."""
    q, m, y = d.day, d.month, d.year
    if m < 3:
        m += 12
        y -= 1
    k, j = y % 100, y // 100
    h = (q + (13 * (m + 1)) // 5 + k + k // 4 + j // 4 + 5 * j) % 7
    return ["Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"][h]


UP = string.ascii_uppercase


# --------------------------------------------------------------------------- independent checkers: kind -> expected option text


def chk_pct_of(m):
    return n(Fraction(m["p"], 100) * m["n"])


def chk_pct_what(m):
    return pc(Fraction(m["x"], m["y"]) * 100)


def chk_pct_successive(m):
    v = (Fraction(100 + m["a"], 100) * Fraction(100 - m["b"], 100) - 1) * 100
    return f"{n(abs(v))}% {'increase' if v > 0 else 'decrease'}"


def chk_pct_original(m):
    return n(Fraction(m["n"]) / (1 + Fraction(m["x"], 100)))


def chk_pct_pass(m):
    return n((m["x"] + m["y"]) / Fraction(m["p"], 100))


def chk_pl_sp(m):
    return rs(m["cp"] * (1 - Fraction(m["p"], 100) if m["loss"] else 1 + Fraction(m["p"], 100)))


def chk_pl_cp(m):
    return rs(Fraction(m["sp"]) / (1 - Fraction(m["p"], 100) if m["loss"] else 1 + Fraction(m["p"], 100)))


def chk_pl_percent(m):
    return pc(Fraction(abs(m["sp"] - m["cp"]), m["cp"]) * 100)


def chk_pl_discount(m):
    sp = m["cp"] * (1 + Fraction(m["x"], 100)) * (1 - Fraction(m["y"], 100))
    v = (sp - m["cp"]) / m["cp"] * 100
    return "No gain no loss" if v == 0 else f"{n(abs(v))}% {'gain' if v > 0 else 'loss'}"


def chk_pl_two(m):
    s, a = m["s"], Fraction(m["a"], 100)
    cp1, cp2 = s / (1 + a), s / (1 - a)
    result = 2 * s - (cp1 + cp2)
    v = -result / (cp1 + cp2) * 100
    return "No gain no loss" if v == 0 else (n(v) + "% loss" if v > 0 else n(-v) + "% gain")


def chk_si(m):
    si = Fraction(m["p"]) * Fraction(m["r"], 100) * m["t"]
    return rs(si + m["p"] if m["amount"] else si)


def chk_si_rate(m):
    return pc(Fraction(m["si"], m["p"] * m["t"]) * 100)


def chk_si_time(m):
    return n(Fraction(m["si"], m["p"]) / Fraction(m["r"], 100)) + " years"


def chk_si_multiple(m):
    per_year = Fraction(100, m["t"])  # per Rs 100 the simple interest each year
    years = 0
    amount = Fraction(100)
    while amount < 100 * m["n"]:
        amount += per_year
        years += 1
    assert amount == 100 * m["n"]
    return f"{years} years"


def _compound(p, rate, years):
    amount = Fraction(p)
    for _ in range(years):
        amount *= 1 + Fraction(rate, 100)
    return amount


def chk_ci(m):
    amount = _compound(m["p"], m["rate"], m["n"])
    return rs(amount - m["p"] if m["what"] == "ci" else amount)


def chk_ci_difference(m):
    p, r = m["p"], m["rate"]
    return rs((_compound(p, r, 2) - p) - Fraction(p * r * 2, 100))


def chk_ci_growth(m):
    for j in range(1, 20):
        if m["k"] ** j == m["k"] ** m["m"]:
            return f"{m['n'] * j} years"
    raise AssertionError


def chk_rp_share(m):
    return rs(Fraction(m["total"]) * Fraction(m["parts"][m["who"]], sum(m["parts"])))


def chk_rp_chain(m):
    f = Fraction(m["a"], m["b"]) * Fraction(m["c"], m["d"])
    return f"{f.numerator} : {f.denominator}"


def chk_rp_add(m):
    ks = [k for k in range(1, 2000) if (m["ra"] * k + m["x"]) * m["rd"] == (m["rb"] * k + m["x"]) * m["rc"]]
    assert len(ks) == 1
    return str(min(m["ra"], m["rb"]) * ks[0])


def chk_rp_fourth(m):
    return n(Fraction(m["b"] * m["c"], m["a"]))


def chk_av_list(m):
    return n(Fraction(sum(m["values"]), len(m["values"])))


def chk_av_replace(m):
    return n(Fraction(m["mean"] * m["n"] - m["old"] + m["new"], m["n"]))


def chk_av_teacher(m):
    return f"{(m['n'] + 1) * (m['age'] + m['d']) - m['n'] * m['age']} years"


def chk_av_natural(m):
    k = m["n"]
    seq = {"natural numbers": range(1, k + 1), "odd numbers": range(1, 2 * k, 2), "even numbers": range(2, 2 * k + 1, 2)}[m["which"]]
    return n(Fraction(sum(seq), k))


def chk_av_middle(m):
    return str(5 * m["a"] - 2 * m["b"] - 2 * m["c"])


def chk_tw_together(m):
    return days(1 / (Fraction(1, m["x"]) + Fraction(1, m["y"])))


def chk_tw_other(m):
    return days(1 / (1 / Fraction(m["t"]) - Fraction(1, m["x"])))


def chk_tw_leave(m):
    x, y, d = m["x"], m["y"], m["d"]
    left = 1 - d * (Fraction(1, x) + Fraction(1, y))
    return days(left / Fraction(1, y))


def chk_tw_pipes(m):
    return hours(1 / (Fraction(1, m["x"]) - Fraction(1, m["y"])))


def chk_tw_efficiency(m):
    b_rate = Fraction(1, (m["k"] + 1) * m["t"])  # B does 1/((k+1) t) of the work a day, A does k times that
    return days(1 / (m["k"] * b_rate))


def chk_tw_men(m):
    return days(Fraction(m["m"] * m["d"], m["m2"]))


def chk_ww_two(m):
    r1, r2 = Fraction(1, m["x"]), Fraction(1, m["y"])
    return rs(m["w"] * r1 / (r1 + r2))


def chk_ww_three(m):
    rates = [Fraction(1, m["x"]), Fraction(1, m["y"]), Fraction(1, m["z"])]
    return rs(m["w"] * rates[2] / sum(rates))


def chk_ww_manhours(m):
    return rs(Fraction(m["x"]) / (m["m1"] * m["h1"] * m["d1"]) * (m["m2"] * m["h2"] * m["d2"]))


def chk_ww_total(m):
    return rs(sum(m["s"] * m["a"] + m["h"] * m["b"] for _ in range(m["d"])))


def chk_td_avg(m):
    dist = m["a"] * m["b"]
    return n(Fraction(2 * dist) / (Fraction(dist, m["a"]) + Fraction(dist, m["b"]))) + " km/h"


def _mps(kmh):
    return Fraction(kmh * 1000, 3600)


def chk_td_pole(m):
    return secs(Fraction(m["length"]) / _mps(m["v"]))


def chk_td_platform(m):
    return secs(Fraction(m["length"] + m["plat"]) / _mps(m["v"]))


def chk_td_two_trains(m):
    rel = m["v1"] + m["v2"] if m["opposite"] else abs(m["v1"] - m["v2"])
    return secs(Fraction(m["l1"] + m["l2"]) / _mps(rel))


def chk_td_boat_speed(m):
    return n(Fraction(m["down"] + m["up"], 2)) + " km/h"


def chk_td_boat_time(m):
    return hours(Fraction(m["d"], m["u"] + m["s"]) + Fraction(m["d"], m["u"] - m["s"]))


def chk_td_overtake(m):
    # the second car has travelled t hours when v2 t = v1 (t + h)
    t = Fraction(m["v1"] * m["h"]) / (m["v2"] - m["v1"])
    assert m["v2"] * t == m["v1"] * (t + m["h"])
    return hours(t)


def chk_cc_angle(m):
    minutes = (m["h"] % 12) * 60 + m["m"]
    hour_hand = Fraction(minutes, 2) % 360
    minute_hand = Fraction(6 * m["m"])
    diff = abs(hour_hand - minute_hand)
    return n(min(diff, 360 - diff)) + " degrees"


def chk_cc_coincide(m):
    h = m["h"]
    t = Fraction(60 * h, 11)
    assert (Fraction(30 * h) + t / 2 - 6 * t) % 360 == 0  # the hands are together at t minutes past h
    whole, rest = divmod(t.numerator, t.denominator)
    return f"{whole} {rest}/{t.denominator} minutes past {h}" if rest else f"{whole} minutes past {h}"


def chk_cc_weekday_ref(m):
    return zeller_weekday(date.fromisoformat(m["target"]))


def chk_cc_weekday_famous(m):
    return zeller_weekday(date.fromisoformat(m["date"]))


def chk_cc_leap(m):
    leaps = [m["right"]] + [w for w in m["wrong"] if calendar.isleap(w)]
    assert leaps == [m["right"]] and calendar.isleap(m["right"])
    return str(m["right"])


def chk_cc_counts(m):
    target = {"coincide": [0], "point in opposite directions": [180], "form a right angle": [90, 270]}[m["what"]]
    limit = 330 * m["hours"]  # 5.5 degrees gained per minute over 60 x hours minutes
    total = sum(1 for t in target for k in range(0, 200) if 0 <= 360 * k + t < limit)
    return str(total)


def chk_pt_two(m):
    return rs(Fraction(m["profit"] * m["b"], m["a"] + m["b"]))


def chk_pt_months(m):
    return rs(Fraction(m["profit"] * m["y"] * m["n"], m["x"] * m["m"] + m["y"] * m["n"]))


def chk_pt_join(m):
    f = Fraction(m["x"] * 12, m["y"] * (12 - m["k"]))
    return f"{f.numerator} : {f.denominator}"


def chk_pt_find(m):
    ys = [y for y in range(500, 40000, 500) if Fraction(m["x"] * 12, y * (12 - m["k"])) == Fraction(m["a"], m["b"])]
    assert len(ys) == 1
    return rs(ys[0])


def chk_pt_three(m):
    w = [m["x"] * 12, m["y"] * 8, m["z"] * 4]
    return rs(Fraction(m["profit"] * w[2], sum(w)))


def chk_me_rect_diag(m):
    return n(isqrt_exact(Fraction(m["l"] ** 2 + m["b"] ** 2))) + " cm"


def chk_me_rect_area(m):
    return n(m["l"] * m["b"]) + " sq cm"


def chk_me_rect_perimeter(m):
    return n(m["l"] + m["b"] + m["l"] + m["b"]) + " cm"


def chk_me_circle_area(m):
    return n(Fraction(22 * m["r"] * m["r"], 7)) + " sq cm"


def chk_me_circle_circ(m):
    return n(Fraction(44 * m["r"], 7)) + " cm"


def chk_me_circle_radius(m):
    return n(Fraction(m["c"]) * 7 / 44) + " cm"


def chk_me_cyl_vol(m):
    return n(Fraction(22 * m["r"] ** 2 * m["h"], 7)) + " cubic cm"


def chk_me_cyl_csa(m):
    return n(Fraction(44 * m["r"] * m["h"], 7)) + " sq cm"


def chk_me_cyl_tsa(m):
    return n(Fraction(44 * m["r"] * (m["h"] + m["r"]), 7)) + " sq cm"


def chk_me_cuboid_vol(m):
    a, b, c = m["dims"]
    return n(math.prod((a, b, c))) + " cubic cm"


def chk_me_cuboid_tsa(m):
    a, b, c = m["dims"]
    return n(2 * a * b + 2 * b * c + 2 * a * c) + " sq cm"


def chk_me_cuboid_diag(m):
    return n(isqrt_exact(Fraction(sum(v * v for v in m["dims"])))) + " cm"


def chk_me_melt(m):
    assert m["a"] ** 3 % m["b"] ** 3 == 0
    return n(m["a"] ** 3 // m["b"] ** 3)


def chk_me_triangle(m):
    a, b, c = m["sides"]
    s = Fraction(a + b + c, 2)
    return n(isqrt_exact(s * (s - a) * (s - b) * (s - c))) + " sq cm"


def chk_me_rhombus_area(m):
    return n(Fraction(m["d1"] * m["d2"], 2)) + " sq cm"


def chk_me_rhombus_side(m):
    return n(isqrt_exact(Fraction(m["d1"], 2) ** 2 + Fraction(m["d2"], 2) ** 2)) + " cm"


def chk_me_sphere_area(m):
    return n(Fraction(4 * 22 * m["r"] ** 2, 7)) + " sq cm"


def chk_me_sphere_vol(m):
    return n(Fraction(4 * 22 * m["r"] ** 3, 21)) + " cubic cm"


def chk_ns_hcf(m):
    nums = m["nums"]
    return str(max(d for d in range(1, min(nums) + 1) if all(v % d == 0 for v in nums)))


def chk_ns_lcm(m):
    nums = m["nums"]
    return str(next(v for v in range(max(nums), math.prod(nums) + 1) if all(v % x == 0 for x in nums)))


def chk_ns_other(m):
    found = [b for b in range(1, m["l"] + 1) if math.gcd(m["a"], b) == m["h"] and m["a"] * b // m["h"] == m["l"]]
    assert len(found) == 1
    return str(found[0])


def chk_ns_least_rem(m):
    xs, rem = m["xs"], m["rem"]
    return str(next(v for v in range(rem + 1, 10 ** 6) if all(v % x == rem for x in xs)))


def chk_ns_greatest(m):
    a, b, r1, r2 = m["a"], m["b"], m["r1"], m["r2"]
    return str(max(d for d in range(max(r1, r2) + 1, min(a, b) + 1) if a % d == r1 and b % d == r2))


def chk_ns_divisible(m):
    fits = [v for v in [m["right"]] + list(m["wrong"]) if v % m["d"] == 0]
    assert fits == [m["right"]]
    return str(m["right"])


def chk_ns_remainder(m):
    return str((m["a"] ** m["n"]) % m["m"])


def chk_ns_unit(m):
    value = m["a"] ** m["n"] * (m["b"] ** m["k"] if "b" in m else 1)
    return str(value % 10)


def chk_ns_sum(m):
    k = m["n"]
    f = {"natural": lambda i: i, "odd": lambda i: 2 * i - 1, "even": lambda i: 2 * i, "squares": lambda i: i * i, "cubes": lambda i: i ** 3}[m["form"]]
    return str(sum(f(i) for i in range(1, k + 1)))


def chk_ns_count(m):
    return str(sum(1 for v in range(m["lo"], m["hi"] + 1) if v % m["d"] == 0))


def chk_series(m):
    t, rule = m["terms"], m["rule"]
    if rule == "arith":
        assert all(t[i + 1] - t[i] == m["d"] for i in range(len(t) - 1))
        return str(t[-1] + m["d"])
    if rule == "geo":
        assert all(t[i + 1] == t[i] * m["m"] for i in range(len(t) - 1))
        return str(t[-1] * m["m"])
    if rule == "square":
        assert t == [(m["s"] + i) ** 2 + m["c"] for i in range(5)]
        return str((m["s"] + 5) ** 2 + m["c"])
    if rule == "second":
        diffs = [t[i + 1] - t[i] for i in range(4)]
        assert all(diffs[i + 1] - diffs[i] == m["e"] for i in range(3))
        return str(t[-1] + diffs[-1] + m["e"])
    if rule == "fib":
        assert all(t[i] == t[i - 1] + t[i - 2] for i in range(2, 5))
        return str(t[-1] + t[-2])
    assert rule == "muladd" and all(t[i + 1] == t[i] * m["m"] + m["c"] for i in range(4))
    return str(t[-1] * m["m"] + m["c"])


def chk_se_letter_step(m):
    pos = m["pos"]
    assert all(pos[i + 1] - pos[i] == m["d"] for i in range(4))
    return UP[pos[-1] + m["d"] - 1]


def chk_se_letter_alt(m):
    pos = m["pos"]
    assert [pos[i + 1] - pos[i] for i in range(4)] == [m["a"], m["b"], m["a"], m["b"]]
    return UP[pos[-1] + m["a"] - 1]


def chk_se_letter_pair(m):
    xs, ys = m["xs"], m["ys"]
    assert all(xs[i + 1] - xs[i] == m["dx"] and ys[i + 1] - ys[i] == m["dy"] for i in range(3))
    return UP[xs[-1] + m["dx"] - 1] + UP[ys[-1] + m["dy"] - 1]


def chk_cd_shift(m):
    return "".join(UP[(UP.index(c) + m["k"]) % 26] for c in m["w2"])


def chk_cd_mirror(m):
    return "".join(UP[::-1][UP.index(c)] for c in m["w2"])


def chk_cd_value(m):
    return str(sum(UP.index(c) + 1 for c in m["w"]))


def chk_cd_word(m):
    words, codes = m["w"], m["c"]
    common = set(m["s1"]) & set(m["s2"])
    assert common == {codes[1]}
    if m["ask"] == words[0]:
        return (set(m["s1"]) - common).pop()
    return (set(m["s2"]) - common).pop()


# generation step and gender of each relation word: (generation change of the person relative to the other, gender)
STEP = {"father": (1, "m"), "mother": (1, "f"), "son": (-1, "m"), "daughter": (-1, "f"), "brother": (0, "m"), "sister": (0, "f"),
        "husband": (0, "m"), "wife": (0, "f")}
TERM = {"Grandfather": (2, "m"), "Grandmother": (2, "f"), "Uncle": (1, "m"), "Aunt": (1, "f"), "Cousin": (0, None), "Nephew": (-1, "m"),
        "Niece": (-1, "f"), "Grandson": (-2, "m"), "Granddaughter": (-2, "f"), "Daughter-in-law": (-1, "f"), "Son-in-law": (-1, "m"),
        "Brother-in-law": (0, "m"), "Sister-in-law": (0, "f"), "Father-in-law": (1, "m"), "Mother-in-law": (1, "f"), "Father": (1, "m"),
        "Mother": (1, "f"), "Brother": (0, "m")}


def _term_ok(term, gen, gender):
    g, sex = TERM[term]
    return g == gen and (sex is None or sex == gender)


def chk_bl_phrase(m):
    words = m["phrase"].replace("my ", "", 1).replace("'s", "").split()
    steps = [STEP[w] for w in words]
    gen = sum(s[0] for s in steps)  # walking outwards from the speaker: a father is one generation up, a son one down
    assert _term_ok(m["answer"], gen, steps[-1][1]), (m["phrase"], m["answer"])
    assert steps[-1][1] == m["gender"]
    return m["answer"]


def chk_bl_chain(m):
    first, second = (s.strip() for s in m["stmt"].split(". ")[:2])
    rel1 = first.split(" the ")[1].split(" of ")[0]
    rel2 = second.rstrip(".").split(" the ")[1].split(" of ")[0]
    # "A is the R1 of B": A is one generation above B when R1 is father/mother; below when son/daughter.
    gen = STEP[rel1][0] + STEP[rel2][0]
    assert _term_ok(m["answer"], gen, STEP[rel1][1]), (m["stmt"], m["answer"])
    return m["answer"]


HEADINGS = "NESW"
NAMES = {"N": "North", "E": "East", "S": "South", "W": "West"}


def chk_ds_face(m):
    i = m["start"]
    for t in m["turns"]:
        i = (i + {"left": 3, "right": 1, "back": 2}[t]) % 4
    return NAMES[HEADINGS[i]]


def _walk_names(m):
    x = y = 0
    i = m["start"]
    for turn, dist in m["legs"]:
        i = (i + {"none": 0, "left": 3, "right": 1}[turn]) % 4
        h = HEADINGS[i]
        if h == "N":
            y += dist
        elif h == "S":
            y -= dist
        elif h == "E":
            x += dist
        else:
            x -= dist
    return x, y


def chk_ds_path_dist(m):
    x, y = _walk_names(m)
    return n(isqrt_exact(Fraction(x * x + y * y))) + " km"


def chk_ds_path_dir(m):
    x, y = _walk_names(m)
    ns = "North" if y > 0 else "South" if y < 0 else None
    ew = "East" if x > 0 else "West" if x < 0 else None
    return "-".join(w for w in (ns, ew) if w)


def chk_ro_total(m):
    total = m["p"] + m["q"] - 1
    assert total - m["p"] + 1 == m["q"]
    return str(total)


def chk_ro_between(m):
    lo, hi = sorted((m["p"], m["q"]))
    return str(len(range(lo + 1, hi)))


def chk_ro_bottom(m):
    row = list(range(1, m["n"] + 1))  # ranks from the top
    return str(list(reversed(row)).index(m["p"]) + 1)


def chk_ro_shift(m):
    total = m["p"] + m["x"] + m["q"] - 1
    assert total - (m["p"] + m["x"]) + 1 == m["q"]
    return str(total)


def chk_ro_order(m):
    names, pairs = m["names"], m["pairs"]
    orders = [p for p in itertools.permutations(range(3)) if all(p.index(a) < p.index(b) for a, b in pairs)]
    top = {names[p[0]] for p in orders}
    bottom = {names[p[2]] for p in orders}
    wants_top = A.SUPERLATIVE[m["more"]] in m["question"]
    group = top if wants_top else bottom
    assert len(group) == 1
    return group.pop()


def _is_prime(k):
    return k > 1 and all(k % d for d in range(2, k))


def _is_square(k):
    return any(i * i == k for i in range(k + 1))


def _is_cube(k):
    return any(i ** 3 == k for i in range(k + 1))


def chk_oo_numbers(m):
    rule = m["rule"]
    pred = {"prime": _is_prime, "square": _is_square, "cube": _is_cube, "even": lambda k: k % 2 == 0}.get(rule) or (
        lambda k, d=int(rule[4:]) if rule.startswith("mult") else 1: k % d == 0)
    fits = [k for k in m["items"] if pred(k)]
    if len(fits) == 1:
        return str(fits[0])
    assert len(fits) == 3
    return str(next(k for k in m["items"] if not pred(k)))


def chk_oo_words(m):
    owner = {}
    for name, members in A.POOLS.items():
        for w in members:
            assert w not in owner, f"{w} is in two pools"
            owner[w] = name
    assert {owner[w] for w in m["majority"]} == {m["a"]} and owner[m["odd"]] == m["b"] and m["a"] != m["b"]
    return m["odd"]


def chk_oo_letters(m):
    gaps = [y - x for x, y in m["pairs"]]
    assert gaps[:3] == [m["g"]] * 3 and gaps[3] == m["h"] != m["g"]
    return UP[m["pairs"][3][0] - 1] + UP[m["pairs"][3][1] - 1]


FACTS = {("capital", "Japan"): "Tokyo", ("capital", "India"): "New Delhi", ("capital", "Canada"): "Ottawa", ("capital", "Australia"): "Canberra",
         ("young", "Horse"): "Foal", ("young", "Cow"): "Calf", ("young", "Sheep"): "Lamb", ("sound", "Horse"): "Neigh", ("sound", "Lion"): "Roar",
         ("instrument", "Barometer"): "Atmospheric pressure", ("instrument", "Ammeter"): "Electric current", ("state_capital", "Gujarat"): "Gandhinagar",
         ("state_capital", "Kerala"): "Thiruvananthapuram", ("state_capital", "Odisha"): "Bhubaneswar", ("author", "Valmiki"): "Ramayana",
         ("unit", "Newton"): "Force", ("unit", "Pascal"): "Pressure"}


def chk_an_words(m):
    table = dict(A.RELATIONS[m["rel"]][1])
    assert len(table) == len(A.RELATIONS[m["rel"]][1]) and len(set(table.values())) == len(table)  # a clean one-to-one table
    for (rel, key), value in FACTS.items():
        if rel == m["rel"] and key in table:
            assert table[key] == value
    assert table[m["k1"]] == m["v1"] and table[m["k2"]] == m["v2"]
    return m["k2"] if m["reverse"] else m["v2"]


RULES = {"the square of the number": lambda k: k ** 2, "the cube of the number": lambda k: k ** 3, "the square of the number plus 1": lambda k: k ** 2 + 1,
         "the square of the number minus 1": lambda k: k ** 2 - 1, "n(n + 1)": lambda k: k * (k + 1), "n(n - 1)": lambda k: k * (k - 1),
         "double the number": lambda k: 2 * k, "triple the number": lambda k: 3 * k, "double the number plus 1": lambda k: 2 * k + 1,
         "n^2 + n + 1": lambda k: k * k + k + 1}


def chk_an_numbers(m):
    f = RULES[m["rule"]]
    return str(f(m["c"]))


# ---- syllogisms: a model checker over ELEMENTS (universe of five things), different from the module's region-set method

TERMS = 3


def _element_models():
    for assign in itertools.product(range(8), repeat=5):
        if all(any(assign_e >> t & 1 for assign_e in assign) for t in range(TERMS)):  # every group has a member
            yield assign


def _true(assign, stmt):
    form, i, j = stmt
    members = [(e >> i & 1, e >> j & 1) for e in assign]
    if form == "A":
        return all(b for a, b in members if a)
    if form == "E":
        return not any(a and b for a, b in members)
    if form == "I":
        return any(a and b for a, b in members)
    return any(a and not b for a, b in members)


_MODELS = list(_element_models())
_CACHE: dict = {}


def element_follows(premises, conclusion) -> bool:
    key = (tuple(map(tuple, premises)), tuple(conclusion))
    if key not in _CACHE:
        ok = [a for a in _MODELS if all(_true(a, tuple(p)) for p in premises)]
        assert ok
        _CACHE[key] = all(_true(a, tuple(conclusion)) for a in ok)
    return _CACHE[key]


def _verdict(f1, f2):
    return A.ANSWERS4[2] if f1 and f2 else A.ANSWERS4[0] if f1 else A.ANSWERS4[1] if f2 else A.ANSWERS4[3]


def chk_sy_two(m):
    c1, c2 = m["conclusions"]
    return _verdict(element_follows(m["premises"], c1), element_follows(m["premises"], c2))


def chk_sc_individual(m):
    c1, c2 = m["conclusions"]
    return _verdict(element_follows(m["premises"], c1), element_follows(m["premises"], c2))


def chk_sc_order(m):
    closure = set(map(tuple, m["pairs"]))
    for _ in range(3):
        closure |= {(a, d) for a, b in closure for c, d in closure if b == c}
    (x1, y1), (x2, y2) = m["c"]
    return _verdict((x1, y1) in closure, (x2, y2) in closure)


def _eval_average(text, nums, mean):
    total = sum(nums)
    k = int(text.rsplit(" ", 1)[-1].rstrip(".")) if text[-1].isdigit() else None
    if text.startswith("The sum of the numbers is"):
        return total == k
    if text.startswith("At least one number is not less than"):
        return max(nums) >= k
    if text.startswith("At least one number is not more than"):
        return min(nums) <= k
    if text.startswith("Every number is equal to"):
        return all(v == k for v in nums)
    if text.startswith("All the numbers are greater than"):
        return all(v > k for v in nums)
    if text.startswith("Every number is less than"):
        return all(v < k for v in nums)
    if text.startswith("The largest number is more than"):
        return max(nums) > k
    raise AssertionError(text)


def chk_sc_average(m):
    n_, mean = m["n"], m["m"]
    rng = random.Random(1)
    samples = [[mean] * n_]
    for _ in range(200):
        nums = [rng.randrange(0, 2 * mean + 1) for _ in range(n_ - 1)]
        nums.append(mean * n_ - sum(nums))
        if nums[-1] >= 0:
            samples.append(nums)
    flags = []
    for text in m["c"]:
        text = text.format(**{"n": n_, "m": mean, "s": n_ * mean, "s2": n_ * mean + n_, "m2": mean + 1})
        flags.append(all(_eval_average(text, s, mean) for s in samples))  # holds in every sample = follows (checked below for the false ones)
    assert flags == m["flags"], (m["c"], flags, m["flags"])
    return _verdict(*flags)


CHECKERS = {
    "pct_of": chk_pct_of, "pct_what": chk_pct_what, "pct_successive": chk_pct_successive, "pct_original": chk_pct_original, "pct_pass": chk_pct_pass,
    "pl_sp": chk_pl_sp, "pl_cp": chk_pl_cp, "pl_percent": chk_pl_percent, "pl_discount": chk_pl_discount, "pl_two": chk_pl_two,
    "si_amount": chk_si, "si_interest": chk_si, "si_rate": chk_si_rate, "si_time": chk_si_time, "si_multiple": chk_si_multiple,
    "ci_interest": chk_ci, "ci_amount": chk_ci, "ci_difference": chk_ci_difference, "ci_growth": chk_ci_growth,
    "rp_share": chk_rp_share, "rp_chain": chk_rp_chain, "rp_add": chk_rp_add, "rp_fourth": chk_rp_fourth,
    "av_list": chk_av_list, "av_replace": chk_av_replace, "av_teacher": chk_av_teacher, "av_natural": chk_av_natural, "av_middle": chk_av_middle,
    "tw_together": chk_tw_together, "tw_other": chk_tw_other, "tw_leave": chk_tw_leave, "tw_pipes": chk_tw_pipes, "tw_efficiency": chk_tw_efficiency,
    "tw_men": chk_tw_men, "ww_two": chk_ww_two, "ww_three": chk_ww_three, "ww_manhours": chk_ww_manhours, "ww_total": chk_ww_total,
    "td_avg": chk_td_avg, "td_pole": chk_td_pole, "td_platform": chk_td_platform, "td_two_trains": chk_td_two_trains,
    "td_boat_speed": chk_td_boat_speed, "td_boat_time": chk_td_boat_time, "td_overtake": chk_td_overtake,
    "cc_angle": chk_cc_angle, "cc_coincide": chk_cc_coincide, "cc_weekday_ref": chk_cc_weekday_ref, "cc_weekday_famous": chk_cc_weekday_famous,
    "cc_leap": chk_cc_leap, "cc_counts": chk_cc_counts,
    "pt_two": chk_pt_two, "pt_months": chk_pt_months, "pt_join": chk_pt_join, "pt_find": chk_pt_find, "pt_three": chk_pt_three,
    "me_rect_diag": chk_me_rect_diag, "me_rect_area": chk_me_rect_area, "me_rect_perimeter": chk_me_rect_perimeter,
    "me_circle_area": chk_me_circle_area, "me_circle_circ": chk_me_circle_circ, "me_circle_radius": chk_me_circle_radius,
    "me_cyl_vol": chk_me_cyl_vol, "me_cyl_csa": chk_me_cyl_csa, "me_cyl_tsa": chk_me_cyl_tsa, "me_cuboid_vol": chk_me_cuboid_vol,
    "me_cuboid_tsa": chk_me_cuboid_tsa, "me_cuboid_diag": chk_me_cuboid_diag, "me_melt": chk_me_melt, "me_triangle": chk_me_triangle,
    "me_rhombus_area": chk_me_rhombus_area, "me_rhombus_side": chk_me_rhombus_side, "me_sphere_area": chk_me_sphere_area, "me_sphere_vol": chk_me_sphere_vol,
    "ns_hcf": chk_ns_hcf, "ns_lcm": chk_ns_lcm, "ns_other": chk_ns_other, "ns_least_rem": chk_ns_least_rem, "ns_greatest": chk_ns_greatest,
    "ns_divisible": chk_ns_divisible, "ns_remainder": chk_ns_remainder, "ns_unit": chk_ns_unit, "ns_sum": chk_ns_sum, "ns_count": chk_ns_count,
    "se_arith": chk_series, "se_geo": chk_series, "se_square": chk_series, "se_second": chk_series, "se_fib": chk_series, "se_muladd": chk_series,
    "se_letter_step": chk_se_letter_step, "se_letter_alt": chk_se_letter_alt, "se_letter_pair": chk_se_letter_pair,
    "cd_shift": chk_cd_shift, "cd_mirror": chk_cd_mirror, "cd_value": chk_cd_value, "cd_word": chk_cd_word,
    "bl_phrase": chk_bl_phrase, "bl_chain": chk_bl_chain, "ds_face": chk_ds_face, "ds_path_dist": chk_ds_path_dist, "ds_path_dir": chk_ds_path_dir,
    "ro_total": chk_ro_total, "ro_between": chk_ro_between, "ro_bottom": chk_ro_bottom, "ro_shift": chk_ro_shift, "ro_order": chk_ro_order,
    "oo_words": chk_oo_words, "oo_numbers": chk_oo_numbers, "oo_letters": chk_oo_letters, "an_words": chk_an_words, "an_numbers": chk_an_numbers,
    "sy_two": chk_sy_two, "sc_order": chk_sc_order, "sc_individual": chk_sc_individual, "sc_average": chk_sc_average,
}


# --------------------------------------------------------------------------- the tests


@pytest.mark.parametrize("area", A.AREAS)
def test_every_question_is_valid_and_its_answer_is_recomputed(area):
    kinds = Counter()
    for seed in range(SEEDS):
        q = A.draw(area, random.Random(f"{area}:{seed}"))
        kinds[q.kind] += 1
        assert q.area == area
        assert len(q.options) == 4 and len({norm_text(o) for o in q.options}) == 4, q.options
        assert 0 <= q.answer_index <= 3
        right = q.options[q.answer_index]
        assert q.kind in CHECKERS, f"no independent checker for {q.kind}"
        expected = CHECKERS[q.kind](q.meta)
        assert expected == right, f"{q.kind}: {q.question} -> shown {right!r}, recomputed {expected!r}"
        assert [o for o in q.options if o == expected] == [expected]  # exactly one option is right
        assert right in q.explanation and q.explanation.rstrip().endswith(f"{right}.")
        why = validate_mcq(GenMcq(question=q.question, options=q.options, answer_index=q.answer_index, explanation=q.explanation))
        assert why is None, why
    assert sum(kinds.values()) == SEEDS


def test_every_template_is_used_and_has_a_checker():
    seen = set()
    for area in A.AREAS:
        for seed in range(SEEDS):
            seen.add(A.draw(area, random.Random(f"{area}:{seed}")).kind)
    assert seen <= set(CHECKERS)
    assert set(CHECKERS) - seen <= {"me_cyl_vol"}, set(CHECKERS) - seen  # rarely drawn, never absent for long; asserted below
    for seed in range(SEEDS * 5):
        if "me_cyl_vol" in seen:
            break
        seen.add(A.draw("mensuration", random.Random(f"mensuration:x{seed}")).kind)
    assert set(CHECKERS) == seen


@pytest.mark.parametrize("area", A.AREAS)
def test_public_api_shape_determinism_and_no_repeats(area):
    for seed in range(5):
        batch = A.generate_questions(area, 20, seed)
        assert len(batch) == 20
        assert len({q["question"] for q in batch}) == 20, "a question repeated inside one batch"
        for q in batch:
            assert set(q) == {"question", "options", "answer_index", "explanation", "area"} and q["area"] == area
            assert all(isinstance(o, str) for o in q["options"])
        assert batch == A.generate_questions(area, 20, seed)  # the same seed always gives the same questions
    assert A.generate_questions(area, 20, 1) != A.generate_questions(area, 20, 2)
    assert A.generate_questions(area, 0, 1) == []


def test_public_api_single_question_over_many_seeds():
    for area in A.AREAS:
        for seed in range(SEEDS):
            q = A.generate_questions(area, 1, seed)[0]
            assert len(set(q["options"])) == 4 and 0 <= q["answer_index"] < 4


def test_unknown_area_is_rejected():
    with pytest.raises(ValueError):
        A.generate_questions("astrology", 5, 1)


def test_areas_labels_and_rotation():
    assert len(A.AREAS) == len(set(A.AREAS)) == 22 and set(A.LABELS) == set(A.AREAS) == set(A.TEMPLATES)
    assert A.AREAS[0] == "percentage" and "syllogism" in A.AREAS
    start = date(2026, 9, 21)
    seen = [A.area_for_date(date.fromordinal(start.toordinal() + i)) for i in range(len(A.AREAS))]
    assert set(seen) == set(A.AREAS)  # a full rotation visits every area once
    assert A.area_for_date(start) == A.area_for_date(date.fromordinal(start.toordinal() + len(A.AREAS)))
    assert A.areas_for_date(start)[0] == A.area_for_date(start) and len(set(A.areas_for_date(start))) == 4


def test_mixed_questions_mix_three_or_four_areas_and_are_deterministic():
    for offset in range(0, 44):
        day = date.fromordinal(date(2026, 9, 21).toordinal() + offset)
        qs = A.mixed_questions(day)
        assert len(qs) == 20 and len({q["question"] for q in qs}) == 20
        areas = Counter(q["area"] for q in qs)
        assert 3 <= len(areas) <= 4 and areas[A.area_for_date(day)] == 8
        assert qs == A.mixed_questions(day)
    assert len(A.mixed_questions(date(2026, 9, 21), 10)) == 10 and A.mixed_questions(date(2026, 9, 21), 0) == []


def test_label_key_matches_the_syllabus_titles():
    assert A.label_key("Ratio & proportion") == A.label_key("Ratio and proportion") == A.label_key(A.LABELS["ratio_proportion"])
    assert A.label_key("Profit & loss") == A.label_key(A.LABELS["profit_loss"])
    assert A.label_key("Analogies") == A.label_key(A.LABELS["analogy"])


def test_formatting_helpers_are_exact():
    assert A.num(12) == "12" and A.num(Fraction(5, 2)) == "2.5" and A.num(Fraction(1, 4)) == "0.25" and A.num(Fraction(-3, 2)) == "-1.5"
    with pytest.raises(A.Retry):
        A.num(Fraction(1, 3))  # never rounds
    assert A.mixed(Fraction(60, 11)) == "5 5/11" and A.mixed(Fraction(6, 1)) == "6" and A.mixed(Fraction(3, 7)) == "3/7"
    assert A.ratio_text(10, 15) == "2 : 3" and A.ratio_text(4, 6, 8) == "2 : 3 : 4"


def test_syllogism_engine_agrees_with_textbook_results():
    all_a_b, all_b_c = ("A", 0, 1), ("A", 1, 2)
    assert A.follows((all_a_b, all_b_c), ("A", 0, 2))  # Barbara
    assert A.follows((all_a_b, all_b_c), ("I", 2, 0))  # (the groups are never empty)
    assert not A.follows((all_a_b, all_b_c), ("A", 2, 0))  # converse fallacy
    assert A.follows((("E", 0, 1), ("A", 2, 1)), ("E", 0, 2))  # No A is B; All C are B => No A is C
    assert A.follows((("E", 1, 2), ("A", 0, 1)), ("E", 0, 2))  # No B is C; All A are B => No A is C
    assert A.follows((("A", 0, 1), ("I", 1, 2)), ("I", 0, 2)) is False  # All A are B; Some B are C: nothing about A and C
    assert A.follows((("E", 0, 1), ("I", 2, 1)), ("O", 2, 0))  # No A is B; Some C are B => Some C are not A
    for premises in (((("A", 0, 1), ("A", 1, 2))),):
        for concl in (("A", 0, 2), ("I", 0, 2), ("E", 0, 2), ("O", 0, 2)):
            assert A.follows(premises, concl) == element_follows(premises, concl)
    table = A._sy_table()
    assert all(table[v] for v in A.ANSWERS4)  # every answer kind can be asked
    for verdict, items in table.items():
        for premises, c1, c2 in items[::37]:  # spot-check the precomputed table with the element model checker
            assert _verdict(element_follows(premises, c1), element_follows(premises, c2)) == verdict


def test_calendar_working_matches_python_for_many_dates():
    for year in (1600, 1900, 1947, 2000, 2024, 2100):
        for month in (1, 2, 3, 7, 12):
            d = date(year, month, 15)
            idx, _text = A.odd_days_working(d)
            assert ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"][idx] == zeller_weekday(d)


def test_number_analogy_rules_are_unambiguous_in_the_family():
    for seed in range(SEEDS):
        q = A.draw("analogy", random.Random(f"analogy:z{seed}"))
        if q.kind != "an_numbers":
            continue
        m = q.meta
        fits = [name for name, f in RULES.items() if f(m["a"]) == RULES[m["rule"]](m["a"]) and f(m["b"]) == RULES[m["rule"]](m["b"])]
        assert all(RULES[name](m["c"]) == RULES[m["rule"]](m["c"]) for name in fits)
