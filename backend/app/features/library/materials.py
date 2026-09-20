"""Recommended free material: the list (data/sources.yaml -> `materials` table) and polite downloading.

Rules: only links from the list are fetched; robots.txt is honoured (a site that says no is not worked around, the
app opens it in the browser instead); one request at a time per website with a pause between them; a PDF is
accepted only when it really starts with "%PDF-"; nothing is stored above the upload size limit.

Check the links (needs internet):  python -m app.features.library.materials --check
"""
import logging
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import httpx
import yaml
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models_v2 import Document, Material
from app.features.library import extract, processing
from app.files import uploads_dir
from app.pipelines.news.feeds import USER_AGENT, Robots
from app.services import Services

log = logging.getLogger(__name__)

HOST_PAUSE = 3.0  # seconds between two requests to the same website
UNVERIFIED_NOTE = " (link not checked yet)"
_last_hit: dict[str, float] = {}
_host_lock = threading.Lock()


def sources_path(settings) -> Path:
    """data/sources.yaml sits next to feeds.yaml (repo `data/` folder, or `/app/config` in the container)."""
    return Path(settings.feeds_file).parent / "sources.yaml"


def load_sources(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out = []
    for item in data.get("items", []):
        if item.get("key") and item.get("title"):
            out.append(item)
    return out


def seed_materials(db: Session, items: list[dict]) -> int:
    """Copies the list into the table by `key` (safe to run at every start). Returns rows added or changed."""
    existing = {m.key: m for m in db.scalars(select(Material))}
    changed = 0
    seen: set[str] = set()
    for item in items:
        key = str(item["key"])[:80]
        seen.add(key)
        why = str(item.get("why", ""))[:400]
        if not item.get("verified") and item.get("url"):
            why = (why[: 400 - len(UNVERIFIED_NOTE)] + UNVERIFIED_NOTE)
        values = {
            "kind": "book" if item.get("kind") == "book" else "official",
            "title": str(item["title"])[:300],
            "why": why,
            "needed_for": str(item.get("needed_for", ""))[:200],
            "url": str(item.get("url") or "")[:1000],
            "subject": str(item.get("subject", ""))[:80],
        }
        row = existing.get(key)
        if row is None:
            db.add(Material(key=key, **values))
            changed += 1
        elif row.deleted or any(getattr(row, k) != v for k, v in values.items()):
            for k, v in values.items():
                setattr(row, k, v)
            row.deleted = False
            changed += 1
    for key, row in existing.items():
        if key not in seen and not row.deleted:
            row.deleted = True  # removed from the list
            changed += 1
    db.commit()
    return changed


def seed(services: Services) -> int:
    items = load_sources(sources_path(services.settings))
    if not items:
        return 0
    with services.session_factory() as db:
        return seed_materials(db, items)


def is_direct(item: dict) -> bool:
    return bool(item.get("direct")) and str(item.get("url", "")).lower().startswith("https://")


def direct_keys(settings) -> set[str]:
    return {str(i["key"]) for i in load_sources(sources_path(settings)) if is_direct(i)}


def start_download(services: Services, db: Session, key: str) -> Document:
    """Creates the document row for a recommended PDF and starts fetching it in the background."""
    material = db.scalar(select(Material).where(Material.key == key, Material.deleted.is_(False)))
    if material is None:
        raise LookupError("That material is not in the list.")
    if key not in direct_keys(services.settings) or not material.url:
        raise ValueError("This is a web page, not a PDF. Please open it in your browser.")
    for doc in db.scalars(select(Document).where(Document.source_url == material.url, Document.deleted.is_(False))):
        if doc.processing_status != "failed":
            return doc  # already downloaded or on its way
        doc.deleted = True  # a failed earlier try: start again with a fresh row
    doc = Document(title=material.title[:300], type="recommended", source_url=material.url,
                   processing_status="downloading", status_detail="Downloading")
    db.add(doc)
    db.commit()
    processing.spawn(fetch_material, services, doc.id)
    return doc


def _polite_wait(host: str) -> None:
    with _host_lock:
        wait = _last_hit.get(host, 0.0) + HOST_PAUSE - time.monotonic()
        if wait > 0:
            time.sleep(min(wait, HOST_PAUSE))
        _last_hit[host] = time.monotonic()


def fetch_material(services: Services, document_id: str) -> None:
    """Downloads the PDF of a recommended material document, then reads it like an upload."""
    try:
        with services.session_factory() as db:
            doc = db.get(Document, document_id)
            if doc is None or doc.deleted:
                return
            ok = _download(services, db, doc)
        if ok:
            processing.process_document(services, document_id)
    except Exception:
        log.exception("download of %s crashed", document_id)
        with services.session_factory() as db:
            doc = db.get(Document, document_id)
            if doc is not None:
                processing.set_status(db, doc, "failed", "The download did not work. Tap Download to try again.")


def _fail(db: Session, doc: Document, detail: str) -> bool:
    processing.set_status(db, doc, "failed", detail)
    return False


def _download(services: Services, db: Session, doc: Document) -> bool:
    url = doc.source_url
    host = urlparse(url).netloc
    client: httpx.Client = services.http
    robots = Robots(client)
    if not robots.allowed(url):
        return _fail(db, doc, "This website does not allow automatic download. Use Open to read it in your browser.")
    _polite_wait(host)
    limit = services.settings.max_upload_mb * 1024 * 1024
    name = f"{doc.id}.pdf"
    path = uploads_dir(services.settings) / name
    size = 0
    try:
        with client.stream("GET", url, headers={"User-Agent": USER_AGENT}, timeout=60, follow_redirects=True) as r:
            if r.status_code != 200:
                return _fail(db, doc, f"The website answered with an error ({r.status_code}). Try again later.")
            with open(path, "wb") as out:
                for block in r.iter_bytes(1024 * 256):
                    if size == 0 and not block.startswith(b"%PDF-"):
                        out.close()
                        path.unlink(missing_ok=True)
                        return _fail(db, doc, "That link did not give a PDF file. Use Open to see the page.")
                    size += len(block)
                    if size > limit:
                        out.close()
                        path.unlink(missing_ok=True)
                        return _fail(db, doc, "This file is too big to keep on the server.")
                    out.write(block)
    except httpx.HTTPError as exc:
        path.unlink(missing_ok=True)
        log.warning("download %s failed: %s", host, type(exc).__name__)
        return _fail(db, doc, "Could not reach the website. Check the internet and try again.")
    try:
        doc.pages = extract.page_count(path)
    except extract.ExtractError as exc:
        path.unlink(missing_ok=True)
        return _fail(db, doc, str(exc))
    doc.file_path, doc.size_bytes = name, size
    processing.set_status(db, doc, "uploaded", "Reading the file")
    return True


def check_links(client: httpx.Client, items: list[dict]) -> list[tuple[str, str, str]]:
    """(key, url, result) for every item with a link. Polite (robots.txt, pauses). Needs internet."""
    robots = Robots(client)
    out = []
    for item in items:
        url = str(item.get("url") or "")
        if not url:
            continue
        if not robots.allowed(url):
            out.append((item["key"], url, "robots.txt says no"))
            continue
        _polite_wait(urlparse(url).netloc)
        try:
            r = client.get(url, headers={"User-Agent": USER_AGENT}, timeout=30, follow_redirects=True)
            out.append((item["key"], url, f"HTTP {r.status_code}"))
        except httpx.HTTPError as exc:
            out.append((item["key"], url, type(exc).__name__))
    return out


if __name__ == "__main__":  # pragma: no cover - needs internet
    import sys

    from app.config import get_settings

    if "--check" in sys.argv:
        with httpx.Client(follow_redirects=True) as c:
            for row in check_links(c, load_sources(sources_path(get_settings()))):
                print(*row, sep="\t")
