from app.api.kv import get_kv, set_kv
from app.db.models_v2 import Job, Topic, Video, VideoNote
from app.features.videos.summaries import NOTE, summary_key
from app.jobs.runner import run_pending
from tests.helpers import FakeServices, FullFakeGateway


def _run(factory, svc, payload):
    with factory() as db:
        job = Job(type="video_summary", payload_json=payload)
        db.add(job)
        db.commit()
        jid = job.id
    run_pending(svc)
    with factory() as db:
        return db.get(Job, jid)


def _video(factory, **kw):
    with factory() as db:
        topic = Topic(title="Federalism")
        db.add(topic)
        db.flush()
        row = Video(youtube_id="dQw4w9WgXcQ", topic_id=topic.id, title=kw.pop("title", "Federalism explained"),
                    channel="Study Channel", duration_seconds=600, **kw)
        db.add(row)
        db.flush()
        db.add(VideoNote(video_id=row.id, seconds=75, text="Two kinds of federal systems"))
        db.commit()
        return row.id


def test_summary_uses_title_topic_and_notes_and_is_saved(env):
    settings, factory = env
    gateway = FullFakeGateway([], texts=["- The title suggests a lesson on federalism.\n- Listen for: what is a federation?"])
    svc = FakeServices.build(settings, factory, gateway)
    vid = _video(factory)
    job = _run(factory, svc, {"video_id": vid})
    assert job.status == "done"
    assert job.result_json["video_id"] == vid and "federalism" in job.result_json["text"]
    assert job.result_json["note"] == NOTE
    feature, prompt = gateway.text_calls[0]
    assert feature == "video_summary"
    assert "Federalism explained" in prompt and "Study Channel" in prompt and "1:15 Two kinds" in prompt
    assert "about 10 minutes" in prompt
    with factory() as db:
        assert get_kv(db, summary_key(vid))["text"].startswith("- The title suggests")


def test_summary_failures(env):
    settings, factory = env
    svc = FakeServices.build(settings, factory, FullFakeGateway([]))
    assert _run(factory, svc, {"video_id": "missing"}).status == "failed"
    blank = _video(factory, title="")
    job = _run(factory, svc, {"video_id": blank})
    assert job.status == "failed" and "no title" in job.error
    ok = _video(factory)
    assert _run(factory, svc, {"video_id": ok}).status == "queued"  # AI gave nothing: tried again later


def test_summary_route(client, auth_header):
    from app.db.session import get_session_factory

    factory = get_session_factory()
    vid = _video(factory)
    empty = client.get(f"/videos/{vid}/summary", headers=auth_header).json()
    assert empty["summary"] == ""
    with factory() as db:
        set_kv(db, summary_key(vid), {"text": "Hello", "note": NOTE})
    got = client.get(f"/videos/{vid}/summary", headers=auth_header).json()
    assert got == {"video_id": vid, "summary": "Hello", "note": NOTE}
    assert client.get("/videos/nope/summary", headers=auth_header).status_code == 404


def test_video_setup_is_harmless(env):
    from app.features import videos

    settings, factory = env
    assert videos.setup(FakeServices.build(settings, factory)) is None
