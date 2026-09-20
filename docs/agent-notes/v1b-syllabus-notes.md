# V1b Syllabus + Notes (M4): builder notes

## What was built
Server (`backend/app/features/syllabus`, `backend/app/features/notes`) and tablet (`ui/syllabus/*`, `ui/notes/*`).

**Server, syllabus**
- Starter outlines in `data/syllabus/*.json` (4 files: UPSC prelims and mains, APPSC prelims and mains) are added at start-up as pending `syllabus_imports` (stable ids, safe to repeat, never re-added after the owner deleted them). They are marked "not checked against the official syllabus" (`verified: false`).
- `syllabus_import` job: text (or a Library document's OCR text) is split per paper, sent to the AI in chunks, merged into one tree, stored as a pending import. Retries if the AI is busy, fails kindly on a bad answer.
- Approval (`POST /syllabus/{id}/approve`): creates approved `Topic` rows (levels 0-3, paper, exam tags, position), merges same-title topics (shared topics count for both exams), applies an optional exam filter and the owner's edited tree, creates an empty `Note` row for every topic of level 1 or deeper, remembers the first importance guess. Running it twice changes nothing.
- Importance 0-10 = paper weight + starter guess + recency-weighted past-paper frequency (`Pyq.topic_ids`) + news boost. `POST /syllabus/recompute-importance`.
- `GET /syllabus/tree?exam=` nested topics with coverage %, importance and `has_note`.

**Server, notes**
- One `Note` row per topic. `note_merge` job (modes `generate`, `merge`, `fix`), see contract below. Uses only the owner's material (RAG search, or the text he captured). "Not found in your material" when there is none.
- Owner-written or owner-edited notes are never overwritten: new material goes under a `## Suggested additions` block at the end of `content_md`; the tablet lets the owner add or skip each item.
- Versions: every change keeps the old text in `note_versions` (last 30). Routes to list, view and restore.
- Cards (group = the topic's subject) and MCQs are created from notes, never duplicated. `Highlight` rows of kind `must` or `card` with a topic become a cloze flashcard (once).
- In the news: `notes/news.py` matches the last 45 days of `NewsItem` (brief items) to topics by title and note keywords (no AI), fills `NewsItem.topic_ids` and `Note.sections.in_the_news`. Runs every 3 hours (scheduler), after each note job, and on `POST /notes/news/refresh`.
- Sync hooks: a topic pushed from the tablet gets its note row; an owner edit pushed for a note keeps a version and marks it owner-edited; a note pushed without a topic is dropped (the tablet cannot create notes).

**Tablet**
- Notes screen: search (titles and note text, offline), exam filter, subject/topic tree, tabs (Notes, In the news, Questions, My doubts, Sources), Must remember box, progress box, Listen (offline TTS), Edit (Markdown), Make notes, Add to notes, Report error, Ask about this (AskDraft), suggestions panel, earlier versions.
- Syllabus map: exam filter, tree with status colour, importance marker, coverage % per paper and overall, side panel to change status / open notes / ask, import dialog (paste text or pick a Library document), imports waiting for approval.
- Import review: edit (rename, add, split, merge, move, delete, exam tags), choose exam filter, "join topics I already have", save draft, approve.
- `SyllabusSetupStep(onNext)`: shows the starter outlines with a "Use this outline" button (needs internet), Continue or Skip.

## Assumptions
1. Notes are created on the server only (the tablet's push cannot set `topic_id`), so approval and a sync hook make an empty note row per topic; the tablet edits these rows.
2. A capture with no topic (`note_merge` without `topic_id`) is assigned to the best matching topic by title/keywords; if none matches the job fails with a kind message ("Open the topic in Notes and use Add to notes").
3. Merge jobs default to 2 MCQs, generate to 5, fix to 0; budget level 1 or higher drops MCQs (spec 9).
4. Weak cards = cards whose FSRS state JSON has `lapses` of 2 or more (V2 owns the state format; if it names the field differently the count shows 0).
5. "My doubts" is a private tablet-only list (local table `local_doubts`), not synced; "Ask now" opens Ask with the text.
6. "Videos [V2]" tab is not added (owned by the videos builder); can be added as a 6th tab in `NoteDetailPane.kt`.
7. Past-year-question mapping to topics is done by the tests/PYQ builder (V4); this package only reads `Pyq.topic_ids` for importance.
8. The four starter outlines are from the earlier interrupted attempt and are NOT verified against the official syllabi (flagged in the app and in the import note).

## Contract (server <-> tablet)
Job `note_merge` payload `{topic_id?, text?, source?:{document_id,page}, mode?:"merge"|"generate"|"fix", mcq_count?}`: `merge` = `text` is new material (default), `generate` = write from Library material (`text` optional extra), `fix` = `text` is the owner's comment. Result `{note_id, added:int, summary}`.
Job `syllabus_import` payload `{exam, title, text?, document_id?, import_id?}`, result `{import_id}`.
HTTP (login required): `POST /syllabus/{id}/approve {exam_filter, merge_into_existing, tree}` -> `{created, merged, total, status}`; `PUT /syllabus/{id}/tree {tree, title}`; `GET /syllabus/tree`; `POST /syllabus/import`; `POST /syllabus/seed`; `POST /syllabus/recompute-importance`; `POST /notes/ensure/{topic_id}`; `POST /notes/generate`; `POST /notes/news/refresh`; `GET /notes/{id}/versions`; `GET /notes/{id}/versions/{v}`; `POST /notes/{id}/restore/{v}`.
`Note.sections` (server-written JSON): `overview, key_points[], must_remember[], mains_angle, keywords[], cards[{id,front,back}], mcqs[{id,question,options,answer_index,explanation}], in_the_news[{id,title,summary,url,source,published_at}]`. Tablet writes only `content_md` and `owner_edited`.
Synced tables used: `topics` (tablet writes `status`), `syllabus_imports` (read only; approval goes by HTTP), `notes`, `cards`, `mcqs`, `documents`, `jobs`.
KV keys: `syllabus.importance_prior` (server only, `{topic_id: 0-10}`).

## Manual test steps for the owner (for docs/tablet-test-checklist.md)
1. Open Notes, then "Syllabus map". You should see the outlines waiting for approval (they say they are not checked).
2. Tap "Check and approve" on one outline. Rename one line, split one, delete one. Tap Approve. Wait a moment: the syllabus map fills with topics.
3. Tap a paper: the arrow opens its subjects. Tap a small topic, change its status to "Studied": the coverage % of the paper goes up.
4. Tap "Open notes" on a topic. It says the note is empty. Tap "Make notes" (needs a book in the Library about this topic). After a minute the notes, "Must remember" box and questions appear.
5. Tap Edit, type a sentence, Save. Tap "Add to notes", paste a new paragraph, Add. Your sentence stays; new points appear under "New from your material" with Add and Skip.
6. In airplane mode press "Make notes": the line says "waiting for internet"; turn the network on and it completes.
7. Notes tab "In the news": after some briefs have arrived, items about the topic appear (matching runs every few hours).
8. "Report error" with a comment: the summary line says what was found. Your own edited text is never replaced.
9. Import your own syllabus: "Import a syllabus", paste one paper of the official syllabus, Start reading. After a minute it shows as "Check and approve".

## Known gaps
- No compiler here: Android code was checked by reading only. JVM tests were written for the pure logic (`SyllabusTreeTest`, `TopicTreeTest`, `NoteLogicTest`, `SyllabusApiTest`) but not run.
- The importance formula weights and generic-word lists are guesses; tune after real use.
- Approval and earlier versions need the network (they are HTTP calls); everything else works offline.
