"""Finding the 11-character YouTube id in whatever the owner pasted (link, short link, embed link or the bare id)."""
import re
from urllib.parse import parse_qs, urlparse

ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com",
         "www.youtube-nocookie.com", "youtu.be", "www.youtu.be"}
PATH_KINDS = {"embed", "shorts", "live", "v", "e"}


def parse_youtube_id(text: str | None) -> str | None:
    """Returns the video id or None. Never fetches anything."""
    if not text:
        return None
    text = text.strip()
    if ID.match(text):
        return text
    if "://" not in text:
        text = "https://" + text
    try:
        url = urlparse(text)
    except ValueError:
        return None
    host = (url.hostname or "").lower()
    if host not in HOSTS:
        return None
    parts = [p for p in url.path.split("/") if p]
    candidate = None
    if host.endswith("youtu.be"):
        candidate = parts[0] if parts else None
    elif parts and parts[0] == "watch":
        candidate = (parse_qs(url.query).get("v") or [None])[0]
    elif len(parts) >= 2 and parts[0] in PATH_KINDS:
        candidate = parts[1]
    return candidate if candidate and ID.match(candidate) else None


def watch_url(youtube_id: str) -> str:
    return f"https://www.youtube.com/watch?v={youtube_id}"


def parse_iso_duration(text: str | None) -> int:
    """'PT1H2M3S' -> 3723 seconds (0 when it cannot be read)."""
    m = re.fullmatch(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?", text or "")
    if not m:
        return 0
    d, h, mi, s = (int(x or 0) for x in m.groups())
    return d * 86400 + h * 3600 + mi * 60 + s
