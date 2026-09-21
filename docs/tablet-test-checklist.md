# Tablet test checklist (real Lenovo Tab M10) - run at every milestone

- [ ] Install the APK; app opens; cold start under 3 s
- [ ] Log in to the HTTPS server
- [ ] Offline acceptance test (airplane mode): open app, listen to today's brief, read a note/document, revise, take a downloaded mock, scan an English page, ask a question (queued), use voice commands, 7 PM alarm fires; reconnect and confirm sync + queued answers
- [ ] Screen-off audio plays 30 min
- [ ] Alarms still fire after a reboot
- [ ] FCM test push arrives
- [ ] Headphone buttons work (single / double / triple press)
- [ ] No crash after 30 min of use (memory)
- [ ] Battery use over a normal day is acceptable

## M2 additions (briefs, audio, notifications)
- [ ] First launch after login shows "Set up this tablet": allow notifications, exact alarms, battery, offline English (India) voice (each step shows a tick when done)
- [ ] Settings > Daily briefs: change the morning time by 5 minutes, tap Save; it says "Saved"; the server log shows the job re-scheduled
- [ ] Briefs > "Prepare a brief now": after a few minutes the brief appears with items, audio and a notification (Play now / Read / Remind in 30 min)
- [ ] Notification "Play now" opens the app and starts audio; "Remind in 30 min" brings it back
- [ ] Play a brief with the screen off for 10 minutes; lock-screen controls and headphone buttons work; speed and sleep timer buttons work
- [ ] Airplane mode: the brief still opens and plays (audio was saved: header says "saved for offline")
- [ ] Alerts screen lists "Your morning brief is ready" with Play now / Read
- [ ] Reboot the tablet: brief alarms still fire (check at the next brief time)
- [ ] 3 days in a row: brief arrives at the set time with audio


## M3 to M12 additions (written unattended; nothing here has been tried on a real tablet yet)
First push the repo and install the APK built by GitHub Actions. If the build fails, send me the red error lines. Then tick these off:

### M3 Library, Reader, Capture
- [ ] Open Library. Tap "Upload PDF", pick an NCERT PDF (a text PDF). Within a minute it shows "Processed · searchable" (pull the list down or wait for sync) and pages count.
- [ ] Type a word from the book in the search box: the page appears under "Found inside your documents". Tap it: the Reader opens at that page.
- [ ] In Read: tap "Read aloud". The current sentence is highlighted and the page scrolls. Tap another sentence: reading starts there. Turn on airplane mode and repeat: it still works.
- [ ] Close and reopen the document: it opens at the page you stopped.
- [ ] Tap a sentence, then "Must remember" (or "Save as point", "Make flashcard"): a green message appears. "Ask about this page" opens Ask with the text filled in.
- [ ] Library, "Scan with camera": allow the camera, photograph a printed page. The text appears within seconds (no internet needed). Correct any mistake, tap "Save this page". Repeat for 5 pages (they join one scan). "Finish and read" opens it in the Reader.
- [ ] Switch on airplane mode, scan a page, save: the message says it is saved on the tablet. Turn the network on: the photo goes up by itself (or tap "Send now").
- [ ] Scan a Telugu page with "This page is in Telugu" on: it shows "Waiting for internet (Telugu page)" and then becomes readable after the server reads it.
- [ ] Recommended tab: items show "Needed for". "Add to library-day list" on a book: it appears in the panel on the right; tick it when done.
- [ ] Settings, Library day: switch on, choose "Next Saturday". The Library panel shows the date.

### M4 Syllabus, Notes
- [ ] Open Notes, then "Syllabus map". You should see the outlines waiting for approval (they say they are not checked).
- [ ] Tap "Check and approve" on one outline. Rename one line, split one, delete one. Tap Approve. Wait a moment: the syllabus map fills with topics.
- [ ] Tap a paper: the arrow opens its subjects. Tap a small topic, change its status to "Studied": the coverage % of the paper goes up.
- [ ] Tap "Open notes" on a topic. It says the note is empty. Tap "Make notes" (needs a book in the Library about this topic). After a minute the notes, "Must remember" box and questions appear.
- [ ] Tap Edit, type a sentence, Save. Tap "Add to notes", paste a new paragraph, Add. Your sentence stays; new points appear under "New from your material" with Add and Skip.
- [ ] In airplane mode press "Make notes": the line says "waiting for internet"; turn the network on and it completes.
- [ ] Notes tab "In the news": after some briefs have arrived, items about the topic appear (matching runs every few hours).
- [ ] "Report error" with a comment: the summary line says what was found. Your own edited text is never replaced.
- [ ] Import your own syllabus: "Import a syllabus", paste one paper of the official syllabus, Start reading. After a minute it shows as "Check and approve".

### M5 Revision, Planner, Today
- [ ] Setup: reach the exams step, confirm 4 default exams, tap Continue.
- [ ] Exams: change priority and hours; reopen, values persist.
- [ ] Settings: Planner and Revise sections appear and open their screens.
- [ ] Today: plan blocks show; tap a block, it opens the linked screen; tick done, restart, tick persists.
- [ ] Airplane mode: Today still shows a fallback plan; ticks persist; reconnect, plan refreshes.
- [ ] Planner: Regenerate; week view shows 7 days; change study hours, regenerate, block minutes change.
- [ ] Revise: queue shows groups with reason pill; up/down moves; Snooze hides a topic.
- [ ] Session: rate Again/Hard/Good/Easy; interval labels look sane; undo works.
- [ ] Hands-free: TTS reads the front; say "good"/"again" to grade.
- [ ] Sunday: Sunday review tab lists fading cards from the last 7 days.

### M6 and M9 Ask, voice, Explain-back, Answers
- [ ] Open Ask, type "What is Article 21?" and press Send. With internet on, an answer with sources appears in a minute.
- [ ] Turn on airplane mode. Ask a question that is in your notes: it is answered at once with "Answered offline from your notes".
- [ ] In airplane mode ask something that is not in your notes: it shows "Waiting for internet" and appears in the right panel. Turn the internet on: the answer arrives and a notification says "answers ready".
- [ ] Tap Listen on an answer: it is read aloud. Tap Save to notes.
- [ ] Tap the big mic on Ask and say "What is federalism?". Tap the round mic on another screen and say "What's next?", "Start revision", "Set timer 2 minutes", "Mark done", "Pause".
- [ ] Ask > Explain it back: choose a topic, tap the big button, explain for a minute, press Get feedback. With internet on, see covered/missed/needs correcting. Press Make cards from missed points; the cards appear in Revise.
- [ ] Ask > Answer writing: Get a question (Mains), press Start timer, write on paper, tap Take a photo for each page, press Send for feedback. In airplane mode it says photos are waiting; turn the internet on and the score arrives.
- [ ] Answer writing > Type it here: write about 100 words, Send for feedback, see the evaluation.
- [ ] Settings > Voice and reading: change speed, press Hear a sample.

### M8 and M10 Tests, Sheets, Report
- [ ] Open Revise, tap "Mock tests". It shows "Ready to take" and "Taken". Tap "Weekly mock now (25 questions)". The message says it will be made; when online, the test appears within a minute or two.
- [ ] Tap a ready test. Check the start page (question count, minutes, the negative-marking switch). Tap Start. The timer counts down at the right.
- [ ] Answer a question, then tap Sure / Unsure / Guess. Tap "Mark to look again", jump with the number grid, close the app, reopen the test: your answers and the timer are still there.
- [ ] Turn on flight mode and take a test to the end: it must still work. Tap "Finish test" with one question empty: it warns about the empty one.
- [ ] On the result page check: score, subject bars, mistake types, guessing message, and every question with the right answer. Change the type of one wrong answer to "Silly mistake".
- [ ] Open "Mistake book" from Tests: the wrong answers are there, with filters by subject and type. Tap "Retest mistakes". Answer two of them correctly on two different days: they leave the book.
- [ ] Tests screen, "Topic test": pick a topic and 10 questions (online once). "Full past paper" and "Read a past paper from my library" ask for exam, year and paper.
- [ ] Settings, Study plan: switch "Negative marking" and the weekly mock day. A new test starts with that setting.
- [ ] Revise, "Revision sheets": sheets are listed by subject. Open one: read it, tap "Listen" (about 3 minutes, works offline), tap "Save as PDF, share or print" (needs internet once) and choose an app.
- [ ] If an exam is 30 days away or less, the sheets list and the report show a "Last-month mode" card with today's sheets.
- [ ] Open "Weekly report" (from Today or Sheets). After Sunday 20:00 it shows hours, topics, cards, accuracy, weak spots and next week. Tap "Listen to report", "Save as PDF or share", and "Accept next week's plan" (the button then shows "Accepted").

### M7 and M11 Settings, Focus, Videos, Widget
- [ ] Settings: change Colours to Dark and back; move the Text size slider, let go: the whole app changes size.
- [ ] Settings, Notifications: turn the evening summary on for 2 minutes from now (use the time buttons), wait: "Your day" arrives. Turn Quiet hours on around now: nothing arrives.
- [ ] Settings, Storage: the bar and numbers load. Change the limit. Press Export my data, choose Drive or email: a zip arrives with tables, notes and settings. Press Sync now.
- [ ] Storage page: Make a backup now; it appears in the list. Delete audio older than 60 days asks first.
- [ ] Focus: type a task, choose 25 + 5, Start. Allow Do Not Disturb when asked: the chip says it is on. Pause, Resume, open Notes (the timer keeps going), come back, Finish early, answer 75%. Today's log shows the minutes. Screen off: at the end "Session done" arrives with 50/75/100 buttons.
- [ ] Videos: paste a YouTube link: it appears. Open it: the video plays inside the app. Press "+ Note at ..." while it plays; tap the time on the note to jump there. Airplane mode: notes still save.
- [ ] Paste a link of a video that does not allow embedding: it shows "Opens in YouTube · link saved here" and Open in YouTube works.
- [ ] "What to listen for" -> Make it: a few lines appear after the server answers (waits when offline).
- [ ] Home screen: add the Civils widget. It shows the next task, cards to revise and time studied; tapping opens the app.
- [ ] Setup: Settings, "Check notifications, battery and voice" reopens the wizard; step through all 6 steps.

### M12 Telugu, Monthly digests
- [ ] Sync once. Open Telugu practice (Settings > Study plan > Open Telugu practice). Today shows a Words card and a Reading card.
- [ ] Start words: tap Hear the word (Telugu voice), Show the meaning, "I knew it" / "Not yet". Finish; the card says All done.
- [ ] Open the reading passage, answer all questions, Check my answers; right answers turn green. Show English.
- [ ] Open a translation: type a Telugu answer, Send for feedback. Airplane mode: it says Waiting for the tutor. Internet on: feedback and score arrive.
- [ ] On a Wednesday or Saturday a letter/essay task appears. Write and send it.
- [ ] Progress tab: streak, 14-day bars, per-kind numbers. My writing tab lists what you sent.
- [ ] Settings > Telugu practice: change minutes with -5 / +5; the planner keeps that time (Today plan shows Telugu practice).
- [ ] Settings > Monthly digests: "Make last month's digest", wait for the server, open it, Share text, Open PDF, Share PDF.

### SI (Civil) goal
- [ ] Sync, then open Exams: three "SLPRB SI (Civil)" rows (Prelims, Physical (PMT and PET), Final Written) with "Date not announced". The priority card has "More on SI (Civil)".
- [ ] Syllabus review: three new pending outlines (SI written, Group-I Prelims, Group-I Mains). Approve each. The filter chips now include SI; Notes and Syllabus can filter by SI.
- [ ] Exams > "SI (Civil) goal and checklist" opens Goals. Group-I application countdown shows days to 27 Oct 2026; tick "I applied".
- [ ] Goals: fill the profile. Date of birth 02-07-1999 gives "eligible"; 01-07-1999 gives "too old" (BC gets +5 years). Category changes the prelim cut-off (40 / 35 / 30).
- [ ] Body check: men 167.6 cm and chest 86.3 cm pass; 167.5 cm fails with the shortfall. Women weight 40 kg passes, 39.9 fails.
- [ ] PET log: 1600 m 8:00 and 100 m 15 s passes for General; 8:01 fails. Saved entries stay after closing the app; delete works.
- [ ] Today (next day's plan): a 06:00 "Run / Sprint / Mobility" block that opens Goals, and an evening "Aptitude drill" block (not on Sunday) that opens a 20-question test with correct answers.
- [ ] Tests > Aptitude drill: pick Percentage, 10 questions; after the server answers the test appears. No negative marking.
- [ ] Answers > Practice prompt: filter SI and Telugu; choosing a prompt creates a draft with the prompt and word limit; send it for feedback.
