from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.db.models import Card, NewsItem
from app.db.models_v2 import DocPage, Document, Highlight, Job, Mcq, Note, NoteVersion, Topic
from app.features.notes import news, service
from app.features.notes.schemas import NoteOut
from app.features.notes.text import (
    NO_MATERIAL_TEXT,
    SUGGESTED_HEADING,
    append_suggestions,
    count_suggestions,
    make_cloze,
    render_content_md,
    strip_markdown,
    unique_new,
)
from app.jobs.runner import run_pending
from app.rag.index import index_document
from tests.helpers import FakeServices, FullFakeGateway

FED_TEXT = (
    "Federalism in India: Article 246 divides powers between the Union and the States in three lists. "
    "The Sarkaria Commission reported in 1988 on Centre-State relations. Federalism is part of the basic structure."
)


def _ai(**kw):
    base = {"found": True, "overview": "Federalism divides power.", "key_points": ["Article 246 has three lists", "Sarkaria Commission reported in 1988"],
            "must_remember": ["Article 246: Union, State and Concurrent lists"], "mains_angle": "Discuss cooperative federalism.",
            "cards": [{"front": "Which article divides legislative powers?", "back": "Article 246"}],
            "mcqs": [{"question": "Sarkaria Commission reported in?", "options": ["1978", "1988", "1998", "2008"], "answer_index": 1, "explanation": "1988"}],
            "keywords": ["federalism", "Sarkaria Commission"], "summary": "Added federalism points."}
    base.update(kw)
    return NoteOut.model_validate(base)


def _world(db, gw=None, text=FED_TEXT):
    paper = Topic(title="GS Paper 2", level=0, approved=True)
    db.add(paper)
    db.flush()
    subject = Topic(title="Polity", level=1, parent_id=paper.id, paper="GS Paper 2", approved=True)
    db.add(subject)
    db.flush()
    topic = Topic(title="Federalism", level=2, parent_id=subject.id, paper="GS Paper 2", approved=True)
    db.add(topic)
    db.flush()
    if gw is not None:
        doc = Document(title="Laxmikanth Polity", pages=1)
        db.add(doc)
        db.flush()
        db.add(DocPage(document_id=doc.id, page=7, text=text))
        db.commit()
        index_document(db, gw, doc.id)
    db.commit()
    return topic, doc if gw is not None else None


def _job(factory, svc, **payload):
    with factory() as db:
        job = Job(type="note_merge", payload_json=payload)
        db.add(job)
        db.commit()
        job_id = job.id
    run_pending(svc)
    with factory() as db:
        return db.get(Job, job_id)


# ---------------------------------------------------------------- text helpers

def test_unique_new_ignores_case_punctuation_and_repeats():
    assert unique_new(["Article 21 is the right to life."], ["article 21 is the right to life", "New one", "new one!"]) == ["New one"]
    assert unique_new([], ["a", "b", "c"], limit=2) == ["a", "b"]


def test_render_and_strip_markdown():
    md = render_content_md({"overview": "Over.", "key_points": ["One", "Two"], "mains_angle": "Angle", "must_remember": ["hidden here"]})
    assert md.startswith("## Overview") and "- One\n- Two" in md and "## Mains angle" in md and "hidden here" not in md
    assert render_content_md({}) == ""
    assert strip_markdown("## Hi *there*") == "Hi there"


def test_suggestions_block_leaves_owner_text_alone_and_never_repeats():
    mine = "## My notes\n\nArticle 21 is life and liberty.\n\n## Extra\n\nMy own heading."
    once = append_suggestions(mine, ["Article 14 is equality", "Article 21 is life and liberty."])
    assert once.startswith(mine)  # the owner's text is untouched and stays first
    assert SUGGESTED_HEADING in once and "Article 14 is equality" in once
    assert once.count("Article 21 is life") == 1  # already in his text: not suggested
    twice = append_suggestions(once, ["Article 14 is equality", "Article 19 is freedom"])
    assert twice.count("Article 14 is equality") == 1 and count_suggestions(twice) == 2
    assert append_suggestions(mine, []) == mine and append_suggestions(mine, ["Article 21 is life and liberty"]) == mine
    # once the owner deletes the block, only his text remains and the block can start again
    assert twice.split(SUGGESTED_HEADING)[0].rstrip() == mine


def test_suggestions_block_in_the_middle_keeps_later_headings():
    text = f"Mine\n\n{SUGGESTED_HEADING}\n\n- old idea\n\n## Later\n\nText after."
    out = append_suggestions(text, ["new idea"])
    assert "## Later" in out and "Text after." in out and count_suggestions(out) == 2


def test_make_cloze_hides_the_first_fact():
    front, back = make_cloze("Article 21 guarantees the right to life", "Rights")
    assert front == "_____ guarantees the right to life" and back.startswith("Article 21")
    assert make_cloze("The 44th Amendment removed property from fundamental rights")[0].startswith("The _____ removed")
    assert "_____" in make_cloze("The Sarkaria Commission reported in 1988.")[0]
    front, back = make_cloze("Cooperative federalism means sharing", "Federalism")
    assert front.startswith("Recall this point about Federalism") and back == "Cooperative federalism means sharing"


def test_note_output_trims_lists_and_drops_broken_items():
    out = NoteOut.model_validate({
        "key_points": [f"p{i}" for i in range(30)] + [None, "  "],
        "cards": [{"front": "q", "back": ""}, {"front": "Good question?", "back": "yes"}, "junk"],
        "mcqs": [{"question": "ok question?", "options": ["a", "b", "c"], "answer_index": 0},
                 {"question": "fine question?", "options": ["a", "b", "c", "d"], "answer_index": 3},
                 {"question": "bad index?", "options": ["a", "b", "c", "d"], "answer_index": 9}],
        "overview": None,
    })
    assert len(out.key_points) == 15 and out.overview == ""
    assert [c.front for c in out.cards] == ["Good question?"]
    assert [q.question for q in out.mcqs] == ["fine question?"]


# ---------------------------------------------------------------- in the news

def test_news_matching_and_in_the_news_lists(env):
    settings, factory = env
    now = datetime.now(timezone.utc)
    with factory() as db:
        topic, _ = _world(db)
        note = service.ensure_note(db, topic.id)
        note.sections = {"keywords": ["Sarkaria Commission"]}
        db.add_all([
            NewsItem(url="https://n/1", title="Centre-State ties: federalism debate after GST row", summary="Federalism again in focus.",
                     published_at=now - timedelta(days=1)),
            NewsItem(url="https://n/2", title="New report revisits the Sarkaria Commission", summary="A panel cites it.", published_at=now - timedelta(days=2)),
            NewsItem(url="https://n/3", title="Cricket final tonight", summary="Sport.", published_at=now),
            NewsItem(url="https://n/4", title="Federalism old story", published_at=now - timedelta(days=200)),
            NewsItem(url="https://n/5", title="Federalism hidden story", hidden=True, published_at=now),
        ])
        db.commit()
        stats = news.refresh(db)
        assert stats["items"] == 3 and stats["matched"] == 2 and stats["notes"] == 1
        items = {i.url: i for i in db.scalars(select(NewsItem))}
        assert items["https://n/1"].topic_ids == [topic.id] and items["https://n/3"].topic_ids == []
        assert items["https://n/4"].topic_ids == []  # too old to matter
        cards = db.get(Note, note.id).sections["in_the_news"]
        assert [c["url"] for c in cards] == ["https://n/1", "https://n/2"]  # newest first
        assert set(cards[0]) == {"id", "title", "summary", "url", "source", "published_at"}
        assert news.refresh(db)["notes"] == 0  # nothing changed: the note is not rewritten
        db.get(NewsItem, items["https://n/1"].id).hidden = True
        db.commit()
        assert news.refresh(db)["notes"] == 1
        assert len(db.get(Note, note.id).sections["in_the_news"]) == 1


def test_news_ignores_generic_titles_and_broad_subjects(env):
    settings, factory = env
    with factory() as db:
        topic, _ = _world(db)
        db.add(Topic(title="Introduction", level=2, approved=True))
        db.add(Topic(title="Polity", level=2, approved=False))
        db.commit()
        terms = news.build_terms(db)
        assert [t.title_norm for t in terms] == ["federalism"]  # the subject 'Polity' has children, 'Introduction' is generic
        assert news.best_topics("An introduction to nothing", terms) == []
        assert news.best_topic_for_text(db, "notes about federalism today").id == topic.id


# ---------------------------------------------------------------- generate

def test_generate_writes_note_sources_cards_and_mcqs(env):
    settings, factory = env
    gw = FullFakeGateway([_ai()])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, doc = _world(db, gw)
        topic_id, doc_id = topic.id, doc.id
    job = _job(factory, svc, topic_id=topic_id, mode="generate")
    assert job.status == "done" and job.result_json["added"] == 3 and job.result_json["note_id"]
    with factory() as db:
        note = db.get(Note, job.result_json["note_id"])
        assert note.status == "ready" and not note.owner_edited
        assert "## Overview" in note.content_md and "- Article 246 has three lists" in note.content_md
        assert note.sections["must_remember"] == ["Article 246: Union, State and Concurrent lists"]
        assert note.sources == [{"document_id": doc_id, "title": "Laxmikanth Polity", "page": 7}]
        cards = list(db.scalars(select(Card)))
        assert len(cards) == 1 and cards[0].group == "Polity" and cards[0].source_type == "note" and cards[0].fsrs_state_json is None
        assert cards[0].due_at is not None and cards[0].topic_id == topic_id
        mcqs = list(db.scalars(select(Mcq)))
        assert len(mcqs) == 1 and mcqs[0].answer_index == 1 and mcqs[0].source_id == note.id
        assert len(note.sections["cards"]) == 1 and len(note.sections["mcqs"]) == 1
    feature, prompt = gw.calls[0]
    assert feature == "note_merge" and "Article 246" in prompt and "Laxmikanth Polity, page 7" in prompt
    assert "Number of MCQs to make: 5" in prompt


def test_generate_again_replaces_unedited_text_keeps_versions_and_never_duplicates_cards(env):
    settings, factory = env
    gw = FullFakeGateway([_ai(), _ai(key_points=["Only one point now"])])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        topic_id = topic.id
    first = _job(factory, svc, topic_id=topic_id, mode="generate")
    second = _job(factory, svc, topic_id=topic_id, mode="generate")
    with factory() as db:
        note = db.get(Note, second.result_json["note_id"])
        assert note.id == first.result_json["note_id"] and note.version == 2
        assert "Only one point now" in note.content_md and "Article 246 has three lists" not in note.content_md
        versions = list(db.scalars(select(NoteVersion).where(NoteVersion.note_id == note.id)))
        assert len(versions) == 1 and "Article 246 has three lists" in versions[0].content_md and versions[0].version == 1
        assert len(list(db.scalars(select(Card)))) == 1 and len(list(db.scalars(select(Mcq)))) == 1


def test_generate_with_no_material_says_so_and_does_not_call_the_ai(env):
    settings, factory = env
    gw = FullFakeGateway([_ai()])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db)
        topic_id = topic.id
    job = _job(factory, svc, topic_id=topic_id, mode="generate")
    assert job.status == "done" and job.result_json["added"] == 0 and job.result_json["summary"] == NO_MATERIAL_TEXT
    assert gw.calls == []
    with factory() as db:
        assert service.get_note(db, topic_id).status == "no_material"


def test_ai_saying_not_found_leaves_an_empty_note_empty(env):
    settings, factory = env
    gw = FullFakeGateway([NoteOut(found=False)])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        topic_id = topic.id
    job = _job(factory, svc, topic_id=topic_id, mode="generate")
    assert job.result_json["added"] == 0
    with factory() as db:
        note = service.get_note(db, topic_id)
        assert note.status == "no_material" and note.content_md == "" and not list(db.scalars(select(Card)))


@pytest.mark.parametrize("level,expected", [(0, 5), (1, 0), (3, 0)])
def test_budget_pressure_drops_mcqs(env, level, expected):
    settings, factory = env
    gw = FullFakeGateway([_ai()], level=level)
    assert service.mcq_count_for(gw, "generate", None) == expected
    assert service.mcq_count_for(gw, "fix", None) == 0
    assert service.mcq_count_for(FullFakeGateway([]), "merge", "junk") == 2
    assert service.mcq_count_for(FullFakeGateway([]), "generate", 99) == 8


# ---------------------------------------------------------------- merge

def test_merge_adds_only_new_points_to_an_unedited_note(env):
    settings, factory = env
    gw = FullFakeGateway([_ai(), _ai(key_points=["Article 246 has three lists", "Inter-State Council under Article 263"], must_remember=[],
                                     cards=[], mcqs=[], summary="")])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, doc = _world(db, gw)
        topic_id, doc_id = topic.id, doc.id
    _job(factory, svc, topic_id=topic_id, mode="generate")
    job = _job(factory, svc, topic_id=topic_id, text="Article 263 provides for an Inter-State Council.", source={"document_id": doc_id, "page": 9})
    assert job.status == "done" and job.result_json["added"] == 1
    assert job.result_json["summary"] == "Added 1 new point."
    with factory() as db:
        note = service.get_note(db, topic_id)
        assert note.sections["key_points"].count("Article 246 has three lists") == 1
        assert "Inter-State Council under Article 263" in note.content_md and note.version == 2
        assert {"document_id": doc_id, "title": "Laxmikanth Polity", "page": 9} in note.sources
    assert "Number of MCQs to make: 2" in gw.calls[1][1] and "Inter-State Council" in gw.calls[1][1]


def test_owner_edited_note_is_never_overwritten(env):
    settings, factory = env
    gw = FullFakeGateway([_ai(key_points=["A brand new point"], must_remember=["Fact to learn"]), _ai(key_points=["A brand new point", "Second"], must_remember=[]),
                          _ai()])
    svc = FakeServices.build(settings, factory, gw)
    mine = "## My own notes\n\nFederalism: my words, my structure."
    with factory() as db:
        topic, _ = _world(db, gw)
        note = service.ensure_note(db, topic.id)
        note.content_md, note.owner_edited, note.status = mine, True, "ready"
        db.commit()
        topic_id, note_id = topic.id, note.id
    job = _job(factory, svc, topic_id=topic_id, text="New captured fact.")
    assert job.status == "done" and job.result_json["added"] == 2
    with factory() as db:
        note = db.get(Note, note_id)
        assert note.content_md.startswith(mine)  # nothing of his changed
        assert SUGGESTED_HEADING in note.content_md and "A brand new point" in note.content_md
        assert "Must remember: Fact to learn" in note.content_md
        assert note.owner_edited is True
        first_version = note.version
    _job(factory, svc, topic_id=topic_id, text="More.")
    with factory() as db:
        note = db.get(Note, note_id)
        assert note.content_md.startswith(mine) and note.content_md.count("A brand new point") == 1 and "Second" in note.content_md
    _job(factory, svc, topic_id=topic_id, mode="generate")
    with factory() as db:
        assert db.get(Note, note_id).content_md.startswith(mine)
        assert first_version >= 1


def test_generate_on_an_edited_note_only_suggests(env):
    settings, factory = env
    gw = FullFakeGateway([_ai()])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        note = service.ensure_note(db, topic.id)
        note.content_md, note.owner_edited = "Mine only.", True
        db.commit()
        topic_id = topic.id
    job = _job(factory, svc, topic_id=topic_id, mode="generate")
    with factory() as db:
        note = service.get_note(db, topic_id)
        assert note.content_md.startswith("Mine only.") and "Article 246 has three lists" in note.content_md
    assert job.result_json["added"] == 3


def test_merge_without_a_topic_finds_one_or_fails_kindly(env):
    settings, factory = env
    gw = FullFakeGateway([_ai()])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db)
        topic_id = topic.id
    ok = _job(factory, svc, text="Notes on federalism and the Finance Commission.")
    assert ok.status == "done"
    with factory() as db:
        assert db.get(Note, ok.result_json["note_id"]).topic_id == topic_id
    lost = _job(factory, svc, text="Something about astronomy.")
    assert lost.status == "failed" and "which topic" in lost.error
    assert _job(factory, svc, topic_id=topic_id, text="").status == "failed"
    assert _job(factory, svc, topic_id="missing", text="x").status == "failed"
    assert _job(factory, svc, topic_id=topic_id, text="x", mode="dance").status == "failed"
    assert _job(factory, svc, topic_id=topic_id, mode="generate", text="").status in ("done", "queued", "failed")


# ---------------------------------------------------------------- fix

def test_fix_mode_corrects_an_unedited_note(env):
    settings, factory = env
    gw = FullFakeGateway([_ai(), _ai(key_points=["Sarkaria Commission reported in 1987"], correction="The year was wrong.", cards=[], mcqs=[])])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        topic_id = topic.id
    _job(factory, svc, topic_id=topic_id, mode="generate")
    job = _job(factory, svc, topic_id=topic_id, mode="fix", text="The Sarkaria year looks wrong")
    assert job.status == "done" and job.result_json["summary"] == "The year was wrong."
    with factory() as db:
        note = service.get_note(db, topic_id)
        assert "reported in 1987" in note.content_md and note.version == 2
    assert "The Sarkaria year looks wrong" in gw.calls[1][1] and "Number of MCQs" not in gw.calls[1][1]


def test_fix_mode_can_say_it_could_not_confirm(env):
    settings, factory = env
    gw = FullFakeGateway([_ai(), NoteOut(found=False, correction="Your material does not mention this.")])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        topic_id = topic.id
    _job(factory, svc, topic_id=topic_id, mode="generate")
    with factory() as db:
        before = service.get_note(db, topic_id).content_md
    job = _job(factory, svc, topic_id=topic_id, mode="fix", text="wrong?")
    assert job.result_json == {"note_id": job.result_json["note_id"], "added": 0, "summary": "Your material does not mention this."}
    with factory() as db:
        assert service.get_note(db, topic_id).content_md == before


def test_fix_on_an_edited_note_only_adds_a_correction_suggestion(env):
    settings, factory = env
    gw = FullFakeGateway([_ai(correction="Article 246, not 264.")])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        note = service.ensure_note(db, topic.id)
        note.content_md, note.owner_edited = "Powers are divided by Article 264.", True
        db.commit()
        topic_id = topic.id
    _job(factory, svc, topic_id=topic_id, mode="fix", text="check the article number")
    with factory() as db:
        text = service.get_note(db, topic_id).content_md
        assert text.startswith("Powers are divided by Article 264.") and "Correction: Article 246, not 264." in text


# ---------------------------------------------------------------- AI trouble

def test_ai_busy_retries_and_final_failure_does_not_leave_generating(env):
    settings, factory = env
    gw = FullFakeGateway([])
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        topic_id = topic.id
    job = _job(factory, svc, topic_id=topic_id, mode="generate")
    assert job.status == "queued"
    with factory() as db:
        assert service.get_note(db, topic_id).status == "generating"
        row = db.get(Job, job.id)
        row.result_json = {"_attempts": settings.job_max_attempts - 1}
        db.commit()
    run_pending(svc)
    with factory() as db:
        assert db.get(Job, job.id).status == "failed"
        assert service.get_note(db, topic_id).status == "failed"


def test_bad_ai_answer_fails_at_once(env):
    settings, factory = env
    gw = FullFakeGateway([])
    gw.last_error = "parse: nope"
    svc = FakeServices.build(settings, factory, gw)
    with factory() as db:
        topic, _ = _world(db, gw)
        topic_id = topic.id
    job = _job(factory, svc, topic_id=topic_id, mode="generate")
    assert job.status == "failed" and "try again later" in job.error


# ---------------------------------------------------------------- sync hooks

def _push(client, auth_header, tables):
    r = client.post("/sync/push", json={"tables": tables}, headers=auth_header)
    assert r.status_code == 200, r.text
    return r.json()


def test_push_of_an_owner_edit_keeps_a_version_and_marks_the_note(client, auth_header):
    factory = client.app.state.services.session_factory
    with factory() as db:
        topic, _ = _world(db)
        note = service.ensure_note(db, topic.id)
        note.content_md, note.status = "Generated text.", "ready"
        db.commit()
        note_id = note.id
    stamp = (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()
    result = _push(client, auth_header, {"notes": [{"id": note_id, "content_md": "Generated text.\n\nMy addition.", "owner_edited": False, "updated_at": stamp}]})
    assert result["accepted"]["notes"] == [note_id]
    with factory() as db:
        note = db.get(Note, note_id)
        assert note.owner_edited is True and note.version == 2  # an edit counts as the owner's even if the flag was not sent
        assert [v.content_md for v in db.scalars(select(NoteVersion))] == ["Generated text."]


def test_owner_typing_into_an_empty_note_makes_it_ready(client, auth_header):
    factory = client.app.state.services.session_factory
    with factory() as db:
        topic, _ = _world(db)
        note_id = service.ensure_note(db, topic.id).id
        db.commit()
    stamp = (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()
    _push(client, auth_header, {"notes": [{"id": note_id, "content_md": "First thoughts", "owner_edited": True, "updated_at": stamp}]})
    with factory() as db:
        note = db.get(Note, note_id)
        assert note.status == "ready" and note.version == 1 and not list(db.scalars(select(NoteVersion)))


def test_tablet_cannot_create_a_topicless_note_and_a_new_topic_gets_its_note(client, auth_header):
    factory = client.app.state.services.session_factory
    stamp = datetime.now(timezone.utc).isoformat()
    result = _push(client, auth_header, {
        "notes": [{"id": "n-orphan", "content_md": "x", "owner_edited": True, "updated_at": stamp}],
        "topics": [{"id": "t-new", "title": "Added on tablet", "level": 2, "approved": True, "updated_at": stamp}],
    })
    assert result["rejected"] == []
    with factory() as db:
        assert db.get(Note, "n-orphan") is None
        assert service.get_note(db, "t-new") is not None


def test_highlights_become_cards_once(client, auth_header):
    factory = client.app.state.services.session_factory
    with factory() as db:
        topic, _ = _world(db)
        topic_id = topic.id
    stamp = datetime.now(timezone.utc).isoformat()
    rows = [
        {"id": "h1", "text": "Article 246 divides powers", "kind": "must", "topic_id": topic_id, "page": 3, "updated_at": stamp},
        {"id": "h2", "text": "Just underlined", "kind": "point", "topic_id": topic_id, "updated_at": stamp},
        {"id": "h3", "text": "No topic here", "kind": "card", "updated_at": stamp},
    ]
    _push(client, auth_header, {"highlights": rows})
    _push(client, auth_header, {"highlights": [dict(rows[0], updated_at=datetime.now(timezone.utc).isoformat())]})
    with factory() as db:
        cards = list(db.scalars(select(Card)))
        assert len(cards) == 1 and cards[0].source_id == "h1" and cards[0].front.startswith("_____")
        assert cards[0].group == "Polity" and db.get(Highlight, "h2") is not None


# ---------------------------------------------------------------- routes

def test_routes_ensure_generate_versions_and_restore(client, auth_header):
    client.app.state.services.kick_jobs = lambda: None
    factory = client.app.state.services.session_factory
    with factory() as db:
        topic, _ = _world(db)
        topic_id = topic.id
    note = client.post(f"/notes/ensure/{topic_id}", headers=auth_header).json()
    assert note["topic_id"] == topic_id and note["status"] == "no_material"
    assert client.post(f"/notes/ensure/{topic_id}", headers=auth_header).json()["id"] == note["id"]
    assert client.post("/notes/ensure/none", headers=auth_header).status_code == 404

    r = client.post("/notes/generate", json={"topic_id": topic_id, "mcq_count": 3}, headers=auth_header)
    with factory() as db:
        job = db.get(Job, r.json()["job_id"])
        assert job.type == "note_merge" and job.payload_json["mode"] == "generate" and job.payload_json["mcq_count"] == 3
    assert client.post("/notes/generate", json={"topic_id": topic_id, "mode": "bad"}, headers=auth_header).status_code == 422

    with factory() as db:
        n = db.get(Note, note["id"])
        service.set_content(db, n, "Version one text")
        service.set_content(db, n, "Version two text")
        db.commit()
    listing = client.get(f"/notes/{note['id']}/versions", headers=auth_header).json()
    assert listing["current"] == 2 and [v["version"] for v in listing["versions"]] == [1]
    assert client.get(f"/notes/{note['id']}/versions/1", headers=auth_header).json()["content_md"] == "Version one text"
    assert client.get(f"/notes/{note['id']}/versions/9", headers=auth_header).status_code == 404
    restored = client.post(f"/notes/{note['id']}/restore/1", headers=auth_header).json()
    assert restored["content_md"] == "Version one text" and restored["owner_edited"] is True and restored["version"] == 3
    assert client.get("/notes/nope/versions", headers=auth_header).status_code == 404
    assert client.post("/notes/news/refresh", headers=auth_header).json()["items"] == 0


def test_setup_registers_the_news_job_only_when_the_scheduler_is_on(client):
    from app.features import notes

    svc = client.app.state.services
    svc.settings.scheduler_enabled = False
    notes.setup(svc)
    assert svc.scheduler.get_job("notes:news") is None
    notes._nightly(svc)  # must not raise


def test_versions_are_capped(env):
    settings, factory = env
    with factory() as db:
        topic, _ = _world(db)
        note = service.ensure_note(db, topic.id)
        for i in range(service.MAX_VERSIONS + 8):
            service.set_content(db, note, f"text {i}")
        db.commit()
        assert len(list(db.scalars(select(NoteVersion).where(NoteVersion.note_id == note.id)))) == service.MAX_VERSIONS
