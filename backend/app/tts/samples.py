"""Make short samples of several English voices so you can pick one:  python -m app.tts.samples
MP3 files appear in <data>/voice-samples/ (on the laptop: backend-data\\voice-samples\\)."""
from pathlib import Path

from app.config import get_settings
from app.tts.piper import TtsError, ensure_voice, synthesize_mp3

CANDIDATES = [
    "en_GB-alan-medium",
    "en_GB-jenny_dioco-medium",
    "en_GB-northern_english_male-medium",
    "en_US-lessac-medium",
    "en_US-ryan-medium",
    "en_US-amy-medium",
]

SAMPLE = (
    "Good morning. Here is your current affairs brief. The Union Cabinet approved a new scheme to strengthen "
    "rural connectivity. Must remember: Article 243 deals with Panchayats. Mains angle: link this to "
    "decentralised governance and the seventy-third Constitutional Amendment."
)


def main() -> None:
    settings = get_settings()
    out = Path(settings.data_dir) / "voice-samples"
    for voice in CANDIDATES:
        try:
            ensure_voice(settings, voice)
            secs = synthesize_mp3(settings, SAMPLE, out / f"{voice}.mp3", voice)
            print(f"OK    {voice}  ({secs}s)")
        except TtsError as exc:
            print(f"SKIP  {voice}: {exc}")
    print(f"\nListen to the files in: {out}")
    print("Then set PIPER_VOICE=<name> in .env")


if __name__ == "__main__":
    main()
