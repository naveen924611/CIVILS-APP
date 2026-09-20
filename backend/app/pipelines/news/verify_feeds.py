"""Tests every feed URL:  python -m app.pipelines.news.verify_feeds
Writes docs/feeds-report.md so the list in data/feeds.yaml can be trimmed to sources that really work."""
import sys
from datetime import datetime
from pathlib import Path

import httpx

from app.config import get_settings
from app.pipelines.news.feeds import USER_AGENT, Robots, load_feeds, parse_feed


def main() -> None:
    settings = get_settings()
    feeds = load_feeds(settings.feeds_file)
    rows = []
    with httpx.Client(follow_redirects=True) as client:
        robots = Robots(client)
        for cfg in feeds:
            status, count, sample = "FAIL", 0, ""
            try:
                if not robots.allowed(cfg.url):
                    status = "BLOCKED by robots.txt"
                else:
                    r = client.get(cfg.url, headers={"User-Agent": USER_AGENT}, timeout=25)
                    if r.status_code != 200:
                        status = f"HTTP {r.status_code}"
                    else:
                        entries = parse_feed(r.content, cfg)
                        count = len(entries)
                        status = "OK" if count else "EMPTY (not a feed?)"
                        sample = entries[0].title[:70] if entries else ""
            except httpx.HTTPError as exc:
                status = f"ERROR {type(exc).__name__}"
            rows.append((cfg.name, cfg.url, status, count, sample))
            print(f"{status:24} {count:3}  {cfg.name}")
    lines = [f"# Feed check ({datetime.now():%Y-%m-%d %H:%M})", "", "| Source | Status | Items | Latest title |", "|---|---|---|---|"]
    lines += [f"| {n} | {s} | {c} | {t} |" for n, _, s, c, t in rows]
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("../docs/feeds-report.md")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nReport written to {out}")


if __name__ == "__main__":
    main()
