"""Shared fakes for the news / brief tests."""
from datetime import datetime, timedelta, timezone

import httpx

from app.llm.schemas import NewsItemOut

SUMMARY = " ".join(["Important"] * 45)


def rss(items: list[tuple[str, str]]) -> bytes:
    stamp = (datetime.now(timezone.utc) - timedelta(hours=2)).strftime("%a, %d %b %Y %H:%M:%S GMT")
    body = "".join(
        f"<item><title>{t}</title><link>{u}</link><description>{'Long enough description. ' * 10}</description>"
        f"<pubDate>{stamp}</pubDate></item>"
        for t, u in items
    )
    return f'<?xml version="1.0"?><rss version="2.0"><channel><title>x</title>{body}</channel></rss>'.encode()


def out(title="Repo rate held", upsc=8, appsc=6, facts=3) -> NewsItemOut:
    return NewsItemOut.model_validate({
        "title": title, "summary": SUMMARY, "relevance_upsc": upsc, "relevance_appsc": appsc,
        "papers": ["GS3"], "mains_angle": "Discuss monetary policy.",
        "prelims_facts": [{"q": f"Question {i}?", "a": f"Answer {i}"} for i in range(facts)],
        "keywords": ["RBI"], "mcqs": [],
    })


class FakeGuard:
    def __init__(self, level=0):
        self._level = level

    def level(self):
        return self._level


class FakeGateway:
    """Returns the next prepared answer for each call (None = the AI failed)."""

    def __init__(self, answers, level=0):
        self.answers = list(answers)
        self.calls = []
        self.guard = FakeGuard(level)
        self.last_error = "boom"

    def generate_json(self, *, feature, system, user, schema, max_output_tokens=2048):
        self.calls.append((feature, user))
        return self.answers.pop(0) if self.answers else None


def feed_client(content: bytes) -> httpx.Client:
    def handler(req):
        if req.url.path == "/robots.txt":
            return httpx.Response(404)
        return httpx.Response(200, content=content)

    return httpx.Client(transport=httpx.MockTransport(handler))
