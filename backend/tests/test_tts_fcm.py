import wave
from pathlib import Path

import pytest

from app.tts import piper


def test_script_for_item_reads_title_summary_facts_and_angle():
    text = piper.script_for_item("Repo rate held.", "The MPC met.", [{"q": "Who sets the repo rate?", "a": "The RBI."}], "Discuss.")
    assert text == "Repo rate held. The MPC met. Must remember. Who sets the repo rate? The RBI. Mains angle. Discuss."
    assert piper.script_for_item("T", "S", [], "") == "T. S"


def test_missing_voice_and_empty_text(env, tmp_path):
    settings, _ = env
    with pytest.raises(piper.TtsError, match="Voice file missing"):
        piper.synthesize_mp3(settings, "hello", tmp_path / "a.mp3")
    model = piper.voices_dir(settings) / f"{settings.piper_voice}.onnx"
    model.parent.mkdir(parents=True)
    model.write_bytes(b"x")
    with pytest.raises(piper.TtsError, match="Nothing to read"):
        piper.synthesize_mp3(settings, "   ", tmp_path / "a.mp3")


def test_synthesize_uses_piper_then_ffmpeg(env, tmp_path, monkeypatch):
    settings, _ = env
    model = piper.voices_dir(settings) / f"{settings.piper_voice}.onnx"
    model.parent.mkdir(parents=True)
    model.write_bytes(b"x")
    calls = []

    class Done:
        returncode = 0
        stderr = ""

    def fake_run(cmd, **kw):
        calls.append(cmd[0])
        if cmd[0] == settings.piper_bin:
            wav = Path(cmd[cmd.index("-f") + 1])
            with wave.open(str(wav), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(1000)
                w.writeframes(b"\x00\x00" * 3000)  # 3 seconds
        else:
            Path(cmd[-1]).write_bytes(b"mp3")
        return Done()

    monkeypatch.setattr(piper.subprocess, "run", fake_run)
    out = tmp_path / "x" / "a.mp3"
    assert piper.synthesize_mp3(settings, "Hello   world", out) == 3
    assert calls == ["piper", "ffmpeg"] and out.exists()


def test_piper_and_ffmpeg_failures_are_reported(env, tmp_path, monkeypatch):
    settings, _ = env
    model = piper.voices_dir(settings) / f"{settings.piper_voice}.onnx"
    model.parent.mkdir(parents=True)
    model.write_bytes(b"x")

    def missing(cmd, **kw):
        raise FileNotFoundError(cmd[0])

    monkeypatch.setattr(piper.subprocess, "run", missing)
    with pytest.raises(piper.TtsError, match="Piper could not run"):
        piper.synthesize_mp3(settings, "hi", tmp_path / "a.mp3")

    class Bad:
        returncode = 1
        stderr = "oops"

    monkeypatch.setattr(piper.subprocess, "run", lambda cmd, **kw: Bad())
    with pytest.raises(piper.TtsError, match="Piper failed"):
        piper.synthesize_mp3(settings, "hi", tmp_path / "a.mp3")

    state = {"n": 0}

    def wav_then_missing(cmd, **kw):
        state["n"] += 1
        if state["n"] == 1:
            with wave.open(cmd[cmd.index("-f") + 1], "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(1000)
                w.writeframes(b"\x00\x00" * 10)
            return type("R", (), {"returncode": 0, "stderr": ""})()
        raise FileNotFoundError("ffmpeg")

    monkeypatch.setattr(piper.subprocess, "run", wav_then_missing)
    with pytest.raises(piper.TtsError, match="ffmpeg could not run"):
        piper.synthesize_mp3(settings, "hi", tmp_path / "a.mp3")


def test_ensure_voice_downloads_once(env, monkeypatch):
    settings, _ = env
    ran = []

    class Ok:
        returncode = 0
        stderr = ""

    def fake_run(cmd, **kw):
        ran.append(cmd)
        (piper.voices_dir(settings) / "v1.onnx").write_bytes(b"x")
        return Ok()

    monkeypatch.setattr(piper.subprocess, "run", fake_run)
    assert piper.ensure_voice(settings, "v1").name == "v1.onnx"
    piper.ensure_voice(settings, "v1")
    assert len(ran) == 1

    class Fail:
        returncode = 1
        stderr = "network"

    monkeypatch.setattr(piper.subprocess, "run", lambda cmd, **kw: Fail())
    with pytest.raises(piper.TtsError):
        piper.ensure_voice(settings, "v2")


def test_fcm_silent_message_shape(env, monkeypatch):
    import sys
    import types

    from app.db.models import Device
    from app.push import fcm

    _, factory = env
    sent = []

    class Msg:
        def __init__(self, **kw):
            self.kw = kw

    fake = types.SimpleNamespace(
        Message=Msg, Notification=lambda **kw: kw, AndroidConfig=lambda **kw: kw,
        send=lambda m: sent.append(m.kw) or "id",
    )
    fake_admin = types.ModuleType("firebase_admin")
    fake_admin.messaging = fake
    monkeypatch.setitem(sys.modules, "firebase_admin", fake_admin)
    monkeypatch.setattr(fcm, "_get_app", lambda: None)
    with factory() as db:
        db.add(Device(fcm_token="t" * 30))
        db.commit()
        assert fcm.send_to_all(db, "Title", "Body", {"brief_id": "b1"}, notify=False) == 1
        assert fcm.send_to_all(db, "Title", "Body", {"brief_id": "b1"}) == 1
    silent, visible = sent
    assert silent["data"] == {"title": "Title", "body": "Body", "brief_id": "b1"} and silent["android"] == {"priority": "high"}
    assert "notification" not in silent and visible["notification"] == {"title": "Title", "body": "Body"}
