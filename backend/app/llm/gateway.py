"""One door for every AI call: provider fallback, retries, usage counting, strict JSON.

Order comes from LLM_CHAIN (default Gemini free -> Groq free). There is no paid provider.
Keys are read from the environment and are never logged.
"""
import base64
import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass

import httpx
from pydantic import BaseModel, ValidationError
from sqlalchemy.orm import Session

from app.config import Settings
from app.db.models import LlmUsage
from app.llm.budget import BudgetGuard

log = logging.getLogger(__name__)

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"


class LlmError(Exception):
    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind  # quota | auth | bad_request | server | network | parse


@dataclass
class Completion:
    text: str
    tokens_in: int
    tokens_out: int
    provider: str
    model: str


def parse_chain(chain: str) -> list[tuple[str, str]]:
    out = []
    for part in chain.split(","):
        part = part.strip()
        if ":" in part:
            provider, model = part.split(":", 1)
            out.append((provider.strip().lower(), model.strip()))
    return out


def extract_json(text: str) -> object:
    """Accepts plain JSON, or JSON wrapped in code fences / extra words."""
    text = text.strip()
    fence = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.S)
    if fence:
        text = fence.group(1)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start, end = text.find("{"), text.rfind("}")
        if start != -1 and end > start:
            return json.loads(text[start : end + 1])
        raise


class LlmGateway:
    def __init__(
        self,
        settings: Settings,
        session_factory: Callable[[], Session],
        client: httpx.Client | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.settings = settings
        self._session_factory = session_factory
        self._client = client or httpx.Client(timeout=90)
        self._sleep = sleep
        self._cooldown_until: dict[tuple[str, str], float] = {}
        self.guard = BudgetGuard(settings, session_factory)
        self.last_error: str = ""

    # ------------------------------------------------------------------ public

    def generate_json(
        self,
        *,
        feature: str,
        system: str,
        user: str,
        schema: type[BaseModel],
        max_output_tokens: int = 2048,
        images: list[tuple[str, bytes]] | None = None,
    ) -> BaseModel | None:
        """Returns a validated object, or None (the caller logs and skips the item).
        `images` is a list of (mime_type, bytes) for pictures the model should look at (Gemini only)."""
        prompt = user
        for attempt in range(2):
            try:
                done = self._complete(feature, system, prompt, max_output_tokens, True, images)
            except LlmError as exc:
                self.last_error = f"{exc.kind}: {exc}"
                log.warning("LLM %s failed: %s", feature, self.last_error)
                return None
            try:
                return schema.model_validate(extract_json(done.text))
            except (ValidationError, json.JSONDecodeError, ValueError) as exc:
                self.last_error = f"parse: {str(exc)[:300]}"
                log.info("LLM %s output rejected (attempt %d): %s", feature, attempt + 1, self.last_error)
                prompt = (
                    user
                    + "\n\nYour previous answer was rejected: "
                    + str(exc)[:400]
                    + "\nReturn ONLY one valid JSON object that follows the rules."
                )
        return None

    def generate_text(
        self,
        *,
        feature: str,
        system: str,
        user: str,
        max_output_tokens: int = 2048,
        images: list[tuple[str, bytes]] | None = None,
    ) -> str | None:
        """Plain text answer (tutor replies, OCR of a handwritten page, ...). None when every provider failed."""
        try:
            return self._complete(feature, system, user, max_output_tokens, False, images).text.strip()
        except LlmError as exc:
            self.last_error = f"{exc.kind}: {exc}"
            log.warning("LLM %s failed: %s", feature, self.last_error)
            return None

    def embed_texts(self, texts: list[str], *, query: bool = False) -> list[list[float]] | None:
        """One vector per text (Gemini embeddings, free tier). None means "use word search instead"."""
        if not texts or not self.settings.gemini_api_key:
            return None
        if self.guard.provider_full("gemini"):
            return None
        models = [self.settings.embed_model, self.settings.embed_fallback_model]
        for model in dict.fromkeys(m for m in models if m):
            if self._cooldown_until.get(("embed", model), 0) > time.monotonic():
                continue
            try:
                out: list[list[float]] = []
                for i in range(0, len(texts), 64):
                    out.extend(self._embed_batch(model, texts[i : i + 64], query))
                return out
            except LlmError as exc:
                self.last_error = f"embed {model}: {exc.kind}: {exc}"
                log.warning("embedding with %s failed: %s", model, self.last_error)
                self._cooldown_until[("embed", model)] = time.monotonic() + (3600 if exc.kind != "quota" else 300)
        return None

    def _embed_batch(self, model: str, batch: list[str], query: bool) -> list[list[float]]:
        requests = []
        for t in batch:
            req: dict = {
                "model": f"models/{model}",
                "content": {"parts": [{"text": t[:6000]}]},
                "outputDimensionality": self.settings.embed_dims,
            }
            if model.endswith("-001"):  # the newer model takes no task type
                req["taskType"] = "RETRIEVAL_QUERY" if query else "RETRIEVAL_DOCUMENT"
            requests.append(req)
        try:
            resp = self._client.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchEmbedContents",
                headers={"x-goog-api-key": self.settings.gemini_api_key},
                json={"requests": requests},
            )
        except httpx.HTTPError as exc:
            self._record("gemini", model, "embed", 0, 0, False)
            raise LlmError("network", type(exc).__name__) from exc
        if resp.status_code != 200:
            self._record("gemini", model, "embed", 0, 0, False)
            kind = "quota" if resp.status_code == 429 else "bad_request" if resp.status_code in (400, 404) else "server"
            raise LlmError(kind, f"HTTP {resp.status_code}: {resp.text[:200]}")
        try:
            vectors = [e["values"] for e in resp.json()["embeddings"]]
        except (KeyError, TypeError, ValueError) as exc:
            self._record("gemini", model, "embed", 0, 0, False)
            raise LlmError("parse", "unexpected embedding response") from exc
        if len(vectors) != len(batch):
            raise LlmError("parse", "embedding count mismatch")
        self._record("gemini", model, "embed", sum(len(t) for t in batch) // 4, 0, True)
        return vectors

    # ---------------------------------------------------------------- internals

    def _complete(
        self, feature: str, system: str, user: str, max_tokens: int, json_mode: bool = True,
        images: list[tuple[str, bytes]] | None = None,
    ) -> Completion:
        chain = parse_chain(self.settings.llm_chain)
        if images:  # only Gemini models here can look at pictures
            chain = [c for c in chain if c[0] == "gemini"]
        last: LlmError | None = None
        now = time.monotonic()
        candidates = [
            c for c in chain
            if self._key(c[0]) and self._cooldown_until.get(c, 0) <= now and not self.guard.provider_full(c[0])
        ]
        if not candidates:  # everything is cooling down or at our own limit: still try, briefs are protected
            candidates = [c for c in chain if self._key(c[0])]
        if not candidates:
            raise LlmError("auth", "No API key is configured (GEMINI_API_KEY / GROQ_API_KEY).")
        for provider, model in candidates:
            try:
                return self._call_with_retries(provider, model, feature, system, user, max_tokens, json_mode, images)
            except LlmError as exc:
                last = exc
                log.warning("%s/%s failed (%s), trying next", provider, model, exc.kind)
        assert last is not None
        raise last

    def _key(self, provider: str) -> str:
        return {"gemini": self.settings.gemini_api_key, "groq": self.settings.groq_api_key}.get(provider, "")

    def _call_with_retries(self, provider, model, feature, system, user, max_tokens, json_mode=True, images=None) -> Completion:
        delays = [2, 6]
        for attempt in range(len(delays) + 1):
            try:
                result = self._call_once(provider, model, system, user, max_tokens, json_mode, images)
                self._record(provider, model, feature, result.tokens_in, result.tokens_out, True)
                return result
            except LlmError as exc:
                self._record(provider, model, feature, 0, 0, False)
                if exc.kind in ("server", "network") and attempt < len(delays):
                    self._sleep(delays[attempt])
                    continue
                if exc.kind == "quota":
                    self._cooldown_until[(provider, model)] = time.monotonic() + (
                        3 * 3600 if "day" in str(exc).lower() else 90
                    )
                elif exc.kind in ("auth", "bad_request"):
                    self._cooldown_until[(provider, model)] = time.monotonic() + 3600
                raise
        raise LlmError("server", "unreachable")  # pragma: no cover

    def _call_once(self, provider, model, system, user, max_tokens, json_mode=True, images=None) -> Completion:
        try:
            if provider == "gemini":
                parts: list[dict] = [{"text": user}]
                for mime, data in images or []:
                    parts.append({"inlineData": {"mimeType": mime, "data": base64.b64encode(data).decode("ascii")}})
                config: dict = {"temperature": 0.2, "maxOutputTokens": max_tokens}
                if json_mode:
                    config["responseMimeType"] = "application/json"
                resp = self._client.post(
                    GEMINI_URL.format(model=model),
                    headers={"x-goog-api-key": self.settings.gemini_api_key},
                    json={
                        "systemInstruction": {"parts": [{"text": system}]},
                        "contents": [{"role": "user", "parts": parts}],
                        "generationConfig": config,
                    },
                )
            elif provider == "groq":
                resp = self._client.post(
                    GROQ_URL,
                    headers={"Authorization": f"Bearer {self.settings.groq_api_key}"},
                    json={
                        "model": model,
                        "messages": [
                            {"role": "system", "content": system},
                            {"role": "user", "content": user},
                        ],
                        **({"response_format": {"type": "json_object"}} if json_mode else {}),
                        "temperature": 0.2,
                        "max_tokens": max_tokens,
                    },
                )
            else:
                raise LlmError("bad_request", f"Unknown provider '{provider}'")
        except httpx.HTTPError as exc:
            raise LlmError("network", f"{type(exc).__name__}") from exc

        if resp.status_code == 200:
            return self._parse_ok(provider, model, resp.json())
        body = resp.text[:300].replace("\n", " ")
        if resp.status_code == 429:
            raise LlmError("quota", f"rate limited: {body}")
        if resp.status_code in (401, 403) or (resp.status_code == 400 and "API key" in body):
            raise LlmError("auth", f"HTTP {resp.status_code}: key rejected or not allowed")
        if resp.status_code in (400, 404):
            raise LlmError("bad_request", f"HTTP {resp.status_code}: {body}")
        raise LlmError("server", f"HTTP {resp.status_code}")

    @staticmethod
    def _parse_ok(provider: str, model: str, data: dict) -> Completion:
        try:
            if provider == "gemini":
                parts = data["candidates"][0]["content"]["parts"]
                text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
                usage = data.get("usageMetadata", {})
                return Completion(text, usage.get("promptTokenCount", 0), usage.get("candidatesTokenCount", 0), provider, model)
            text = data["choices"][0]["message"]["content"] or ""
            usage = data.get("usage", {})
            return Completion(text, usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0), provider, model)
        except (KeyError, IndexError, TypeError) as exc:
            raise LlmError("parse", "unexpected response shape (answer blocked or empty)") from exc

    def _record(self, provider, model, feature, tin, tout, ok) -> None:
        with self._session_factory() as db:
            db.add(LlmUsage(provider=provider, model=model, feature=feature, tokens_in=tin, tokens_out=tout, ok=ok))
            db.commit()
