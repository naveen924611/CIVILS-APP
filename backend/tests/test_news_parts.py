from datetime import datetime, timedelta, timezone

import httpx

from app.pipelines.news.dedup import dedupe, normalize_url, similar, title_tokens
from app.pipelines.news.feeds import FeedConfig, RawEntry, Robots, fetch_feed, load_feeds, parse_feed
from app.pipelines.news.select import select_entries

RSS = """<?xml version="1.0"?><rss version="2.0"><channel><title>T</title>
<item><title>RBI keeps repo rate unchanged</title><link>https://example.com/a?utm_source=x</link>
<description>&lt;p&gt;The MPC &lt;b&gt;kept&lt;/b&gt; rates.&lt;/p&gt;</description>
<pubDate>Sat, 19 Sep 2026 06:00:00 GMT</pubDate></item>
<item><title></title><link>https://example.com/no-title</link></item>
<item><title>No link</title></item>
</channel></rss>"""

CFG = FeedConfig(name="Test", url="https://example.com/feed", priority=1)


def entry(title, url, source="S", priority=2, hours_ago=1):
    return RawEntry(url=url, title=title, summary="", source=source, lang="en", priority=priority,
                    published=datetime.now(timezone.utc) - timedelta(hours=hours_ago))


def test_parse_feed_cleans_html_and_skips_broken_entries():
    items = parse_feed(RSS, CFG)
    assert len(items) == 1
    assert items[0].title == "RBI keeps repo rate unchanged"
    assert items[0].summary == "The MPC kept rates."
    assert items[0].published == datetime(2026, 9, 19, 6, 0, tzinfo=timezone.utc)


def test_load_feeds_skips_disabled(tmp_path):
    p = tmp_path / "f.yaml"
    p.write_text("feeds:\n - {name: A, url: 'https://a/x', priority: 1}\n - {name: B, url: 'https://b/x', enabled: false}\n")
    feeds = load_feeds(p)
    assert [f.name for f in feeds] == ["A"]
    assert feeds[0].priority == 1


def _client(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_robots_blocks_disallowed_paths_and_allows_when_missing():
    def handler(req):
        if req.url.host == "blocked.test":
            return httpx.Response(200, text="User-agent: *\nDisallow: /private")
        return httpx.Response(404)

    robots = Robots(_client(handler))
    assert not robots.allowed("https://blocked.test/private/x")
    assert robots.allowed("https://blocked.test/public/x")
    assert robots.allowed("https://nofile.test/anything")


def test_robots_allows_when_unreachable():
    def handler(req):
        raise httpx.ConnectError("down")

    assert Robots(_client(handler)).allowed("https://x.test/a")


def test_fetch_feed_ok_blocked_and_error():
    def handler(req):
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /nope")
        if req.url.path == "/feed":
            return httpx.Response(200, content=RSS.encode())
        return httpx.Response(500)

    c = _client(handler)
    robots = Robots(c)
    assert len(fetch_feed(c, robots, CFG)) == 1
    assert fetch_feed(c, robots, FeedConfig(name="B", url="https://example.com/nope")) == []
    assert fetch_feed(c, robots, FeedConfig(name="E", url="https://example.com/broken")) == []


def test_normalize_url_removes_tracking_and_www():
    a = normalize_url("https://www.Example.com/story/?utm_source=tw&id=5#top")
    b = normalize_url("https://example.com/story?id=5")
    assert a == b


def test_similar_titles_and_dedupe_keeps_best_priority():
    assert similar(title_tokens("RBI keeps repo rate unchanged at 5.5%"), title_tokens("RBI keeps repo rate unchanged at 5.5 per cent"))
    assert not similar(title_tokens("Cabinet approves new port"), title_tokens("Monsoon rains lash Kerala"))
    assert not similar(frozenset(), title_tokens("x"))
    entries = [
        entry("RBI keeps repo rate unchanged", "https://b.com/1", "B", priority=3),
        entry("RBI keeps repo rate unchanged today", "https://a.com/1", "A", priority=1),
        entry("Polavaram project gets funds", "https://a.com/2", "A", priority=1),
        entry("Old story", "https://a.com/old", "A", priority=1),
    ]
    kept = dedupe(entries, {normalize_url("https://a.com/old")}, [])
    assert [e.url for e in kept] == ["https://a.com/1", "https://a.com/2"]


def test_dedupe_against_earlier_titles():
    seen = [title_tokens("Cabinet approves new port at Ramayapatnam")]
    kept = dedupe([entry("Cabinet approves new port at Ramayapatnam", "https://x.com/9")], set(), seen)
    assert kept == []


def test_select_entries_is_recent_and_spreads_sources():
    now = datetime.now(timezone.utc)
    entries = [entry(f"A{i}", f"https://a/{i}", "A", 1, hours_ago=i) for i in range(5)]
    entries += [entry(f"B{i}", f"https://b/{i}", "B", 2, hours_ago=i) for i in range(5)]
    entries.append(entry("too old", "https://a/old", "A", 1, hours_ago=100))
    picked = select_entries(entries, 4, 30, now)
    assert len(picked) == 4
    assert {e.source for e in picked} == {"A", "B"}
    assert all(e.title != "too old" for e in picked)
    assert picked[0].source == "A"  # higher-priority source goes first
