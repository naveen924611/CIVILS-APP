"""A small in-memory rate limit for expensive routes (export, cleanup, YouTube lookups).

    @router.get("/x", dependencies=[Depends(rate_limit(5, 60, "x"))])

It counts calls per client address in a sliding window and answers 429 with a Retry-After header when the limit is
passed. The login has its own limiter (app/auth/ratelimit.py); this one never touches auth code.
"""
import threading
import time
from collections import defaultdict, deque
from collections.abc import Callable

from fastapi import HTTPException, Request

_lock = threading.Lock()
_hits: dict[str, deque[float]] = defaultdict(deque)


def reset_rate_limits() -> None:
    """Used by tests."""
    with _lock:
        _hits.clear()


def check_rate(key: str, max_calls: int, per_seconds: int, now: float | None = None) -> int:
    """Records one call. Returns 0 when allowed, otherwise the seconds to wait."""
    moment = time.monotonic() if now is None else now
    with _lock:
        q = _hits[key]
        cutoff = moment - per_seconds
        while q and q[0] <= cutoff:
            q.popleft()
        if len(q) >= max_calls:
            return max(1, int(q[0] + per_seconds - moment) + 1)
        q.append(moment)
        return 0


def rate_limit(max_calls: int, per_seconds: int, name: str) -> Callable[[Request], None]:
    """Dependency factory: at most `max_calls` per `per_seconds` for each client address."""

    def dependency(request: Request) -> None:
        who = request.client.host if request.client else "unknown"
        wait = check_rate(f"{name}:{who}", max_calls, per_seconds)
        if wait:
            raise HTTPException(429, "Too many requests. Please wait a moment and try again.",
                                headers={"Retry-After": str(wait)})

    return dependency
