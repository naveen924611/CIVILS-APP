# V3: Ask, voice, tutor, Explain-back, Answer writing (M6, M9)

## What was built

Server (`backend/app/features/tutor`, `backend/app/features/answers`, prompts `tutor_*`, `answers_*`)
- `tutor_question` job: RAG over library pages and notes, numbered citations, sources list, plain "Not from your material." label when nothing matched, protected budget (`tutor_answer`), retry-safe (no duplicate ChatMessage).
  Optional `mode`: `simple | depth | quiz | evaluate` (style line added to the prompt).
- `explain_feedback` job: compares the transcript with the topic's key points (notes first, then library, then general knowledge) and writes `ExplainSession.feedback_json`.
- `answer_eval` job: Gemini vision reads the photos, or (new) the typed `text` in the payload when there are no photos; writes `AnswerSubmission.feedback_json` and `score`.
- Routes: `POST /answers/generate`, `POST /answers/{id}/images`. Weekly draft questions (Sunday 06:30 when the scheduler is on). A daily "Answer writing" plan block (`ref: "answers"`).
- Tests: `backend/tests/test_tutor.py`, `backend/tests/test_answers.py` (24 tests, package coverage about 95 percent).

Tablet (`ui/ask`, `ui/voice`, `ui/explain`, `ui/answers`)
- Ask screen: chat bubbles, tutor modes (Answer, Explain simply, In depth, Quiz me, Evaluate my answer), typed or spoken questions, "Waiting for internet - N" panel, "You can say, even offline" list, earlier chats, Listen (TTS), Save to notes (`note_merge` job), sources open the document or the note, `AskDraft` consumed into the input box (with topic chip).
- Offline: a question is first searched in the notes on the tablet ("Answered offline from your notes", with an "Ask the tutor for more" button); otherwise it becomes a `tutor_question` job. The answer appears from the job result at once, and from the synced ChatMessage later.
- FloatingMic (hidden on Ask) + offline voice grammar (`VoiceGrammar`): read brief, next, previous, repeat, pause, resume, faster, slower, what's next, start revision, mark done, read this page, save this, set timer N minutes. Anything else is a question.
- Explain-back (`ui/explain`): topic picker, record with the tablet's speech recogniser (continuous rounds), editable transcript, send, feedback view (covered, missed, needs correcting, model explanation with Listen), "Make cards from missed points" (cloze cards), Try again, history.
- Answer writing (`ui/answers`): list (to write, waiting, feedback ready, failed, score bars), get a question from the server (short/mains/essay) or write your own, timer (about 1 minute per 17 words), photos of paper (camera through `CaptureFiles`, or gallery) or typed text, offline queue with automatic upload, evaluation view.
- Settings: `AskSettingsSection(nav)` (voice, speed, listening language, headphone toggle, hands-free, wake phrase, headphone buttons).
- JVM tests: `VoiceGrammarTest`, `AskLogicTest`, `AskBubblesTest`, `ExplainAnswerLogicTest`. NOT run (no Kotlin compiler here); each expectation was checked by hand.

## Contract (tablet <-> server)

Jobs (payload -> result)
- `tutor_question`: `{conversation_id, question, topic_ids?: [], via: "text"|"voice", mode?: ""|"simple"|"depth"|"quiz"|"evaluate"}` -> `{message_id, answer, sources: [{document_id,title,page} | {note_id,topic,topic_id}]}`. The handler also writes the assistant `ChatMessage` (`role=assistant`, `job_id` = the job). The tablet writes the user `ChatMessage` with the same `job_id`.
- `explain_feedback`: `{session_id}` -> `{session_id}`; feedback is in `explain_sessions.feedback_json`: `{covered[], missed[], needs_correcting[{said,correct}], coverage{covered,total}, model_explanation, from_your_material}`. Session `status`: queued -> done | failed. Needs at least 8 words.
- `answer_eval`: `{answer_id, text?}` -> `{answer_id, score}`; feedback in `answers.feedback_json`: `{structure{intro,body,conclusion}, content_coverage, examples_data, word_limit{words,limit,comment}, presentation, strengths[], improvements[], model_outline[], transcript, readable}`; `score` 0-10. Answer `status`: draft -> queued -> done | failed.
- `note_merge` (owned by V1b) is queued by "Save to notes": `{topic_id?, text, title, mode: "merge"}`.
- Photos: `POST /answers/{id}/images?replace=true`, multipart field `files`. The tablet uploads first, then queues `answer_eval`. A 404 means the answer row has not synced yet (retried).
- `POST /answers/generate` `{kind, topic_id?}` returns the new draft row.

KV keys (all read with the defaults shown)
`voice.name` "en-IN" (reading voice as a language tag: en-IN, en-GB, en-US, te-IN), `voice.speed` 1.0, `voice.listen_language` "en-IN" (or "te-IN"; new), `voice.speak_on_headphones` false, `voice.hands_free_revision` false, `voice.wake_phrase` false, `voice.headphone_map` `{"single":"play_pause","double":"next","triple":"back15"}`.
Helper for other builders: `ui/voice/VoiceSettings.kt` (`VoiceKeys`, `VoiceSettings`).

Other cross-screen facts
- Routes used: `ask`, `explain`, `explain/{topicId}` (a topic id, or `session-<sessionId>` to open an earlier explanation), `answers`, `answer/{id}`. Other screens open Explain with `Routes.explainTopic(topicId)`.
- Voice hooks for other builders: inject `VoiceBus` and call `register { command -> handled }` in a DisposableEffect (Reader for ReadPage/SaveThis/Pause/Next, Revise for Next/MarkDone/Repeat). `VoiceGrammar.parseGrade(text)` returns 1 to 4 for "again/hard/good/easy" (hands-free revision).
- Cards from missed points: `source_type="explain"`, `source_id=<session id>`, `group=<subject name>`, `fsrs_state_json=null`, `due_at=now`, front is a fill-in-the-blank.

## Assumptions
1. "Hold to talk" is implemented as tap to talk (the recogniser stops itself after a pause). Cancelling a hold would destroy the recogniser before the result arrives.
2. `TtsSpeaker` has no voice-name API, so `voice.name` is the accent/language tag passed to it. Telugu text is read with `te-IN` automatically.
3. The headphone button map is stored and editable but not applied: the media session lives in the foundation `PlaybackService` (not mine). Wake phrase and hands-free keys are stored only; Revise (V2) may read them.
4. `voice.speak_on_headphones` reads a tutor answer aloud only on the Ask screen, only for a question that was waiting.
5. Typed answers need no photos: the text travels in the `answer_eval` job payload (small server change in `evaluation.py`).
6. Photos are uploaded as taken (server shrinks them; limit 60 MB per file). Uploads are retried every 45 s (10 s after a 404) while the app is open, by a loop started from `FloatingMicViewModel` (no WorkManager worker, so no proguard entry needed). If the app is closed offline, the upload continues at the next start.
7. Timer suggestion: about one minute per 17 words (250 words = 15 minutes).
8. "Read today's brief" opens Briefs and plays the newest ready brief through `NavEvents` (`ACTION_PLAY_BRIEF`).
9. "Mark done" marks the first plan block not done or skipped in today's `DailyPlan.completion_json`. If no plan row exists yet it says so.
10. The "set timer" countdown lives while the app process lives and speaks when finished.
11. Conversations: one current chat id kept in SharedPreferences (`ask_chat`); "New chat" starts another; "Earlier chats" groups `chat_messages` by `conversation_id`.
12. Server-only detail: a note source also carries `topic_id` so the tablet can open the note.

## Facts not verified
- Nothing compiled. Navigation `NavController.navigate(route) { ... }` is used without an extra import, as `MainActivity` does.
- `android.media.ExifInterface` is used for thumbnails (framework class, no dependency).
- Offline recognition needs the offline English pack on the tablet.

## Manual test steps for the owner (for docs/tablet-test-checklist.md)
1. Open Ask, type "What is Article 21?" and press Send. With internet on, an answer with sources appears in a minute.
2. Turn on airplane mode. Ask a question that is in your notes: it is answered at once with "Answered offline from your notes".
3. In airplane mode ask something that is not in your notes: it shows "Waiting for internet" and appears in the right panel. Turn the internet on: the answer arrives and a notification says "answers ready".
4. Tap Listen on an answer: it is read aloud. Tap Save to notes.
5. Tap the big mic on Ask and say "What is federalism?". Tap the round mic on another screen and say "What's next?", "Start revision", "Set timer 2 minutes", "Mark done", "Pause".
6. Ask > Explain it back: choose a topic, tap the big button, explain for a minute, press Get feedback. With internet on, see covered/missed/needs correcting. Press Make cards from missed points; the cards appear in Revise.
7. Ask > Answer writing: Get a question (Mains), press Start timer, write on paper, tap Take a photo for each page, press Send for feedback. In airplane mode it says photos are waiting; turn the internet on and the score arrives.
8. Answer writing > Type it here: write about 100 words, Send for feedback, see the evaluation.
9. Settings > Voice and reading: change speed, press Hear a sample.

## Known gaps
- Headphone map and wake phrase are not applied yet (see assumption 3). Hands-free revision itself belongs to V2.
- No per-answer image zoom; the evaluation shows the text the tablet read.
- `docs/job-types.md` does not exist yet; the integrator should add the rows above.
