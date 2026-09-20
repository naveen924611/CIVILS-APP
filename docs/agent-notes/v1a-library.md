# V1a Library, Reader, Capture (M3): notes

## What was built

**Server** (`backend/app/features/library/`)
- `__init__.py` (router + `setup`), `api.py` (routes), `uploads.py` (saving PDFs, photos, camera scans), `extract.py` (pypdf text, page pictures, photo clean-up),
  `processing.py` (extract, queue OCR, index into RAG, statuses), `ocr.py` (job `ocr_page`, Gemini vision), `materials.py` (recommended list + polite download),
  `hooks.py` (sync hooks + background re-indexing). Prompt `app/llm/prompts/library_ocr.{system,user}.md`. Data `data/sources.yaml`. Tests `backend/tests/test_library.py` (28 tests, about 89 percent of the package).
**Tablet** (`android/.../`)
- `ui/library/`: Library screen (my uploads by subject, search, Recommended tab, optional books, library-day panel), `LibraryViewModel`, `LibraryRepository`
  (uploads, downloads, offline photo queue), `LibraryApi` (+ Hilt module), `ScanUploadWorker`, `LibrarySettingsSection`, `LibraryRoutes`.
- `ui/read/`: Reader (outline, big text, sentence highlight, tap to read from here, TTS bar with speed and sleep timer, highlights, notes, Ask, original page view), Read home.
- `ui/capture/`: Capture (camera or gallery photo, on-device ML Kit reading, spotted facts, duplicate check against notes, queued AI merge, save page).
- `reader/`: plain Kotlin logic with JVM tests (`SentenceSplitter`, `FactSpotter`, `ReadingPosition`, `LibraryLogic`) in `app/src/test/.../reader/`.

## Assumptions (nobody could be asked)
1. Documents are created only by the server (upload/scan endpoints); the tablet never invents a Document row. Deleting on the tablet works (soft delete, a sync hook removes files and chunks).
2. Camera uses the system camera through `TakePicture` + `CaptureFiles` (as the build guide 9.5 says), not CameraX. CAMERA permission is asked first (needed because it is declared in the manifest).
3. Images sent to the server are one document per upload call (`type=image`) or one scan session (`type=scan`); no PDF is built for them. The Reader shows their page pictures through `/library/documents/{id}/pages/{n}/image`.
4. `ocr_page` is registered with budget feature `note_merge` (deferrable): when the day's AI budget is nearly used the job waits until tomorrow (the gateway counts the calls as feature `ocr`). Reading a page you photographed is therefore never allowed to eat the budget of briefs and tutor answers.
5. Telugu pages: the on-device reader is Latin only, so a Telugu page is sent with empty text and the server reads it with Gemini vision (which queues itself). The tablet enqueues its own `ocr_page` job only from the Reader ("Read with AI", with `force`).
6. "Subject" of a document = the syllabus subject (Topic level 1, or the ancestor of its topic) or, for recommended files, the material's `subject`; the owner can put a document under a subject from the "More" menu (needs the syllabus imported). Otherwise "Not sorted yet".
7. Recommended material with no direct PDF link is opened in the browser (`Open website`). Only items with `direct: true` and a `.pdf` URL get a Download button; today none has one, because no link could be verified (see below).
8. Photos taken offline are kept in the app folder (`pending_scans/`), listed in a local-only table, and sent by `ScanUploadWorker` when the network returns (also when Library or Capture opens).
9. Highlight kinds follow the spec: `point`, `must`, `card`. "Add to revision" and "Make flashcard" save a `card` highlight (V1b turns highlights into cards). Highlights from Capture use `document_id = null` until a document is known.
10. `study.library_day` is written by `LibrarySettingsSection` only; the planner reads it.

## Contract

### Server routes (all need login; prefix `/library`)
| Route | In | Out |
|---|---|---|
| `POST /library/upload` | multipart `files` (PDFs and/or pictures), optional `title` | `{documents:[{id,title,type,pages,processing_status,status_detail}], problems:[str]}` |
| `POST /library/scans` | multipart `image`, optional `document_id`, `text`, `title`, `language` | `{document_id, page, language}` |
| `GET /library/documents/{id}/file` | | the PDF |
| `GET /library/documents/{id}/pages/{n}/image` | | JPEG of the page |
| `GET /library/documents/{id}/text` | | `{id,title,pages:[{page,text,source}]}` |
| `POST /library/documents/{id}/retry` | | document brief |
| `POST /library/materials/{key}/download` | | document brief (400 for web pages) |
| `GET /library/search?q=&document_id=&k=` | | `{hits:[{document_id,title,page,text}]}` |

Rows reach the tablet by sync: `documents`, `doc_pages`, `materials` (from `data/sources.yaml`, by `key`), and `library_list` (tablet writes it).
Document statuses: `uploaded`, `processing`, `downloading`, `waiting` ("Waiting for internet"), `needs_ocr`, `processed` ("Processed · searchable"), `failed`.
`DocPage.source`: `text` (PDF text layer), `gemini` (server OCR), `device_ocr` (tablet ML Kit). Page pictures are kept at `DATA_DIR/uploads/<docId>_pages/pNNNN.jpg`.

### Job type `ocr_page` (handler: `library/ocr.py`)
payload `{document_id, page, only_this_page?: bool, force?: bool, language?: "te"}` (the build guide's `image_ref` is not needed: the server has the file).
Without `only_this_page` it reads the next up to 6 pages that still have no text and queues the next batch. Result `{document_id, page, chars, pages_done, remaining}`.
Also created by the server itself for scanned PDFs and for photos sent without text.

### Job `note_merge` (created by the tablet, handled by V1b)
`{text, title, mode:"merge", source?:{document_id,page}, topic_id?}` (from Reader "Make notes" and Capture "Add to my notes (AI)"). Capture shows `result.summary` when present.

### KV
`study.library_day` = `{"enabled":false,"date":null}` (YYYY-MM-DD). Written by `LibrarySettingsSection`.

### Local (tablet-only) data
Table `local_pending_scans` (`PendingScan`): photos waiting to be sent. Files in `filesDir/pending_scans`, `filesDir/library/<docId>.pdf` (downloaded PDFs), `cacheDir/library_pages/`.

## Verified and unverified
- Verified here: the server code and tests (`pytest tests/test_library.py`, ruff), the Kotlin logic by porting the regexes and splitter to Python and comparing with the test expectations.
- NOT verified: every URL in `data/sources.yaml` (no internet in the build environment). All have `verified: false`, and the app adds "(link not checked yet)" to their text. Two Andhra Pradesh items (Socio-Economic Survey, State Budget) have no link at all because no official address could be confirmed. Book names are from common knowledge (edition and publisher not stated).
  Check them with `python -m app.features.library.materials --check` on a machine with internet, then set `verified: true` and add direct PDF links (`direct: true`) if wanted.
- NOT compiled: all Android code (no compiler here); it was read line by line as a compiler would.

## Manual test steps (for `docs/tablet-test-checklist.md`)
1. Open Library. Tap "Upload PDF", pick an NCERT PDF (a text PDF). Within a minute it shows "Processed · searchable" (pull the list down or wait for sync) and pages count.
2. Type a word from the book in the search box: the page appears under "Found inside your documents". Tap it: the Reader opens at that page.
3. In Read: tap "Read aloud". The current sentence is highlighted and the page scrolls. Tap another sentence: reading starts there. Turn on airplane mode and repeat: it still works.
4. Close and reopen the document: it opens at the page you stopped.
5. Tap a sentence, then "Must remember" (or "Save as point", "Make flashcard"): a green message appears. "Ask about this page" opens Ask with the text filled in.
6. Library, "Scan with camera": allow the camera, photograph a printed page. The text appears within seconds (no internet needed). Correct any mistake, tap "Save this page". Repeat for 5 pages (they join one scan). "Finish and read" opens it in the Reader.
7. Switch on airplane mode, scan a page, save: the message says it is saved on the tablet. Turn the network on: the photo goes up by itself (or tap "Send now").
8. Scan a Telugu page with "This page is in Telugu" on: it shows "Waiting for internet (Telugu page)" and then becomes readable after the server reads it.
9. Recommended tab: items show "Needed for". "Add to library-day list" on a book: it appears in the panel on the right; tick it when done.
10. Settings, Library day: switch on, choose "Next Saturday". The Library panel shows the date.

## Known gaps
- Voice note (spec 6.11 selection menu) is not built. The recommended list has no direct PDF links yet, so "Download" is not shown for any item.
- The Reader's original-page view of a PDF downloads the whole PDF once (kept on the tablet).
- On-device OCR is English only. No rotation or crop tools for photos.
- Photos are not compressed on the tablet before upload (the server shrinks them).
- If the Settings screen (V5) does not yet call `LibrarySettingsSection(nav)`, the library day can only be set there once V5 adds it.
