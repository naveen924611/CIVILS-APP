import time
from datetime import datetime, timedelta, timezone

from app.config import get_settings
from app.db.models import Alert, Brief, Card, NewsItem
from app.db.session import get_session_factory
from app.tts.piper import audio_dir


def seed(client):
    factory = get_session_factory()
    with factory() as db:
        item = NewsItem(url="https://e.com/1", title="Repo rate held", summary="S " * 40, relevance_upsc=8,
                        relevance_appsc=5, audio_path="x.mp3", audio_seconds=30)
        hidden = NewsItem(url="https://e.com/2", title="Hidden one", summary="S", hidden=True)
        db.add_all([item, hidden])
        db.flush()
        item.audio_path = f"{item.id}.mp3"
        brief = Brief(kind="morning", scheduled_for=datetime.now(timezone.utc), status="ready", item_ids=[item.id])
        db.add(brief)
        db.add(Card(front="Q?", back="A", source_id=item.id))
        db.add(Alert(kind="brief_ready", title="Ready", payload={"brief_id": "x"}))
        db.commit()
        return item.id, brief.id


def test_endpoints_need_login(client):
    for method, path in [("get", "/briefs"), ("get", "/sync/pull"), ("get", "/audio/x.mp3"), ("get", "/usage"),
                         ("get", "/settings/briefs"), ("post", "/briefs/run")]:
        assert getattr(client, method)(path).status_code == 401, path


def test_briefs_list_and_detail(client, auth_header):
    item_id, brief_id = seed(client)
    rows = client.get("/briefs", headers=auth_header).json()
    assert [b["id"] for b in rows] == [brief_id]
    detail = client.get(f"/briefs/{brief_id}", headers=auth_header).json()
    assert detail["items"][0]["title"] == "Repo rate held"
    assert detail["items"][0]["audio_url"] == f"/audio/{item_id}.mp3"
    assert client.get("/briefs/nope", headers=auth_header).status_code == 404


def test_sync_pull_is_incremental_and_skips_hidden(client, auth_header):
    seed(client)
    first = client.get("/sync/pull", headers=auth_header).json()
    assert [i["title"] for i in first["news_items"]] == ["Repo rate held"]
    assert len(first["briefs"]) == len(first["tables"]["cards"]) == len(first["alerts"]) == 1
    assert first["more"] is False and first["server_time"].endswith("Z")
    later = client.get("/sync/pull", params={"since": first["server_time"]}, headers=auth_header).json()
    assert later["news_items"] == [] and later["alerts"] == []
    old = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    assert len(client.get("/sync/pull", params={"since": old}, headers=auth_header).json()["news_items"]) == 1


def test_sync_pull_reports_deleted_rows(client, auth_header):
    _, brief_id = seed(client)
    since = client.get("/sync/pull", headers=auth_header).json()["server_time"]
    time.sleep(0.01)
    with get_session_factory()() as db:
        db.get(Brief, brief_id).deleted = True
        db.commit()
    changed = client.get("/sync/pull", params={"since": since}, headers=auth_header).json()
    assert changed["briefs"][0]["deleted"] is True


def test_mark_alerts_read(client, auth_header):
    seed(client)
    alert_id = client.get("/sync/pull", headers=auth_header).json()["alerts"][0]["id"]
    assert client.post(f"/alerts/{alert_id}/read", headers=auth_header).status_code == 200
    assert client.post("/alerts/none/read", headers=auth_header).status_code == 404
    assert client.post("/alerts/read-all", headers=auth_header).json() == {"ok": True}
    assert client.get("/sync/pull", headers=auth_header).json()["alerts"][0]["read"] is True


def test_audio_streams_with_range_and_rejects_missing(client, auth_header):
    item_id, _ = seed(client)
    assert client.get(f"/audio/{item_id}.mp3", headers=auth_header).status_code == 404  # file not made yet
    path = audio_dir(get_settings()) / f"{item_id}.mp3"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"0123456789")
    r = client.get(f"/audio/{item_id}.mp3", headers=auth_header)
    assert r.status_code == 200 and r.content == b"0123456789" and r.headers["content-type"] == "audio/mpeg"
    part = client.get(f"/audio/{item_id}.mp3", headers={**auth_header, "Range": "bytes=2-4"})
    assert part.status_code == 206 and part.content == b"234"
    assert client.get("/audio/unknown.mp3", headers=auth_header).status_code == 404


def test_brief_settings_roundtrip_and_validation(client, auth_header):
    cur = client.get("/settings/briefs", headers=auth_header).json()
    assert [b["time"] for b in cur["briefs"]] == ["07:00", "19:00"]
    cur["briefs"][0]["time"] = "06:15"
    cur["briefs"].append({"id": "extra1", "time": "13:00", "enabled": True, "days": [0, 1, 2, 3, 4]})
    assert client.put("/settings/briefs", json=cur, headers=auth_header).status_code == 200
    assert client.get("/settings/briefs", headers=auth_header).json()["briefs"][0]["time"] == "06:15"
    bad = {"briefs": [{"id": "morning", "time": "07:00"}]}
    assert client.put("/settings/briefs", json=bad, headers=auth_header).status_code == 422


def test_run_now_starts_background_brief_or_reports_busy(client, auth_header, monkeypatch):
    service = client.app.state.brief_service
    started = []
    monkeypatch.setattr(service, "run", lambda brief_id: started.append(brief_id))
    r = client.post("/briefs/run", json={"kind": "bogus"}, headers=auth_header)
    assert r.status_code == 202
    time.sleep(0.1)
    assert started == [r.json()["brief_id"]]
    with get_session_factory()() as db:
        assert db.get(Brief, r.json()["brief_id"]).kind == "extra1"
    service._lock.acquire()
    try:
        assert client.post("/briefs/run", json={}, headers=auth_header).status_code == 409
    finally:
        service._lock.release()


def test_usage_endpoint(client, auth_header, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "g")
    data = client.get("/usage", headers=auth_header).json()
    assert data["level"] in (0, 1, 2, 3) and "resets_at" in data and "providers" in data


def test_push_brief_ready_sends_silent_message(client, monkeypatch):
    from app import main
    from app.push import fcm

    got = {}
    monkeypatch.setattr(fcm, "send_to_all", lambda db, t, b, data=None, notify=True: got.update(t=t, data=data, notify=notify))
    main._push_brief_ready(None, Alert(kind="brief_ready", title="Ready", body="3 items", payload={"brief_id": "b1", "kind": "morning"}))
    assert got == {"t": "Ready", "data": {"type": "brief_ready", "brief_id": "b1", "kind": "morning"}, "notify": False}
