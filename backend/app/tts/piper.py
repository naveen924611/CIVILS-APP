"""Text to speech with Piper (free, runs on the server) -> MP3, 64 kbps mono."""
import re
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

from app.config import Settings


class TtsError(Exception):
    pass


def voices_dir(settings: Settings) -> Path:
    return Path(settings.data_dir) / "voices"


def audio_dir(settings: Settings) -> Path:
    return Path(settings.data_dir) / "audio"


def ensure_voice(settings: Settings, voice: str) -> Path:
    """Downloads the voice file the first time (about 60 MB)."""
    target = voices_dir(settings)
    model = target / f"{voice}.onnx"
    if not model.exists():
        target.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run(
            [sys.executable, "-m", "piper.download_voices", "--download-dir", str(target), voice],
            capture_output=True, text=True, timeout=600,
        )
        if proc.returncode != 0 or not model.exists():
            raise TtsError(f"Could not download voice {voice}: {proc.stderr[-200:]}")
    return model


def synthesize_mp3(settings: Settings, text: str, out_mp3: Path, voice: str | None = None) -> int:
    """Returns the length in whole seconds."""
    model = voices_dir(settings) / f"{voice or settings.piper_voice}.onnx"
    if not model.exists():
        raise TtsError(f"Voice file missing: {model.name}. Run: python -m app.tts.voices download")
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        raise TtsError("Nothing to read")
    out_mp3.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = Path(tmp) / "speech.wav"
        try:
            proc = subprocess.run(
                [settings.piper_bin, "-m", str(model), "-f", str(wav_path)],
                input=text, capture_output=True, text=True, timeout=300,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
            raise TtsError(f"Piper could not run: {type(exc).__name__}") from exc
        if proc.returncode != 0 or not wav_path.exists():
            raise TtsError(f"Piper failed: {proc.stderr[-200:]}")
        with wave.open(str(wav_path), "rb") as w:
            seconds = round(w.getnframes() / float(w.getframerate()))
        try:
            enc = subprocess.run(
                [settings.ffmpeg_bin, "-y", "-loglevel", "error", "-i", str(wav_path),
                 "-ac", "1", "-b:a", "64k", str(out_mp3)],
                capture_output=True, text=True, timeout=300,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
            raise TtsError(f"ffmpeg could not run: {type(exc).__name__}") from exc
        if enc.returncode != 0 or not out_mp3.exists():
            raise TtsError(f"ffmpeg failed: {enc.stderr[-200:]}")
    return max(1, seconds)


def script_for_item(title: str, summary: str, facts: list[dict], mains_angle: str) -> str:
    """What is read aloud for one brief item."""
    parts = [f"{title.rstrip('.')}.", summary.strip()]
    if facts:
        parts.append("Must remember.")
        parts += [f"{f['q'].rstrip('?.')}? {f['a'].rstrip('.')}." for f in facts]
    if mains_angle.strip():
        parts.append(f"Mains angle. {mains_angle.strip()}")
    return " ".join(parts)
