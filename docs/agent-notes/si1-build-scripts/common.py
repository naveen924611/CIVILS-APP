import json, re

class N:
    def __init__(s, title, kids=None, imp=None, h=None, page=None, off=None, extra=None):
        s.title, s.kids, s.imp, s.h, s.page, s.off, s.extra = title, kids or [], imp, h, page, off, extra or []

def leaf(t, h=1.5, imp=None, p=None, off=None):
    return N(t, [], imp, h, p, off)

def unit(title, page, imp, off, leaves, h=1.5):
    """leaves: str | (text, hours) | (text, hours, page) | (text, hours, page, official_override)"""
    kids = []
    for l in leaves:
        if isinstance(l, str):
            kids.append(leaf(l, h, imp, page))
        else:
            t = l[0]; hh = l[1] if len(l) > 1 else h; pg = l[2] if len(l) > 2 and l[2] else page
            of = l[3] if len(l) > 3 else None
            kids.append(leaf(t, hh, imp, pg, of))
    return N(title, kids, imp, None, page, off)

def finalize(roots):
    items = []
    def go(n, depth, path, ppage, pimp):
        n.level = depth
        n.page = n.page or ppage
        path2 = path + [n.title]
        if n.kids:
            for k in n.kids:
                go(k, depth + 1, path2, n.page, n.imp if n.imp is not None else pimp)
            n.h = round(sum(k.h for k in n.kids), 2)
            if n.imp is None:
                n.imp = round(sum(k.imp for k in n.kids) / len(n.kids))
        else:
            if n.imp is None:
                n.imp = pimp if pimp is not None else 5
        if n.off and n.kids:
            items.append({"official": n.off, "page": n.page, "path": path2})
        for (o, pg) in n.extra:
            items.append({"official": o, "page": pg, "path": path2})
        if not n.kids and not getattr(n, "suggest", False):
            items.append({"official": n.off or n.title, "page": n.page, "path": path2})
    def ser(n):
        d = {"title": n.title, "level": n.level, "exam_tags": ["APPSC"]}
        if not n.kids:
            d["est_hours"] = n.h
        else:
            d["est_hours"] = round(n.h, 2)
        d["importance"] = n.imp
        d["children"] = [ser(k) for k in n.kids]
        return d
    for r in roots:
        go(r, 0, [], r.page, r.imp)
    return [ser(r) for r in roots], items

def write(outfile, covfile, key, title, supersedes, note, roots, srcpages):
    tree, items = finalize(roots)
    doc = {"key": key, "exam": "APPSC Group-I", "title": title, "verified": True,
           "supersedes": supersedes, "source_note": note, "tree": tree}
    json.dump(doc, open(outfile, "w"), indent=1, ensure_ascii=False)
    cov = {"key": key, "outline_file": outfile.split("/")[-1],
           "source": "Group_I_072026.pdf pages " + srcpages, "items": items}
    json.dump(cov, open(covfile, "w"), indent=1, ensure_ascii=False)
