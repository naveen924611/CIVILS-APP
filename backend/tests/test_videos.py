import httpx
import pytest

from app.db.models_v2 import Topic, Video
from app.db.session import get_session_factory
from app.features.storage.ratelimit import reset_rate_limits
from app.features.videos import youtube
from app.features.videos.ids import parse_iso_duration, parse_youtube_id, watch_url

VID = "dQw4w9WgXcQ"


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.delenv("YOUTUBE_API_KEY", raising=False)
    reset_rate_limits()
    youtube.clear_cache()
    yield
    reset_rate_limits()
    youtube.clear_cache()


def _mock(client, handler):
    calls = []

    def wrapped(request):
        calls.append(request)
        return handler(request)

    client.app.state.services.http = httpx.Client(transport=httpx.MockTransport(wrapped))
    return calls


def oembed_ok(request):
    return httpx.Response(200, json={"title": "Federalism explained", "author_name": "Study Channel",
                                     "thumbnail_url": "https://i.ytimg.com/vi/x/hq.jpg"})


@pytest.mark.parametrize("text", [
    VID,
    f"https://www.youtube.com/watch?v={VID}",
    f"https://www.youtube.com/watch?v={VID}&t=42s&list=PL1",
    f"http://youtube.com/watch?feature=share&v={VID}",
    f"https://m.youtube.com/watch?v={VID}",
    f"https://youtu.be/{VID}?si=abc",
    f"youtu.be/{VID}",
    f"https://www.youtube.com/embed/{VID}",
    f"https://www.youtube-nocookie.com/embed/{VID}",
    f"https://www.youtube.com/shorts/{VID}",
    f"https://www.youtube.com/live/{VID}?feature=share",
    f"  https://music.youtube.com/watch?v={VID}  ",
])
def test_parse_youtube_id_accepts(text):
    assert parse_youtube_id(text) == VID


@pytest.mark.parametrize("text", [
    None, "", "hello", "short", "https://example.com/watch?v=" + VID, "https://www.youtube.com/watch?v=tooshort",
    "https://www.youtube.com/", "https://www.youtube.com/channel/UC12345678901", "https://youtu.be/",
    "https://evil.com/youtu.be/" + VID, "not a url at all!!", "ftp://[bad",
])
def test_parse_youtube_id_rejects(text):
    assert parse_youtube_id(text) is None


def test_parse_iso_duration_and_watch_url():
    assert parse_iso_duration("PT1H2M3S") == 3723
    assert parse_iso_duration("PT45S") == 45
    assert parse_iso_duration("P1DT1H") == 90000
    assert parse_iso_duration("nonsense") == 0 and parse_iso_duration(None) == 0
    assert watch_url(VID) == f"https://www.youtube.com/watch?v={VID}"


def test_videos_routes_need_login(client):
    assert client.post("/videos/resolve", json={"url_or_id": VID}).status_code == 401
    assert client.get("/videos/search?q=x").status_code == 401


def test_resolve_creates_row_and_dedupes(client, auth_header):
    calls = _mock(client, oembed_ok)
    with get_session_factory()() as db:
        topic = Topic(title="Federalism")
        db.add(topic)
        db.commit()
        topic_id = topic.id
    r = client.post("/videos/resolve", json={"url_or_id": f"https://youtu.be/{VID}", "topic_id": topic_id},
                    headers=auth_header)
    assert r.status_code == 200
    body = r.json()
    assert body["created"] is True and body["verified"] is True
    v = body["video"]
    assert v["youtube_id"] == VID and v["title"] == "Federalism explained" and v["channel"] == "Study Channel"
    assert v["embeddable"] is True and v["topic_id"] == topic_id
    assert body["thumbnail_url"].startswith("https://i.ytimg.com")
    assert str(calls[0].url).startswith("https://www.youtube.com/oembed")
    again = client.post("/videos/resolve", json={"url_or_id": VID, "topic_id": topic_id}, headers=auth_header).json()
    assert again["created"] is False and again["video"]["id"] == v["id"]
    # same video with no topic is a separate row
    other = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert other["created"] is True and other["video"]["id"] != v["id"]
    with get_session_factory()() as db:
        assert db.query(Video).count() == 2


def test_resolve_revives_a_deleted_row(client, auth_header):
    _mock(client, oembed_ok)
    first = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()["video"]
    with get_session_factory()() as db:
        db.get(Video, first["id"]).deleted = True
        db.commit()
    again = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert again["created"] is False and again["video"]["deleted"] is False


def test_resolve_embedding_disabled_is_not_embeddable(client, auth_header):
    _mock(client, lambda r: httpx.Response(401))
    body = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert body["video"]["embeddable"] is False and body["verified"] is True
    assert body["video"]["title"] == f"YouTube video {VID}"
    assert "inside other apps" in body["reason"]


def test_resolve_missing_video_is_404(client, auth_header):
    _mock(client, lambda r: httpx.Response(404))
    r = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header)
    assert r.status_code == 404


def test_resolve_bad_input_and_bad_topic(client, auth_header):
    assert client.post("/videos/resolve", json={"url_or_id": "nope"}, headers=auth_header).status_code == 400
    _mock(client, oembed_ok)
    r = client.post("/videos/resolve", json={"url_or_id": VID, "topic_id": "missing"}, headers=auth_header)
    assert r.status_code == 404


def test_resolve_when_youtube_is_unreachable_is_conservative(client, auth_header):
    def boom(request):
        raise httpx.ConnectError("offline")

    _mock(client, boom)
    body = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert body["video"]["embeddable"] is False and body["verified"] is False and "reach" in body["reason"]
    # a later good lookup fills in the details
    _mock(client, oembed_ok)
    fixed = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert fixed["created"] is False and fixed["video"]["title"] == "Federalism explained"
    assert fixed["video"]["embeddable"] is True
    # and a failed lookup afterwards does not wipe it
    _mock(client, boom)
    kept = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert kept["video"]["title"] == "Federalism explained" and kept["video"]["embeddable"] is True


@pytest.mark.parametrize("response", [
    httpx.Response(200, content=b"<html>"), httpx.Response(200, json={}), httpx.Response(500),
])
def test_oembed_odd_answers_mean_not_embeddable(response):
    http = httpx.Client(transport=httpx.MockTransport(lambda r: response))
    out = youtube.oembed(http, VID)
    assert out.embeddable is False and out.found is True


def test_resolve_uses_api_for_duration_and_stricter_embed_flag(client, auth_header, monkeypatch):
    monkeypatch.setenv("YOUTUBE_API_KEY", "secret-key-123")

    def handler(request):
        if request.url.host == "www.youtube.com":
            return oembed_ok(request)
        assert "secret-key-123" not in str(request.url)  # the key goes in a header, never the URL
        assert request.headers["x-goog-api-key"] == "secret-key-123"
        return httpx.Response(200, json={"items": [{
            "contentDetails": {"duration": "PT1H2M3S"}, "status": {"embeddable": False},
            "snippet": {"title": "T", "channelTitle": "C"}}]})

    _mock(client, handler)
    body = client.post("/videos/resolve", json={"url_or_id": VID}, headers=auth_header).json()
    assert body["video"]["duration_seconds"] == 3723
    assert body["video"]["embeddable"] is False  # oEmbed said yes, the API said no: the stricter answer wins


def test_api_details_failures_are_quiet(monkeypatch):
    monkeypatch.setenv("YOUTUBE_API_KEY", "k")
    for response in (httpx.Response(403), httpx.Response(200, json={"items": []}), httpx.Response(200, content=b"x")):
        http = httpx.Client(transport=httpx.MockTransport(lambda r, resp=response: resp))
        assert youtube.api_details(http, VID) is None

    def boom(request):
        raise httpx.ReadTimeout("slow")

    assert youtube.api_details(httpx.Client(transport=httpx.MockTransport(boom)), VID) is None
    monkeypatch.delenv("YOUTUBE_API_KEY")
    assert youtube.api_details(httpx.Client(transport=httpx.MockTransport(boom)), VID) is None


def test_search_without_key_returns_reason(client, auth_header):
    calls = _mock(client, oembed_ok)
    body = client.get("/videos/search?q=federalism", headers=auth_header).json()
    assert body["results"] == [] and body["reason"] == "no_api_key" and "paste a link" in body["message"]
    assert calls == []


def test_search_needs_a_query_or_topic(client, auth_header):
    assert client.get("/videos/search", headers=auth_header).json()["reason"] == "no_query"


def _search_handler(counter):
    def handler(request):
        counter.append(request)
        return httpx.Response(200, json={"items": [
            {"id": {"videoId": VID}, "snippet": {"title": "Fed", "channelTitle": "Ch", "publishedAt": "2025-01-01T00:00:00Z",
                                                 "thumbnails": {"medium": {"url": "https://i/x.jpg"}}}},
            {"id": {}, "snippet": {}},
        ]})

    return handler


def test_search_with_key_uses_topic_title_and_caches(client, auth_header, monkeypatch):
    monkeypatch.setenv("YOUTUBE_API_KEY", "k-abc")
    with get_session_factory()() as db:
        subject = Topic(title="Polity")
        db.add(subject)
        db.flush()
        topic = Topic(title="Federalism", parent_id=subject.id)
        db.add(topic)
        db.commit()
        topic_id = topic.id
    seen = []
    _mock(client, _search_handler(seen))
    body = client.get(f"/videos/search?topic_id={topic_id}", headers=auth_header).json()
    assert body["query"] == "Federalism Polity explained" and body["cached"] is False
    assert body["results"] == [{"youtube_id": VID, "title": "Fed", "channel": "Ch", "thumbnail_url": "https://i/x.jpg",
                                "published_at": "2025-01-01T00:00:00Z", "watch_url": watch_url(VID), "embeddable": True}]
    req = seen[0]
    assert req.url.params["videoEmbeddable"] == "true" and "k-abc" not in str(req.url)
    again = client.get(f"/videos/search?topic_id={topic_id}", headers=auth_header).json()
    assert again["cached"] is True and len(seen) == 1  # served from the one-day cache


def test_search_cache_expires_after_a_day(client, auth_header, monkeypatch):
    monkeypatch.setenv("YOUTUBE_API_KEY", "k")
    seen = []
    _mock(client, _search_handler(seen))
    clock = [1000.0]
    monkeypatch.setattr(youtube, "_now", lambda: clock[0])
    client.get("/videos/search?q=Polity", headers=auth_header)
    clock[0] += 3600
    client.get("/videos/search?q=polity", headers=auth_header)
    assert len(seen) == 1
    clock[0] += 24 * 3600
    client.get("/videos/search?q=Polity", headers=auth_header)
    assert len(seen) == 2


def test_search_failure_gives_empty_list_with_reason(client, auth_header, monkeypatch):
    monkeypatch.setenv("YOUTUBE_API_KEY", "k")
    _mock(client, lambda r: httpx.Response(403, json={"error": "quota"}))
    body = client.get("/videos/search?q=x", headers=auth_header).json()
    assert body["results"] == [] and body["reason"] == "search_failed" and "free daily limit" in body["message"]

    def boom(request):
        raise httpx.ConnectError("x")

    _mock(client, boom)
    assert client.get("/videos/search?q=y", headers=auth_header).json()["reason"] == "search_failed"
    _mock(client, lambda r: httpx.Response(200, content=b"not json"))
    assert client.get("/videos/search?q=z", headers=auth_header).json()["reason"] == "search_failed"


def test_search_cache_is_bounded(monkeypatch):
    monkeypatch.setenv("YOUTUBE_API_KEY", "k")
    http = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"items": []})))
    for i in range(youtube.CACHE_MAX + 5):
        youtube.search(http, f"query {i}")
    assert len(youtube._cache) == youtube.CACHE_MAX


def test_resolve_is_rate_limited(client, auth_header):
    _mock(client, oembed_ok)
    codes = [client.post("/videos/resolve", json={"url_or_id": "bad"}, headers=auth_header).status_code
             for _ in range(31)]
    assert codes[-1] == 429 and codes[0] == 400
