"""Library: extraction, upload, OCR jobs, scans, recommended material (no network, fake AI)."""
import io
from pathlib import Path

import httpx
import pytest
from fpdf import FPDF
from PIL import Image
from sqlalchemy import select

from app.config import get_settings
from app.db.models_v2 import Chunk, DocPage, Document, Job, Material
from app.db.session import get_session_factory
from app.features.library import extract, hooks, materials, ocr, processing, uploads
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway

TEXT = (
    "Article 21 protects the right to life and personal liberty. The Supreme Court widened it in the Maneka Gandhi case.\n"
    "It has since covered privacy, dignity and a clean environment for every person in India today."
)
TELUGU = "భారత రాజ్యాంగం ప్రజలకు ప్రాథమిక హక్కులను ఇస్తుంది"
SOURCES = Path(__file__).resolve().parents[2] / "data" / "sources.yaml"


def text_pdf(pages=(TEXT,)) -> bytes:
    pdf = FPDF()
    for t in pages:
        pdf.add_page()
        pdf.set_font("Helvetica", size=12)
        pdf.multi_cell(0, 8, t)
    return bytes(pdf.output())


def picture(color=(200, 180, 120), size=(300, 400), fmt="JPEG") -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, fmt)
    return buf.getvalue()


def scan_pdf(n=2) -> bytes:
    frames = [Image.new("RGB", (300, 400), (240 - i * 20, 230, 200)) for i in range(n)]
    buf = io.BytesIO()
    frames[0].save(buf, "PDF", save_all=True, append_images=frames[1:])
    return buf.getvalue()


@pytest.fixture
def sync_spawn(monkeypatch):
    """Background work runs straight away in tests."""
    monkeypatch.setattr(processing, "spawn", lambda fn, *a: fn(*a))


@pytest.fixture
def lib(env, sync_spawn):
    settings, factory = env
    gw = FullFakeGateway([], texts=[])
    svc = FakeServices.build(settings, factory, gw)
    svc.extras["ocr_pause"] = 0
    processing.set_services(svc)
    hooks.take_dirty()
    yield svc, factory, gw
    processing.set_services(None)


# ---------------------------------------------------------------- extraction

def test_reflow_joins_lines_and_hyphens():
    out = extract.reflow("The consti-\ntution of India\nis the law.\n\nSecond paragraph\nhere.")
    assert out == "The constitution of India is the law.\n\nSecond paragraph here."


def test_telugu_detection():
    assert extract.is_telugu(TELUGU)
    assert not extract.is_telugu("Article 21 and 22")
    assert extract.telugu_ratio("") == 0.0


def test_extract_text_and_scan_pages(tmp_path):
    good = tmp_path / "a.pdf"
    good.write_bytes(text_pdf(("Page one about federalism in India and its features.", "Page two about the Finance Commission.")))
    pages = extract.extract_pages(good)
    assert [p.page for p in pages] == [1, 2] and "federalism" in pages[0].text and not pages[0].needs_ocr
    scan = tmp_path / "s.pdf"
    scan.write_bytes(scan_pdf(2))
    pages = extract.extract_pages(scan)
    assert all(p.needs_ocr for p in pages)
    assert extract.page_image(scan, 1) is not None
    assert extract.page_image(scan, 9) is None
    assert extract.page_count(scan) == 2


def test_bad_files_are_refused_kindly(tmp_path):
    bad = tmp_path / "b.pdf"
    bad.write_bytes(b"%PDF-1.4 not really")
    with pytest.raises(extract.ExtractError):
        extract.open_pdf(bad)
    with pytest.raises(extract.ExtractError):
        extract.normalize_image(b"not a picture")


def test_normalize_image_shrinks_and_converts():
    big = picture(size=(3000, 1000), fmt="PNG")
    jpeg, w, h = extract.normalize_image(big)
    assert max(w, h) == extract.MAX_IMAGE_SIDE and jpeg[:2] == b"\xff\xd8"
    buf = io.BytesIO()
    Image.new("RGBA", (50, 50), (255, 0, 0, 0)).save(buf, "PNG")
    assert extract.normalize_image(buf.getvalue())[1] == 50


def test_images_to_pdf(tmp_path):
    dest = tmp_path / "x.pdf"
    extract.images_to_pdf([picture(), picture((10, 10, 10))], dest)
    assert extract.page_count(dest) == 2


# ---------------------------------------------------------------- upload + processing

def _login_upload(client, auth_header, files, **data):
    return client.post("/library/upload", headers=auth_header, files=files, data=data)


def test_upload_pdf_is_read_and_searchable(client, auth_header, sync_spawn):
    r = _login_upload(client, auth_header, [("files", ("Polity Notes.pdf", text_pdf(), "application/pdf"))])
    assert r.status_code == 200, r.text
    doc_id = r.json()["documents"][0]["id"]
    factory = get_session_factory()
    with factory() as db:
        doc = db.get(Document, doc_id)
        assert doc.title == "Polity Notes" and doc.processing_status == "processed"
        assert doc.status_detail == "Processed · searchable" and doc.pages == 1 and doc.language == "en"
        assert db.scalars(select(Chunk).where(Chunk.document_id == doc_id)).first() is not None
    hits = client.get("/library/search", params={"q": "Maneka Gandhi"}, headers=auth_header).json()["hits"]
    assert hits and hits[0]["document_id"] == doc_id and hits[0]["page"] == 1
    pull = client.get("/sync/pull", headers=auth_header).json()["tables"]
    assert any(d["id"] == doc_id for d in pull["documents"]) and pull["doc_pages"]
    assert client.get(f"/library/documents/{doc_id}/file", headers=auth_header).content[:5] == b"%PDF-"
    assert client.get(f"/library/documents/{doc_id}/text", headers=auth_header).json()["pages"][0]["page"] == 1


def test_upload_rejects_wrong_files(client, auth_header, sync_spawn):
    r = _login_upload(client, auth_header, [("files", ("notes.txt", b"hello", "text/plain"))])
    assert r.status_code == 400
    r = _login_upload(client, auth_header, [("files", ("fake.pdf", b"hello there", "application/pdf"))])
    assert r.status_code == 400 and "PDF" in r.json()["detail"]
    r = _login_upload(client, auth_header, [("files", ("p.jpg", b"not a picture", "image/jpeg"))])
    assert r.status_code == 400


def test_upload_size_limit(client, auth_header, monkeypatch):
    monkeypatch.setattr(get_settings(), "max_upload_mb", 0)
    r = _login_upload(client, auth_header, [("files", ("big.pdf", text_pdf(), "application/pdf"))])
    assert r.status_code == 400 and "bigger" in r.json()["detail"]


def test_scanned_pdf_gets_ocr_job_and_gemini_text(client, auth_header, sync_spawn):
    svc = client.app.state.services
    gw = FullFakeGateway([], texts=["First page text about rivers.", "```\nSecond page text about soils.\n```"])
    svc.gateway = gw
    svc.extras["ocr_pause"] = 0
    r = _login_upload(client, auth_header, [("files", ("scan.pdf", scan_pdf(2), "application/pdf"))])
    doc_id = r.json()["documents"][0]["id"]
    factory = get_session_factory()
    with factory() as db:
        assert db.get(Document, doc_id).processing_status == "processing"
        job = db.scalar(select(Job).where(Job.type == "ocr_page"))
        assert job.payload_json == {"document_id": doc_id, "page": 1}
    run_pending(svc)
    with factory() as db:
        doc = db.get(Document, doc_id)
        pages = {p.page: p for p in db.scalars(select(DocPage).where(DocPage.document_id == doc_id))}
        assert pages[1].text == "First page text about rivers." and pages[1].source == "gemini"
        assert pages[2].text == "Second page text about soils."
        assert doc.processing_status == "processed" and doc.language == "en"
        assert db.scalar(select(Job).where(Job.type == "ocr_page")).result_json["chars"] > 0
    assert [f for f, _ in gw.image_calls] == [] and all(f == "ocr" for f, _ in gw.text_calls)


def test_ocr_when_ai_is_down_waits_then_recovers(lib):
    svc, factory, gw = lib
    with factory() as db:
        doc = Document(title="Scan", type="scan", pages=1)
        db.add(doc)
        db.commit()
        doc_id = doc.id
        uploads.add_image_page(svc, db, doc, picture())
        uploads.finish_image_document(svc, db, doc)
    run_pending(svc)  # AI down: job stays queued
    with factory() as db:
        doc = db.get(Document, doc_id)
        assert doc.processing_status == "waiting" and doc.status_detail == "Waiting for internet"
        assert processing.waiting_detail(doc, True) == "Waiting for internet (Telugu page)"
        job = db.scalar(select(Job).where(Job.type == "ocr_page"))
        assert job.status == "queued" and job.result_json["_attempts"] == 1
        job.result_json = {}  # pretend the retry time has come
        db.commit()
    gw.texts.append(TELUGU)
    run_pending(svc)
    with factory() as db:
        doc = db.get(Document, doc_id)
        assert doc.processing_status == "processed" and doc.language == "te"


def test_ocr_no_text_and_budget_and_last_attempt(lib):
    svc, factory, gw = lib
    with factory() as db:
        doc = Document(title="Blank", type="scan")
        db.add(doc)
        db.commit()
        doc_id = doc.id
        uploads.add_image_page(svc, db, doc, picture())
        uploads.add_image_page(svc, db, doc, picture())
    gw.texts.extend(["NO_TEXT", "Some text here about coasts and ports of India."])
    with factory() as db:
        job = Job(type="ocr_page", payload_json={"document_id": doc_id, "page": 1}, status="queued")
        db.add(job)
        db.commit()
    run_pending(svc)
    with factory() as db:
        pages = {p.page: p.text for p in db.scalars(select(DocPage).where(DocPage.document_id == doc_id))}
        assert pages[1] == "" and pages[2].startswith("Some text")
    # budget full: nothing is read, a job waits for tomorrow
    gw.guard._level = 3
    with factory() as db:
        job = Job(type="ocr_page", payload_json={"document_id": doc_id, "page": 1, "only_this_page": True, "force": True}, status="queued")
        db.add(job)
        db.commit()
    before = len(gw.text_calls)
    run_pending(svc)
    assert len(gw.text_calls) == before
    gw.guard._level = 0
    # a missing document fails plainly
    with factory() as db:
        db.add(Job(type="ocr_page", payload_json={"document_id": "nope", "page": 1}, status="queued"))
        db.commit()
    run_pending(svc)
    with factory() as db:
        failed = db.scalar(select(Job).where(Job.status == "failed"))
        assert failed is not None and "removed" in failed.error


def test_ocr_only_this_page_with_force(lib):
    svc, factory, gw = lib
    with factory() as db:
        doc = Document(title="Mixed", type="scan")
        db.add(doc)
        db.commit()
        doc_id = doc.id
        uploads.add_image_page(svc, db, doc, picture(), text="wrong text from the tablet", source="device_ocr")
        db.add(Job(type="ocr_page", payload_json={"document_id": doc_id, "page": 1, "only_this_page": True, "force": True, "language": "te"}, status="queued"))
        db.commit()
    gw.texts.append(TELUGU)
    run_pending(svc)
    with factory() as db:
        page = db.scalar(select(DocPage).where(DocPage.document_id == doc_id))
        assert page.text == TELUGU and page.source == "gemini"


def test_clean_ocr():
    assert ocr.clean_ocr("  no_text ") == ""
    assert ocr.clean_ocr("```text\nHello world\n```") == "Hello world"


# ---------------------------------------------------------------- scans (camera)

def test_scan_with_device_text_then_more_pages(client, auth_header, sync_spawn):
    files = {"image": ("p.jpg", picture(), "image/jpeg")}
    r = client.post("/library/scans", headers=auth_header, files=files, data={"text": "Article 32 gives the right to constitutional remedies to every citizen."})
    assert r.status_code == 200, r.text
    doc_id, page = r.json()["document_id"], r.json()["page"]
    assert page == 1
    r = client.post("/library/scans", headers=auth_header, files=files, data={"document_id": doc_id, "text": "Article 33 restricts rights of the armed forces in some cases."})
    assert r.json() == {"document_id": doc_id, "page": 2, "language": "en"}
    factory = get_session_factory()
    with factory() as db:
        doc = db.get(Document, doc_id)
        assert doc.type == "scan" and doc.pages == 2 and doc.processing_status == "processed"
        assert db.scalar(select(DocPage).where(DocPage.document_id == doc_id, DocPage.page == 2)).source == "device_ocr"
        assert db.scalars(select(Chunk).where(Chunk.document_id == doc_id)).first() is not None
    img = client.get(f"/library/documents/{doc_id}/pages/2/image", headers=auth_header)
    assert img.status_code == 200 and img.content[:2] == b"\xff\xd8"
    assert client.get(f"/library/documents/{doc_id}/pages/9/image", headers=auth_header).status_code == 404
    assert client.get(f"/library/documents/{doc_id}/file", headers=auth_header).status_code == 404
    bad = client.post("/library/scans", headers=auth_header, files={"image": ("p.jpg", b"xx", "image/jpeg")})
    assert bad.status_code == 400


def test_scan_without_text_queues_server_ocr_and_pdf_page_image(client, auth_header, sync_spawn):
    files = {"image": ("p.jpg", picture(), "image/jpeg")}
    doc_id = client.post("/library/scans", headers=auth_header, files=files).json()["document_id"]
    factory = get_session_factory()
    with factory() as db:
        assert db.scalar(select(Job).where(Job.type == "ocr_page")).status == "queued"
        assert db.get(Document, doc_id).processing_status == "processing"
    pdf_id = _login_upload(client, auth_header, [("files", ("s.pdf", scan_pdf(1), "application/pdf"))]).json()["documents"][0]["id"]
    assert client.get(f"/library/documents/{pdf_id}/pages/1/image", headers=auth_header).status_code == 200
    assert client.get("/library/documents/missing/file", headers=auth_header).status_code == 404
    wrong = client.post("/library/scans", headers=auth_header, files=files, data={"document_id": pdf_id})
    assert wrong.status_code == 400


def test_upload_images_makes_one_document(client, auth_header, sync_spawn):
    files = [("files", (f"p{i}.jpg", picture((i * 40, 100, 100)), "image/jpeg")) for i in range(3)]
    r = _login_upload(client, auth_header, files, title="NCERT photos")
    doc = r.json()["documents"][0]
    assert doc["type"] == "image" and doc["pages"] == 3 and doc["title"] == "NCERT photos"
    with get_session_factory()() as db:
        assert len(list(db.scalars(select(Job).where(Job.type == "ocr_page")))) == 1


def test_retry_paths(client, auth_header, sync_spawn):
    factory = get_session_factory()
    pdf_id = _login_upload(client, auth_header, [("files", ("a.pdf", text_pdf(), "application/pdf"))]).json()["documents"][0]["id"]
    assert client.post(f"/library/documents/{pdf_id}/retry", headers=auth_header).json()["processing_status"] == "processed"
    with factory() as db:
        doc = db.get(Document, pdf_id)
        doc.processing_status = "failed"
        db.commit()
    assert client.post(f"/library/documents/{pdf_id}/retry", headers=auth_header).json()["processing_status"] == "processed"
    with factory() as db:
        doc = Document(title="Scan", type="scan", pages=1, processing_status="needs_ocr")
        db.add(doc)
        db.commit()
        db.add(DocPage(document_id=doc.id, page=1, text=""))
        db.commit()
        scan_id = doc.id
    assert client.post(f"/library/documents/{scan_id}/retry", headers=auth_header).json()["processing_status"] == "processing"


# ---------------------------------------------------------------- hooks

def test_pushed_text_is_indexed_and_deleted_document_is_cleaned(lib):
    svc, factory, gw = lib
    with factory() as db:
        doc = Document(title="Scan", type="scan", pages=1, processing_status="needs_ocr")
        db.add(doc)
        db.commit()
        doc_id = doc.id
        page = DocPage(document_id=doc_id, page=1, text="Directive Principles are in Part IV of the Constitution of India.", source="device_ocr")
        db.add(page)
        db.commit()
        hooks.page_pushed(db, page, True)
    assert hooks.reindex_dirty(svc) == 1
    assert hooks.reindex_dirty(svc) == 0
    with factory() as db:
        assert db.get(Document, doc_id).processing_status == "processed"
        assert db.scalars(select(Chunk).where(Chunk.document_id == doc_id)).first() is not None
    # deleting removes files and chunks
    folder = processing.pages_dir(svc, doc_id)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "p0001.jpg").write_bytes(b"x")
    with factory() as db:
        doc = db.get(Document, doc_id)
        doc.deleted = True
        hooks.document_pushed(db, doc, False)
        db.commit()
        assert db.scalars(select(Chunk).where(Chunk.document_id == doc_id)).first() is None
    assert not folder.exists()
    assert hooks.documents_needing_index(svc) == []


def test_resume_unfinished_documents(lib):
    svc, factory, _gw = lib
    with factory() as db:
        text_doc = Document(title="T", type="pdf", processing_status="processing")
        db.add(text_doc)
        db.commit()
        name = f"{text_doc.id}.pdf"
        (processing.uploads_dir(svc.settings) / name).write_bytes(text_pdf())
        text_doc.file_path = name
        dl = Document(title="D", type="recommended", processing_status="downloading")
        db.add(dl)
        db.commit()
        text_id, dl_id = text_doc.id, dl.id
    assert processing.resume_unfinished(svc) == 1
    with factory() as db:
        assert db.get(Document, text_id).processing_status == "processed"
        assert db.get(Document, dl_id).processing_status == "failed"


def test_missing_file_fails_plainly(lib):
    svc, factory, _gw = lib
    with factory() as db:
        doc = Document(title="Gone", type="pdf", file_path="nothing.pdf", processing_status="uploaded")
        db.add(doc)
        db.commit()
        doc_id = doc.id
    processing.process_document(svc, doc_id)
    with factory() as db:
        assert db.get(Document, doc_id).processing_status == "failed"


# ---------------------------------------------------------------- recommended material

def test_sources_file_is_well_formed():
    items = materials.load_sources(SOURCES)
    assert len(items) >= 10
    keys = [i["key"] for i in items]
    assert len(keys) == len(set(keys))
    assert {i["kind"] for i in items} == {"official", "book"}
    for i in items:
        assert i["kind"] == "book" or i.get("url") is not None
        if i["kind"] == "book":
            assert not i.get("url")  # books are never downloaded
        if i.get("url"):
            assert i["url"].startswith("https://")
        assert i.get("verified") is False  # nothing was checked online when the list was written


def test_seed_is_idempotent_and_marks_unverified(env):
    _settings, factory = env
    items = materials.load_sources(SOURCES)
    with factory() as db:
        first = materials.seed_materials(db, items)
        assert first == len(items)
        assert materials.seed_materials(db, items) == 0
        ncert = db.scalar(select(Material).where(Material.key == "ncert-textbooks"))
        assert ncert.why.endswith("(link not checked yet)") and ncert.kind == "official"
        book = db.scalar(select(Material).where(Material.key == "book-laxmikanth"))
        assert book.kind == "book" and not book.why.endswith("checked yet)")
        materials.seed_materials(db, items[:3])  # an item removed from the list is hidden
        assert db.scalar(select(Material).where(Material.key == "book-laxmikanth")).deleted is True
        materials.seed_materials(db, items)
        assert db.scalar(select(Material).where(Material.key == "book-laxmikanth")).deleted is False
    assert materials.load_sources(Path("/no/such/file.yaml")) == []


def _material_services(lib_tuple, handler, tmp_path, monkeypatch):
    svc, factory, _gw = lib_tuple
    svc.http = httpx.Client(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(materials, "HOST_PAUSE", 0)
    yaml_file = tmp_path / "sources.yaml"
    yaml_file.write_text(
        "items:\n"
        "  - {key: pdf-one, kind: official, title: Sample PDF, why: w, url: 'https://files.test/a.pdf', direct: true, verified: false}\n"
        "  - {key: page-one, kind: official, title: A web page, why: w, url: 'https://files.test/page', direct: false}\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(materials, "sources_path", lambda settings: yaml_file)
    with factory() as db:
        materials.seed_materials(db, materials.load_sources(yaml_file))
    return svc, factory


def test_download_material_reads_pdf(lib, tmp_path, monkeypatch):
    seen = []

    def handler(req):
        seen.append(req.url.path)
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /private")
        return httpx.Response(200, content=text_pdf(), headers={"content-type": "application/pdf"})

    svc, factory = _material_services(lib, handler, tmp_path, monkeypatch)
    with factory() as db:
        doc = materials.start_download(svc, db, "pdf-one")
        doc_id = doc.id
        again = materials.start_download(svc, db, "pdf-one")  # not downloaded twice
        assert again.id == doc_id
    with factory() as db:
        doc = db.get(Document, doc_id)
        assert doc.processing_status == "processed" and doc.type == "recommended" and doc.pages == 1
    assert seen.count("/a.pdf") == 1
    with factory() as db:
        with pytest.raises(ValueError):
            materials.start_download(svc, db, "page-one")
        with pytest.raises(LookupError):
            materials.start_download(svc, db, "unknown")


def test_download_refusals(lib, tmp_path, monkeypatch):
    mode = {"robots": "User-agent: *\nDisallow: /", "body": b"%PDF-1.4 ok", "status": 200}

    def handler(req):
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text=mode["robots"])
        return httpx.Response(mode["status"], content=mode["body"])

    svc, factory = _material_services(lib, handler, tmp_path, monkeypatch)

    def run():
        with factory() as db:
            doc = materials.start_download(svc, db, "pdf-one")
            doc_id = doc.id
        with factory() as db:
            d = db.get(Document, doc_id)
            return d.processing_status, d.status_detail

    status, detail = run()
    assert status == "failed" and "does not allow" in detail  # robots.txt is respected
    mode.update(robots="User-agent: *\nAllow: /", body=b"<html>not a pdf</html>")
    status, detail = run()
    assert status == "failed" and "did not give a PDF" in detail
    mode.update(status=503)
    assert "error (503)" in run()[1]
    mode.update(status=200, body=b"%PDF-1.4 broken")
    assert run()[0] == "failed"
    monkeypatch.setattr(svc.settings, "max_upload_mb", 0)
    mode.update(body=text_pdf())
    assert "too big" in run()[1]


def test_download_network_error(lib, tmp_path, monkeypatch):
    def handler(req):
        if req.url.path == "/robots.txt":
            return httpx.Response(404)
        raise httpx.ConnectError("offline")

    svc, factory = _material_services(lib, handler, tmp_path, monkeypatch)
    with factory() as db:
        doc_id = materials.start_download(svc, db, "pdf-one").id
    with factory() as db:
        assert "Could not reach" in db.get(Document, doc_id).status_detail


def test_download_endpoint_and_check_links(client, auth_header, sync_spawn, monkeypatch):
    r = client.post("/library/materials/nothing/download", headers=auth_header)
    assert r.status_code == 404
    r = client.post("/library/materials/ncert-textbooks/download", headers=auth_header)
    assert r.status_code == 400 and "browser" in r.json()["detail"]
    pull = client.get("/sync/pull", headers=auth_header).json()["tables"]
    assert any(m["key"] == "ncert-textbooks" for m in pull["materials"])

    def handler(req):
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /no")
        return httpx.Response(200 if "ok" in req.url.path else 404)

    monkeypatch.setattr(materials, "HOST_PAUSE", 0)
    c = httpx.Client(transport=httpx.MockTransport(handler))
    rows = materials.check_links(c, [
        {"key": "a", "url": "https://x.test/ok"}, {"key": "b", "url": "https://x.test/no/x"}, {"key": "c", "url": ""},
        {"key": "d", "url": "https://x.test/missing"},
    ])
    assert rows == [("a", "https://x.test/ok", "HTTP 200"), ("b", "https://x.test/no/x", "robots.txt says no"),
                    ("d", "https://x.test/missing", "HTTP 404")]


def test_setup_seeds_and_schedules(lib, monkeypatch):
    import app.features.library as feature

    svc, factory, _gw = lib
    monkeypatch.setattr(materials, "sources_path", lambda settings: SOURCES)
    monkeypatch.setattr(svc.settings, "scheduler_enabled", True)
    feature.setup(svc)
    assert svc.scheduler.get_job("library:reindex") is not None
    with factory() as db:
        assert db.scalar(select(Material).where(Material.key == "pib")) is not None
