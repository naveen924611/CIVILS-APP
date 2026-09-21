import io
import os
import time
import zipfile

import pytest

from app.config import get_settings
from app.db.models import Card, NewsItem
from app.db.models_v2 import Document, Job, Note, Sheet, Topic
from app.db.session import get_session_factory
from app.features.storage.backups import list_backups, nightly_backup, run_backup
from app.features.storage.export import safe_file_name
from app.features.storage.ratelimit import check_rate, reset_rate_limits
from app.features.storage.usage import compute_usage, database_files
from app.tts.piper import audio_dir
from tests.helpers import FakeServices


@pytest.fixture(autouse=True)
def _fresh_limits():
    reset_rate_limits()
    yield
    reset_rate_limits()


def _old(path, days):
    t = time.time() - days * 86400
    os.utime(path, (t, t))


def test_storage_routes_need_login(client):
    for method, path in [("get", "/storage/usage"), ("post", "/storage/cleanup"), ("get", "/storage/export"),
                         ("get", "/storage/backups"), ("post", "/storage/backups/run")]:
        assert getattr(client, method)(path).status_code == 401, path


def test_usage_counts_bytes_by_type(client, auth_header, tmp_path):
    settings = get_settings()
    audio = audio_dir(settings)
    audio.mkdir(parents=True)
    (audio / "a.mp3").write_bytes(b"x" * 1000)
    up = tmp_path / "data" / "uploads"
    up.mkdir(parents=True)
    (up / "b.pdf").write_bytes(b"y" * 500)
    with get_session_factory()() as db:
        db.add(Job(type="tutor_question", payload_json={"question": "What?"}, status="queued"))
        db.add(Job(type="tutor_question", payload_json={}, status="done"))
        db.commit()
    body = client.get("/storage/usage", headers=auth_header).json()
    assert body["audio_bytes"] == 1000 and body["audio_files"] == 1
    assert body["documents_bytes"] == 500 and body["document_files"] == 1
    assert body["study_data_bytes"] > 0
    assert body["waiting_jobs"] == 1 and body["waiting_bytes"] > 0
    assert body["total_bytes"] >= 1500 + body["study_data_bytes"]
    assert body["disk_total_bytes"] > 0


def test_usage_with_no_folders(env):
    settings, factory = env
    with factory() as db:
        out = compute_usage(settings, db)
    assert out["audio_bytes"] == 0 and out["documents_bytes"] == 0 and out["waiting_jobs"] == 0


def test_cleanup_removes_old_audio_and_clears_references(client, auth_header):
    settings = get_settings()
    audio = audio_dir(settings)
    audio.mkdir(parents=True)
    old, fresh = audio / "old.mp3", audio / "fresh.mp3"
    old.write_bytes(b"1" * 100)
    fresh.write_bytes(b"2" * 100)
    _old(old, 90)
    with get_session_factory()() as db:
        n_old = NewsItem(url="https://e/1", title="Old", audio_path="old.mp3", audio_seconds=30)
        n_new = NewsItem(url="https://e/2", title="New", audio_path="fresh.mp3", audio_seconds=30)
        sheet = Sheet(topic_id="t", audio_path="old.mp3", audio_seconds=20)
        db.add_all([n_old, n_new, sheet])
        db.commit()
        old_id, new_id, sheet_id = n_old.id, n_new.id, sheet.id
    r = client.post("/storage/cleanup", json={"audio_older_than_days": 60}, headers=auth_header)
    assert r.status_code == 200
    body = r.json()
    assert body["deleted_files"] == 1 and body["freed_bytes"] == 100 and body["cleared_references"] == 2
    assert not old.exists() and fresh.exists()
    with get_session_factory()() as db:
        assert db.get(NewsItem, old_id).audio_path is None
        assert db.get(NewsItem, old_id).audio_seconds is None
        assert db.get(NewsItem, new_id).audio_path == "fresh.mp3"
        assert db.get(Sheet, sheet_id).audio_path is None and db.get(Sheet, sheet_id).audio_seconds == 0


def test_cleanup_default_body_and_validation(client, auth_header):
    assert client.post("/storage/cleanup", headers=auth_header).json()["older_than_days"] == 60
    assert client.post("/storage/cleanup", json={"audio_older_than_days": 0}, headers=auth_header).status_code == 422


def test_cleanup_without_audio_folder(client, auth_header):
    body = client.post("/storage/cleanup", json={}, headers=auth_header).json()
    assert body["deleted_files"] == 0


def test_export_zip_has_tables_notes_and_document_list(client, auth_header):
    with get_session_factory()() as db:
        subject = Topic(title="Polity", level=1, paper="GS2")
        db.add(subject)
        db.flush()
        topic = Topic(title="Federalism: Basics", level=2, parent_id=subject.id)
        gone = Topic(title="Deleted", level=2, deleted=True)
        db.add_all([topic, gone])
        db.flush()
        db.add(Note(topic_id=topic.id, content_md="- Article 1 says India is a Union of States"))
        db.add(Note(topic_id=gone.id, content_md=""))
        db.add(Card(front="Q?", back="A"))
        db.add(Document(title="NCERT Polity", file_path="uploads/abc-ncert.pdf", pages=12))
        db.commit()
    r = client.get("/storage/export", headers=auth_header)
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/zip"
    assert "civils-companion-" in r.headers["content-disposition"]
    zf = zipfile.ZipFile(io.BytesIO(r.content))
    names = zf.namelist()
    assert "README.txt" in names and "documents.json" in names and "settings.json" in names
    assert "tables/cards.json" in names and "tables/topics.json" in names
    md = [n for n in names if n.startswith("notes/")]
    assert md == ["notes/Polity - Federalism_ Basics.md"]
    assert "Union of States" in zf.read(md[0]).decode()
    import json

    topics = json.loads(zf.read("tables/topics.json"))
    assert {t["title"] for t in topics} == {"Polity", "Federalism: Basics"}  # soft-deleted rows are left out
    assert json.loads(zf.read("documents.json"))[0]["file"] == "abc-ncert.pdf"


def test_export_is_rate_limited(client, auth_header):
    codes = [client.get("/storage/export", headers=auth_header).status_code for _ in range(5)]
    assert codes[:4] == [200] * 4 and codes[4] == 429


def test_safe_file_name():
    assert safe_file_name("a/b\\c:d") == "a_b_c_d"
    assert safe_file_name("   ") == "note"
    assert len(safe_file_name("x" * 300)) == 80


def test_backup_now_list_and_keep_seven(client, auth_header, tmp_path):
    settings = get_settings()
    folder = tmp_path / "data" / "backups"
    folder.mkdir(parents=True)
    for i in range(9):
        (folder / f"civils-backup-2020010{i}-000000.tar.gz").write_bytes(b"x")
    (folder / "notes.txt").write_text("not a backup")
    r = client.post("/storage/backups/run", headers=auth_header)
    assert r.status_code == 200 and r.json()["name"].startswith("civils-backup-")
    listing = client.get("/storage/backups", headers=auth_header).json()
    assert listing["keep"] == 7 and len(listing["backups"]) == 7
    assert listing["backups"][0]["name"] == r.json()["name"]
    assert len(list_backups(settings)) == 7


def test_nightly_backup_function_and_registration(env, tmp_path):
    settings, factory = env
    with factory() as db:
        db.add(Card(front="Q", back="A"))
        db.commit()
    svc = FakeServices.build(settings, factory)
    nightly_backup(svc)
    assert len(list_backups(settings)) == 1
    # scheduler off (tests): no job. On: one cron job.
    from app.features import storage

    storage.setup(svc)
    assert svc.scheduler.get_job("storage:backup") is None
    on = settings.model_copy(update={"scheduler_enabled": True})
    svc2 = FakeServices.build(on, factory)
    storage.setup(svc2)
    assert svc2.scheduler.get_job("storage:backup") is not None


def test_backup_is_skipped_for_other_databases(env):
    settings, _ = env
    other = settings.model_copy(update={"database_url": "postgresql://u@h/db"})
    assert run_backup(other) is None
    assert database_files(other) == []
    nightly_backup(type("S", (), {"settings": other})())  # logs and returns


def test_backup_now_conflict_for_other_databases(client, auth_header, monkeypatch):
    monkeypatch.setattr("app.features.storage.run_backup", lambda settings: None)
    assert client.post("/storage/backups/run", headers=auth_header).status_code == 409


def test_rate_limit_window():
    assert check_rate("k", 2, 60, now=100.0) == 0
    assert check_rate("k", 2, 60, now=101.0) == 0
    wait = check_rate("k", 2, 60, now=102.0)
    assert 55 <= wait <= 60
    assert check_rate("k", 2, 60, now=161.5) == 0  # the first call left the window
    assert check_rate("other", 2, 60, now=102.0) == 0


def test_usage_reports_the_owners_limit(client, auth_header):
    assert client.get("/storage/usage", headers=auth_header).json()["limit_bytes"] == 20 * 1024**3
    client.put("/kv/storage.limit_gb", json={"value": 5}, headers=auth_header)
    assert client.get("/storage/usage", headers=auth_header).json()["limit_bytes"] == 5 * 1024**3
    client.put("/kv/storage.limit_gb", json={"value": "lots"}, headers=auth_header)  # nonsense falls back to 20 GB
    assert client.get("/storage/usage", headers=auth_header).json()["limit_bytes"] == 20 * 1024**3


def test_backup_can_be_restored_into_a_fresh_folder(env, tmp_path):
    """The tested restore of milestone M7: back up, then restore into an empty folder and read the data back."""
    import sqlite3

    from app.tools.backup import restore_backup

    settings, factory = env
    with factory() as db:
        db.add(Card(front="Restore me", back="A"))
        db.commit()
    (tmp_path / "data" / "audio").mkdir(parents=True, exist_ok=True)
    (tmp_path / "data" / "audio" / "a.mp3").write_bytes(b"sound")
    path = run_backup(settings)
    assert path is not None and path.is_file()
    target = tmp_path / "restored"
    restore_backup(path, target)
    con = sqlite3.connect(target / "civils.db")
    try:
        assert con.execute("select front from cards").fetchall() == [("Restore me",)]
    finally:
        con.close()
    assert (target / "audio" / "a.mp3").read_bytes() == b"sound"
    with pytest.raises(SystemExit):  # never overwrites without --force
        restore_backup(path, target)
