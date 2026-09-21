import json, re, sys
base = sys.argv[1]
N = lambda t: re.sub(r"[^a-z0-9]+", " ", t.lower()).strip()
for name, cov in (("appsc_g1_prelims_2026.json", "appsc-g1-prelims-2026.json"), ("appsc_g1_mains_2026.json", "appsc-g1-mains-2026.json")):
    d = json.load(open(f"{base}/data/syllabus/{name}")); c = json.load(open(f"{base}/data/exam-specs/coverage/{cov}"))
    nodes = leaves = 0
    def walk(ns, depth=0):
        global nodes, leaves
        for n in ns:
            nodes += 1; assert n["level"] == depth and len(n["title"]) < 300, n["title"]
            assert len({N(x["title"]) for x in n["children"]}) == len(n["children"]), ("dup", n["title"])
            if not n["children"]: leaves += 1; assert 0.5 <= n["est_hours"] <= 4, (n["title"], n["est_hours"])
            walk(n["children"], depth + 1)
    walk(d["tree"]); assert len({N(x["title"]) for x in d["tree"]}) == len(d["tree"])
    bad = 0; papers = set()
    for it in c["items"]:
        cur = d["tree"]; node = None
        for t in it["path"]:
            node = next((x for x in cur if N(x["title"]) == N(t)), None)
            if node is None: bad += 1; print("UNRESOLVED", it["path"]); break
            cur = node["children"]
        papers.add(it["path"][0])
        assert isinstance(it["page"], int)
    missing = {n["title"] for n in d["tree"]} - papers
    # every leaf covered by some item?
    covered = set(tuple(N(t) for t in it["path"]) for it in c["items"])
    unc = []
    def lv(ns, p=()):
        for n in ns:
            q = p + (N(n["title"]),)
            if not n["children"]:
                if q not in covered and not n["title"] in ("DAF and bio-data questions","Current affairs","Home-state knowledge (Andhra Pradesh)","Ethics scenarios","Communication"): unc.append(q)
            lv(n["children"], q)
    lv(d["tree"])
    print(name, d["key"], d["verified"], d["supersedes"], "papers", len(d["tree"]), "nodes", nodes, "leaves", leaves, "items", len(c["items"]), "unresolved", bad, "papers-missing", missing, "uncovered leaves", unc[:5], len(unc))
