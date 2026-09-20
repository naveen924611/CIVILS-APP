"""Check your keys and model names:  python -m app.llm.check
Prints OK/FAIL per model in LLM_CHAIN. Never prints keys."""
import time

from pydantic import BaseModel

from app.config import get_settings
from app.db.base import Base
from app.db.session import get_engine, get_session_factory
from app.llm.gateway import LlmGateway, parse_chain


class Ping(BaseModel):
    ok: bool


def main() -> None:
    settings = get_settings()
    Base.metadata.create_all(get_engine())
    factory = get_session_factory()
    print(f"Keys set: gemini={bool(settings.gemini_api_key)} groq={bool(settings.groq_api_key)}")
    for provider, model in parse_chain(settings.llm_chain):
        one = settings.model_copy(update={"llm_chain": f"{provider}:{model}"})
        gw = LlmGateway(one, factory)
        t0 = time.time()
        res = gw.generate_json(
            feature="check", system="Reply with JSON only.", user='Return {"ok": true}', schema=Ping,
            max_output_tokens=64,
        )
        status = "OK  " if res else "FAIL"
        extra = "" if res else f"  ({gw.last_error})"
        print(f"{status} {provider}:{model}  {time.time() - t0:.1f}s{extra}")


if __name__ == "__main__":
    main()
