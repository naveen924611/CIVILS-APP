from datetime import date, datetime, timezone

from sqlalchemy import select

from app.db.models import Brief, NewsItem
from app.db.models_v2 import Compilation, Job, Mcq, Mistake, Note, Topic
from app.features import compilation
from app.features.compilation import build, pdf
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway

AUG = datetime(2026, 8, 15, 6, 0, tzinfo=timezone.utc)
SEP = datetime(2026, 9, 3, 6, 0, tzinfo=timezone.utc)


def _story(title, upsc=7, paper="GS2", when=AUG, ap=False, facts=1, **extra):
    return NewsItem(
        url=f"https://x.test/{title}", title=title, source="The Hindu", summary=f"Summary of {title}. " * 3,
        relevance_upsc=upsc, relevance_appsc=3, papers=[paper], mains_angle="Discuss the federal angle.",
        prelims_facts=[{"q": f"Q{i}?", "a": f"A{i}"} for i in range(facts)], is_ap_specific=ap,
        published_at=when, **extra,
    )


def _fill(factory):
    with factory() as db:
        a, b, c = _story("Repo rate held", 9, "GS3"), _story("Polavaram funds", 6, "GS1", ap=True), _story("Old news", 5, when=SEP)
        hidden = _story("Hidden", 2, hidden=True)
        db.add_all([a, b, c, hidden])
        db.flush()
        db.add(Brief(kind="morning", scheduled_for=AUG, status="ready", item_ids=[a.id, b.id]))
        db.add(Brief(kind="morning", scheduled_for=SEP, status="ready", item_ids=[c.id]))
        topic = Topic(title="Federalism")
        db.add(topic)
        db.flush()
        db.add(Note(topic_id=topic.id, sections={"overview": "Union and states share power.", "key_points": ["Art 1", {"text": "Seventh Schedule"}]}))
        mcq = Mcq(question="Which article deals with the Governor?", options=["153", "52", "74"], answer_index=0,
                  explanation="Art 153 says there shall be a Governor.")
        db.add(mcq)
        db.flush()
        db.add(Mistake(mcq_id=mcq.id))
        db.add(Mistake(mcq_id="nothing"))
        db.commit()


def test_month_helpers():
    assert build.previous_month(date(2026, 9, 1)) == "2026-08"
    assert build.previous_month(date(2026, 1, 15)) == "2025-12"
    assert build.valid_month("2026-08") and not build.valid_month("2026-13") and not build.valid_month("2026-8")
    assert build.month_title("2026-08") == "Current affairs digest: August 2026"
    start, end = build.bounds("2026-12", "Asia/Kolkata")
    assert start == datetime(2026, 11, 30, 18, 30, tzinfo=timezone.utc) and end == datetime(2026, 12, 31, 18, 30, tzinfo=timezone.utc)


def test_build_month_from_briefs_notes_and_mistakes(env):
    settings, factory = env
    _fill(factory)
    # the mistake and note rows were created "now"; force them into August
    from sqlalchemy import update
    with factory() as db:
        db.execute(update(Note).values(updated_at=AUG))
        db.execute(update(Mistake).values(updated_at=AUG))
        db.commit()
    with factory() as db:
        row = build.build_month(db, settings, "2026-08")
        md = row.content_md
        assert row.title == "Current affairs digest: August 2026" and row.status == "ready"
    assert md.startswith("# Current affairs digest: August 2026")
    assert "2 stories, 1 notes updated, 1 mistakes" in md
    assert "**Repo rate held**" in md and "**Polavaram funds** (AP)" in md
    assert "Old news" not in md and "Hidden" not in md
    assert md.index("### GS1") < md.index("### GS3")
    assert "## Prelims quick facts" in md and "## Andhra Pradesh this month" in md
    assert "### Federalism" in md and "- Seventh Schedule" in md
    assert "Right answer: 153" in md and "Why: Art 153" in md
    assert "Mains angle: Discuss the federal angle." in md and "Source: The Hindu 15 Aug" in md


def test_rebuild_updates_the_same_row_and_writes_pdf(env):
    settings, factory = env
    _fill(factory)
    with factory() as db:
        first = build.build_month(db, settings, "2026-08")
        rid, path = first.id, first.pdf_path
    assert path == "compilations/2026-08.pdf"
    from app.files import data_path
    data = data_path(settings, path).read_bytes()
    assert data.startswith(b"%PDF")
    with factory() as db:
        again = build.build_month(db, settings, "2026-08")
        assert again.id == rid
        assert len(list(db.scalars(select(Compilation)))) == 1


def test_no_briefs_falls_back_to_collected_items_and_empty_month(env):
    settings, factory = env
    with factory() as db:
        db.add(_story("Loose story", 8))
        db.add(_story("Undated", 8, when=None, fetched_at=AUG))
        db.commit()
        assert build.build_month(db, settings, "2026-07", allow_empty=False) is None
        empty = build.build_month(db, settings, "2026-07")
        assert "Nothing was collected" in empty.content_md
        aug = build.build_month(db, settings, "2026-08", allow_empty=False)
        assert "Loose story" in aug.content_md and "Undated" in aug.content_md


def test_pdf_cleaning_and_layout():
    covered = pdf._cmap(str(pdf.FONT_DIR / pdf.REGULAR))
    assert pdf.clean("India → ₹5 crore తెలుగు done", covered) == "India -> Rs 5 crore done"
    assert pdf.clean("a‌b", None) == "ab"
    md = "# Title\n\nPlain line\n\n## Section\n\n### Sub\n\n- **bold** item\n  detail line\n" + "- long " * 200
    raw = pdf.make_pdf("Title తె", md)
    assert raw.startswith(b"%PDF") and len(raw) > 1000


def test_pdf_falls_back_without_fonts(monkeypatch, tmp_path):
    monkeypatch.setattr(pdf, "FONT_DIR", tmp_path)
    assert pdf.make_pdf("T", "# Hi\n- café త\n").startswith(b"%PDF")


def test_write_pdf_failure_is_not_fatal(env, monkeypatch):
    settings, _ = env
    monkeypatch.setattr(pdf, "make_pdf", lambda *a: (_ for _ in ()).throw(RuntimeError("boom")))
    assert pdf.write_pdf(settings, "2026-08", "T", "# x") is None


def test_job_builds_month_and_rejects_bad_month(env):
    settings, factory = env
    _fill(factory)
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    ids = []
    with factory() as db:
        for payload in ({"month": "2026-08"}, {"month": "bad"}, {}):
            j = Job(type="compilation_build", payload_json=payload)
            db.add(j)
            db.flush()
            ids.append(j.id)
        db.commit()
    run_pending(svc)
    with factory() as db:
        ok, bad, default = (db.get(Job, i) for i in ids)
        assert ok.status == "done" and ok.result_json["month"] == "2026-08"
        assert bad.status == "failed" and "2026-08" in bad.error
        assert default.status == "done" and len(default.result_json["month"]) == 7


def test_monthly_run_and_setup(env, monkeypatch):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    monkeypatch.setattr(build, "previous_month", lambda today: "2026-08")
    assert compilation.run_monthly(svc) is None            # nothing collected: no row, no push
    _fill(factory)
    assert compilation.run_monthly(svc) == "2026-08"
    assert svc.extras["pushed"][0][0] == "Monthly digest ready"
    monkeypatch.setattr(build, "build_month", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("x")))
    assert compilation.run_monthly(svc) is None
    compilation.setup(svc)                                   # scheduler disabled in tests: registers nothing
    assert svc.scheduler.get_job("compilation:monthly") is None
    settings.scheduler_enabled = True
    compilation.setup(svc)
    assert svc.scheduler.get_job("compilation:monthly") is not None


def test_routes(client, auth_header):
    assert client.get("/compilation").status_code == 401
    assert client.get("/compilation", headers=auth_header).json() == []
    assert client.post("/compilation/generate", json={"month": "2026-13"}, headers=auth_header).status_code == 400
    row = client.post("/compilation/generate", json={"month": "2026-08"}, headers=auth_header)
    assert row.status_code == 200 and row.json()["pdf_path"] == "compilations/2026-08.pdf"
    listing = client.get("/compilation", headers=auth_header).json()
    assert listing[0]["month"] == "2026-08" and listing[0]["has_pdf"] is True
    pdf_resp = client.get(f"/compilation/{row.json()['id']}/pdf", headers=auth_header)
    assert pdf_resp.status_code == 200 and pdf_resp.content.startswith(b"%PDF")
    assert client.get("/compilation/nope/pdf", headers=auth_header).status_code == 404
    default = client.post("/compilation/generate", json={}, headers=auth_header)
    assert default.status_code == 200 and build.valid_month(default.json()["month"])
    # a PDF path that escapes the data folder is refused
    from app.db.models_v2 import Compilation as C
    from app.db.session import get_session_factory
    with get_session_factory()() as db:
        c = db.get(C, row.json()["id"])
        c.pdf_path = "../../etc/passwd"
        db.commit()
    assert client.get(f"/compilation/{row.json()['id']}/pdf", headers=auth_header).status_code == 404
    with get_session_factory()() as db:
        c = db.get(C, row.json()["id"])
        c.pdf_path = "compilations/missing.pdf"
        db.commit()
    assert client.get(f"/compilation/{row.json()['id']}/pdf", headers=auth_header).status_code == 404
