import time
from collections import defaultdict, deque


class FailureLimiter:
    """Counts failed logins per key (IP) in a sliding window."""

    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def _trim(self, key: str, window: int) -> deque[float]:
        q = self._hits[key]
        cutoff = time.monotonic() - window
        while q and q[0] < cutoff:
            q.popleft()
        return q

    def blocked(self, key: str, max_failures: int, window: int) -> bool:
        return len(self._trim(key, window)) >= max_failures

    def record_failure(self, key: str, window: int) -> None:
        self._trim(key, window).append(time.monotonic())

    def reset(self, key: str | None = None) -> None:
        if key is None:
            self._hits.clear()
        else:
            self._hits.pop(key, None)


limiter = FailureLimiter()
