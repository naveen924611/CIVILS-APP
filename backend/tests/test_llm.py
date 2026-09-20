import json

import httpx
import pytest
from pydantic import BaseModel

from app.db.models import LlmUsage
from app.llm.gateway import LlmGateway, extract_json, parse_chain
from app.llm.schemas import NewsItemOut

SUMMARY = " ".join(["word"] * 40)


class Tiny(BaseModel):
    x: int


def gemini_ok(text):
    return httpx.Response(200, json={
        "candidates": [{"content": {"parts": [{"text": text}]}}],
        "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5},
    })


def groq_ok(text):
    return httpx.Response(200, json={"choices": [{"message": {"content": text}}], "usage": {"prompt_tokens": 3, "completion_tokens": 2}})


def make(env, handler, chain=None):
    settings, factory = env
    if chain:
        settings.llm_chain = chain
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return LlmGateway(settings, factory, client, sleep=lambda s: None)


def call(gw):
    return gw.generate_json(feature="brief_item", system="s", user="u", schema=Tiny)


def test_parse_chain_and_extract_json():
    assert parse_chain("gemini:a,groq:openai/gpt-oss-20b") == [("gemini", "a"), ("groq", "openai/gpt-oss-20b")]
    assert extract_json('```json\n{"x": 1}\n```') == {"x": 1}
    assert extract_json('Sure! {"x": 2} done') == {"x": 2}
    with pytest.raises(ValueError):
        extract_json("no json here")


def test_gemini_success_records_usage(env):
    seen = {}

    def handler(req):
        seen["key"] = req.headers["x-goog-api-key"]
        seen["body"] = json.loads(req.content)
        return gemini_ok('{"x": 7}')

    gw = make(env, handler)
    assert call(gw).x == 7
    assert seen["key"] == "g-test"
    assert seen["body"]["generationConfig"]["responseMimeType"] == "application/json"
    with env[1]() as db:
        row = db.query(LlmUsage).one()
        assert (row.provider, row.feature, row.tokens_in, row.ok) == ("gemini", "brief_item", 10, True)


def test_falls_back_to_groq_when_gemini_is_rate_limited(env):
    def handler(req):
        if "googleapis" in str(req.url):
            return httpx.Response(429, text="quota exceeded per day")
        return groq_ok('{"x": 3}')

    gw = make(env, handler, "gemini:m1,groq:m2")
    assert call(gw).x == 3
    # gemini is now cooling down for hours ("per day"): the next call goes straight to groq
    calls = []

    def handler2(req):
        calls.append(str(req.url))
        return groq_ok('{"x": 4}')

    gw._client = httpx.Client(transport=httpx.MockTransport(handler2))
    assert call(gw).x == 4
    assert all("googleapis" not in u for u in calls)


def test_retries_server_errors_then_succeeds(env):
    n = {"c": 0}

    def handler(req):
        n["c"] += 1
        return httpx.Response(503) if n["c"] < 3 else gemini_ok('{"x": 1}')

    assert call(make(env, handler, "gemini:m1")).x == 1
    assert n["c"] == 3


def test_bad_json_is_retried_once_then_gives_up(env):
    n = {"c": 0}

    def handler(req):
        n["c"] += 1
        return gemini_ok("not json")

    gw = make(env, handler, "gemini:m1")
    assert call(gw) is None
    assert n["c"] == 2
    assert gw.last_error.startswith("parse")


def test_auth_error_returns_none_with_clear_message(env):
    gw = make(env, lambda req: httpx.Response(403, text="nope"), "gemini:m1")
    assert call(gw) is None
    assert gw.last_error.startswith("auth")


def test_network_error_and_blocked_answer(env):
    def boom(req):
        raise httpx.ConnectError("x")

    gw = make(env, boom, "gemini:m1")
    assert call(gw) is None
    assert gw.last_error.startswith("network")
    gw2 = make(env, lambda req: httpx.Response(200, json={"promptFeedback": {"blockReason": "SAFETY"}}), "gemini:m1")
    assert call(gw2) is None
    assert gw2.last_error.startswith("parse")


def test_no_keys_configured(env):
    settings, factory = env
    settings.gemini_api_key = settings.groq_api_key = ""
    gw = LlmGateway(settings, factory, httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(500))))
    assert call(gw) is None
    assert "No API key" in gw.last_error


def test_budget_levels(env):
    settings, factory = env
    settings.gemini_daily_requests = 10
    settings.groq_daily_requests = 10
    guard = make(env, lambda r: gemini_ok('{"x":1}')).guard

    def add(provider, n):
        with factory() as db:
            for _ in range(n):
                db.add(LlmUsage(provider=provider, model="m", feature="brief_item"))
            db.commit()

    assert guard.level() == 0
    add("gemini", 14)  # counts at most its limit: 10 of 20 = 50%
    assert guard.level() == 0
    assert guard.provider_full("gemini") is True
    add("groq", 2)  # 12 of 20 = 60%
    assert guard.level() == 1
    add("groq", 3)  # 15 of 20 = 75%
    assert guard.level() == 2
    add("groq", 3)  # 18 of 20 = 90%
    assert guard.level() == 3
    assert guard.allow("brief_item") and guard.allow("tutor_answer") and guard.allow("something_else")
    assert not guard.allow("weekly_report") and not guard.allow("mock_test")
    assert guard.resets_at() > guard.resets_at().replace(year=2000)


def test_budget_without_keys_counts_as_full(env):
    settings, factory = env
    settings.gemini_api_key = settings.groq_api_key = ""
    gw = make(env, lambda r: gemini_ok("{}"))
    assert gw.guard.limits() == {}
    assert gw.guard.fraction() == 1.0


def test_news_schema_clamps_and_validates():
    ok = NewsItemOut(title="A valid title", summary=SUMMARY, relevance_upsc=12, relevance_appsc="-3")
    assert (ok.relevance_upsc, ok.relevance_appsc) == (10, 0)
    with pytest.raises(ValueError):
        NewsItemOut(title="A valid title", summary="too short", relevance_upsc=5, relevance_appsc=5)
