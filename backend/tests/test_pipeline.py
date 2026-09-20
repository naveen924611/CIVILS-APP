from sqlalchemy import select

from app.db.models import Card, NewsItem
from app.pipelines.news import run as run_mod
from app.pipelines.news.feeds import RawEntry
from tests.helpers import FakeGateway, feed_client, out, rss

LONG = "This is a long article text about policy. " * 10


def setup_feeds(settings, tmp_path):
    (tmp_path / "feeds.yaml").write_text("feeds:\n - {name: PIB, url: 'https://feed.test/rss', priority: 1}\n")


def run(env, tmp_path, monkeypatch, gateway, items, article=LONG):
    settings, factory = env
    setup_feeds(settings, tmp_path)
    monkeypatch.setattr(run_mod, "fetch_article_text", lambda client, robots, url: article)
    with factory() as db:
        stats = run_mod.run_news_pipeline(db, gateway, feed_client(rss(items)), settings, sleep=lambda s: None)
    return stats, factory


def test_pipeline_saves_items_flashcards_and_hides_low_relevance(env, tmp_path, monkeypatch):
    gw = FakeGateway([out("Repo rate held", 8, 6), out("Film awards announced", 2, 1)])
    stats, factory = run(env, tmp_path, monkeypatch, gw, [
        ("RBI keeps repo rate unchanged", "https://example.com/a"),
        ("Film awards announced in Mumbai", "https://example.com/b"),
    ])
    assert (stats.fetched, stats.selected, stats.created, stats.hidden, stats.cards) == (2, 2, 2, 1, 3)
    with factory() as db:
        items = {i.title: i for i in db.scalars(select(NewsItem))}
        assert items["Repo rate held"].hidden is False
        assert items["Film awards announced"].hidden is True
        cards = db.scalars(select(Card)).all()
        assert len(cards) == 3 and all(c.group == "Current affairs" for c in cards)
        assert cards[0].source_id == items["Repo rate held"].id


def test_low_scoring_items_make_no_flashcards(env, tmp_path, monkeypatch):
    stats, factory = run(env, tmp_path, monkeypatch, FakeGateway([out("Minor", 6, 5)]), [("Minor local story here", "https://example.com/m")])
    assert stats.created == 1 and stats.cards == 0


def test_ai_failure_and_short_text_are_skipped(env, tmp_path, monkeypatch):
    gw = FakeGateway([None])
    stats, _ = run(env, tmp_path, monkeypatch, gw, [("Story one about budget", "https://example.com/1")])
    assert (stats.created, stats.skipped) == (0, 1)
    gw2 = FakeGateway([out()])
    stats2, _ = run(env, tmp_path, monkeypatch, gw2, [("Story two about roads", "https://example.com/2")], article="short")
    # feed description (about 250 chars) is used when the page cannot be read... here article='short' is used, so skipped
    assert stats2.skipped == 1 and gw2.calls == []


def test_second_run_does_not_duplicate(env, tmp_path, monkeypatch):
    items = [("RBI keeps repo rate unchanged", "https://example.com/a")]
    run(env, tmp_path, monkeypatch, FakeGateway([out()]), items)
    gw = FakeGateway([out()])
    stats, factory = run(env, tmp_path, monkeypatch, gw, items)
    assert stats.new == 0 and stats.created == 0 and gw.calls == []
    with factory() as db:
        assert len(db.scalars(select(NewsItem)).all()) == 1


def test_prompt_follows_degrade_level():
    entry = RawEntry(url="https://x", title="T", summary="", source="PIB", lang="en", priority=1)
    _, normal = run_mod.build_prompt(entry, "text", 0)
    _, short = run_mod.build_prompt(entry, "text", 2)
    assert "120 to 180" in normal and "2 or 3 multiple-choice" in normal
    assert "60 to 100" in short and "empty list" in short
    assert "{{" not in normal


def test_recent_unbriefed_orders_by_relevance(env, tmp_path, monkeypatch):
    gw = FakeGateway([out("Low one", 5, 5), out("High one", 9, 3)])
    _, factory = run(env, tmp_path, monkeypatch, gw, [
        ("Alpha story about rivers", "https://example.com/1"), ("Beta story about tanks", "https://example.com/2"),
    ])
    with factory() as db:
        titles = [i.title for i in run_mod.recent_unbriefed(db, 10)]
        assert titles == ["High one", "Low one"]
        assert len(run_mod.recent_unbriefed(db, 1)) == 1
