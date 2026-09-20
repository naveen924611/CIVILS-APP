"""python -m app.tts.voices download [voice]   (default voice from PIPER_VOICE)"""
import sys

from app.config import get_settings
from app.tts.piper import ensure_voice


def main() -> None:
    settings = get_settings()
    if len(sys.argv) < 2 or sys.argv[1] != "download":
        raise SystemExit("usage: python -m app.tts.voices download [voice-name]")
    voice = sys.argv[2] if len(sys.argv) > 2 else settings.piper_voice
    print(f"Downloading {voice} ...")
    print(f"Ready: {ensure_voice(settings, voice)}")


if __name__ == "__main__":
    main()
