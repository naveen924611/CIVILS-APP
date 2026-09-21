# SI (Civil) goal: plan, arrangements and coverage check

Written 2026-09-21. Sources: `SLPRB_AP_SI_Notification_2026.pdf` (Rc.No.81/SLPRB/Rect.1/2026, 16-09-2026) and
`Group_I_072026.pdf` (APPSC Brief Notification 07/2026, 15-09-2026). Facts are in `data/exam-specs/` (one JSON each).

## 1. What the goal is

Naveen now prepares for THREE exams, not two:

| Goal | Exam | Where the facts are |
|---|---|---|
| A | UPSC CSE (Prelims, Mains) | existing, unchanged |
| B | APPSC Group-I (07/2026) | `data/exam-specs/appsc_group1_2026.json` |
| C | **SLPRB SI (Civil), Post Code 11** (new) | `data/exam-specs/slprb_si_2026.json` |

SI (Civil) is different from A and B in two ways: it has a **physical selection** (PMT then PET) and two **descriptive
qualifying papers** (English, Telugu) on top of two objective papers. Prelims marks do NOT count for the final merit; only
Final Paper III + IV (400 marks) decide the rank.

## 2. The dates that matter (all verified from the PDFs unless marked)

| Date | What | Status |
|---|---|---|
| 2026-10-06 | Group-I application opens. The Detailed Notification (age, physicals, vacancies) is due on or before this day | verified (brief notification) |
| 2026-10-27, 11:59 PM | Group-I application closes | verified |
| not announced | SLPRB SI application dates ("by press release") | verified that it is NOT in the PDF |
| not announced | SI Prelims date, Group-I Screening date | not announced yet |
| 2026-07-01 | SI age reference date: at least 21 and not yet 27 on this day (born 02-07-1999 to 01-07-2005), degree cut-off | verified |
| on or after 2026-04-01 | Issue date for community / NCL / EWS certificates (SI) | verified |

## 3. Exam structure we must cover (what will be written)

### SI (Civil) PC 11
1. **Prelim Written Test** (OMR, objective): Paper 1 Arithmetic and Test of Reasoning (100 Q, SSC standard) and Paper 2 General Studies (100 Q, degree standard). Qualifying % in EACH paper: 40 OC/EWS, 35 BC, 30 SC/ST. No marks carried forward.
2. **PMT**: men 167.6 cm height, 86.3 cm chest (+5 cm expansion); women 152.5 cm height, 40 kg. Relaxed in ABO-ST agency areas.
3. **PET** (qualifying for PC 11): 1600 m plus one of 100 m or long jump. General 8:00 min / 15 s / 3.80 m; ex-servicemen 9:30 / 16.5 s / 3.65 m; women 10:30 / 18 s / 2.75 m.
4. **Final Written** (4 papers, 3 h each): I English (descriptive, qualifying), II Telugu or Urdu (descriptive, qualifying), III Arithmetic and Reasoning (200 Q), IV General Studies (200 Q).

### APPSC Group-I (163 posts: Deputy Collector, ACST, DSP (Civil) ...)
1. **Screening Test** (OMR, 1/3 negative): Paper I GS (120 Q) and Paper II General Aptitude (120 Q: mental ability and administrative/psychological abilities 60, Science and Technology 30, Current Events 30).
2. **Mains**: Telugu (150, qualifying, SSC), English (150, qualifying, SSC), Papers I to V at 150 each (General Essay; History, Culture and Geography of India and AP; Polity, Constitution, Governance, Law and Ethics; Economy and Development of India and AP; Science and Technology), Interview 75. Total 825.

## 4. Coverage matrix: every part of every exam, and where the app covers it

Status: **Done** = syllabus outline in the app + practice tool. **Partial** = covered, with a stated limit. **Manual** = the app tracks it, the student trains outside the app.
The column "Outline file" names the JSON in `data/syllabus/`; the file `data/exam-specs/coverage/<key>.json` lists every official
syllabus line with the exact outline node that covers it, and `backend/tests/test_syllabus_coverage.py` fails when a node is missing.

### 4.1 SI (Civil)

| # | Official component | Marks | Outline / tool in the app | Status |
|---|---|---|---|---|
| S1 | Arithmetic: number system, SI and CI, ratio and proportion, average, percentage, profit and loss, time and work, work and wages, time and distance, clocks and calendars, partnership, mensuration (Prelims P1, Final P3) | 100 / 200 | `slprb_si_written.json`, paper "Arithmetic and Test of Reasoning"; daily generated **aptitude drill** (exact answers, computed by the server, no AI) | Done |
| S2 | Reasoning, verbal: analogies, similarities and differences, problem solving, analysis, judgment, decision making | (in P1/P3) | same outline; drill generates series, coding-decoding, blood relations, directions, ranking, odd one out, syllogisms, statements and conclusions | Done |
| S3 | Reasoning, non-verbal: spatial visualisation, spatial orientation, visual memory | (in P1/P3) | same outline (topics listed); drills use text puzzles only. Figure questions need images, so use imported past papers (PYQ import) and a workbook | **Partial** |
| S4 | General Science incl. contemporary developments, environment | 100 / 200 | GS paper of `slprb_si_written.json`; tests generated from the student's own notes | Done |
| S5 | Current events, national and international | (in P2/P4) | Daily briefs (already built), tagged to SI topics | Done |
| S6 | History of India (social, economic, cultural, political, Indian National Movement) | (in P2/P4) | GS paper, History nodes | Done |
| S7 | Geography of India | (in P2/P4) | GS paper, Geography nodes | Done |
| S8 | Indian Polity and Economy (political system, rural development, planning, economic reforms) | (in P2/P4) | GS paper, Polity and Economy nodes | Done |
| S9 | English (Final P1, qualifying): short essay, comprehension, precis, letter, paragraph/report, translation English to Telugu | 100 | English paper of the outline; writing prompt bank (`data/writing/`) sent to the existing Answer-writing evaluator | Done |
| S10 | Telugu or Urdu (Final P2, qualifying): same forms, translation to English | 100 | Telugu paper of the outline; existing Telugu module (passages, templates, translation, vocab) plus the prompt bank. **Urdu is not built** (the student takes Telugu) | Done for Telugu |
| S11 | PMT | pass/fail | Goals screen: self-check of height, chest, weight against the right column (men, women, ABO-ST) | Done |
| S12 | PET | pass/fail | Goals screen: log of 1600 m, 100 m, long jump against the standards; a daily physical block in the plan (see 5.4) | Done (tracking), **Manual** (training) |
| S13 | Eligibility: age 21 to 27 on 01-07-2026 plus relaxations, degree, fee, certificates issued on/after 01-04-2026, medical | pass/fail | Goals screen: profile and checklist | Done |
| S14 | Qualifying cut-offs: 40/35/30 percent in EACH paper (Prelims, and again in each Final paper, page 13) | pass/fail | Goals screen shows the cut-off for the chosen category and a two-paper calculator; mock tests show a per-paper percent | Done |

### 4.2 APPSC Group-I

| # | Official component | Marks | Outline / tool | Status |
|---|---|---|---|---|
| G1 | Screening Paper I: History and Culture; Constitution, Polity, Social Justice, IR; Indian and AP Economy and Planning; Geography (30 each) | 120 | `appsc_group1_prelims.json` (rewritten from the PDF, verified) | Done |
| G2 | Screening Paper II Part A: General Mental Ability, administrative and psychological abilities | 60 | same file, plus the aptitude drill (shared with SI) | Done |
| G3 | Screening Paper II Part B(i): Science and Technology; B(ii): Current Events | 60 | same file; briefs | Done |
| G4 | Mains Telugu (150, qualifying; 13 question types incl. Vemana and Sumathi verses) | 150 | `appsc_group1_mains.json`; Telugu module; prompt bank | **Partial**: verse-meaning practice needs the SCERT text pasted in |
| G5 | Mains English (150, qualifying; 10 question types) | 150 | outline; prompt bank; English grammar drill not generated | **Partial** |
| G6 | Mains Paper I General Essay (3 essays of 800 words; sections current affairs, socio-political/economic/environmental, cultural-historical/civic/reflective) | 150 | outline; Answer-writing evaluator (essay kind) | Done |
| G7 | Mains Paper II History, Culture, Geography of India and AP (15 units) | 150 | outline, verified line by line | Done |
| G8 | Mains Paper III Polity, Constitution, Governance, Law and Ethics (15 units incl. ethics, basic knowledge of laws) | 150 | outline | Done |
| G9 | Mains Paper IV Economy and Development of India and AP (12 units) | 150 | outline | Done |
| G10 | Mains Paper V Science and Technology (9 units) | 150 | outline | Done |
| G11 | Interview | 75 | outline node with the app's own preparation topics (marked "not from the notification") | **Partial** (no official syllabus exists) |
| G12 | Application window 06/10 to 27/10/2026 and the Detailed Notification | dates | Goals screen countdown and "Applied" tick | Done |

### 4.3 What is NOT in the notifications (do not treat as fact)

The "God Mode" guide pasted by the student says many things. Checked against both PDFs:

| Claim in the guide | Result |
|---|---|
| Group-I has Screening (Prelims) + Mains + Interview, 825 marks in the merit stages | **Confirmed** (brief notification) |
| Papers I to V topics | **Confirmed** (pages 13 to 24) |
| DSP height 167.6 cm, chest 86.3 cm; women 45.5 kg | **Unverified for Group-I DSP.** The 167.6 / 86.3 numbers appear in the SI notification, so they are probably copied from SI. For SI women the notification says 40 kg, not 45.5 kg. Group-I physicals will be in the Detailed Notification |
| "No PET for DSP" | **Unverified, and probably wrong.** Group-I brief notification page 2 says: for post codes 03 (DSP Civil) and 04 (DSP Communications) "see the physical requirements in the Detailed Notification", so DSP DOES have physical requirements. What they are is not known until 06/10/2026 |
| Age limits and past-year cut-offs for Group-I | **Unverified** (Detailed Notification is not out) |
| Coaching-site cut-off numbers | Not in any official document; ignored |

## 5. Arrangements (what we build)

### 5.1 Data (no code)
- `data/exam-specs/slprb_si_2026.json`, `appsc_group1_2026.json`: the verified facts.
- `data/syllabus/slprb_si_written.json` (new, verified): one outline for Arithmetic and Reasoning, General Studies, English, Telugu. The Prelims and Final papers share one syllabus (Annexure II), so it is studied once.
- `data/syllabus/appsc_group1_prelims.json` and `appsc_group1_mains.json` rewritten from the notification (verified). The old starter files are moved to `data/syllabus/_superseded/` and the seeder retires their unapproved copies (`supersedes` field).
- `data/exam-specs/coverage/*.json`: official line to outline node; checked by a test.
- `data/writing/si_and_group1_prompts.json` (90 prompts): original practice prompts for the descriptive papers (English and Telugu forms). The tablet reads a byte-identical copy in `android/app/src/main/assets/writing_prompts.json` (a test keeps them equal).

### 5.2 Server
- Exam tag `SI` accepted everywhere (`trees.py`), exam name matching by whole word (so "SI" cannot match inside another word).
- Priority KV `exam.priority` now has `SI`.
- Planner hook: daily **physical training** block and a daily **aptitude drill** block, only while the SI exam exists.
- Deterministic aptitude / reasoning generator (`features/tests/aptitude.py`), test kind `aptitude`.
- Seeder `supersedes` support.

### 5.3 Tablet
- Third exam goal in Exams (dates, priority "More on SI"), seeded once for new and existing installs.
- New **Goals** screen (reachable from Exams and Today): SI eligibility profile and checklist, PMT self-check, PET log with pass/fail, Group-I application countdown and "Applied" ticks, prelim cut-off table.
- SI chip in every exam filter (Notes, Syllabus, Tests).
- Aptitude drill entry in Tests; physical block Start opens the Goals screen.
- Writing prompt picker in Answers (offline, from the bundled copy of the bank).

### 5.4 Daily rhythm added by the plan (SI on)
06:00 physical block, 45 minutes, outside the study hours: Mon/Wed/Fri run (1600 m pace work), Tue/Thu sprint and long-jump drills,
Sat PET simulation (log the result in Goals), Sun light mobility. Weekday aptitude drill: 20 questions, 25 minutes, rotating the
12 arithmetic and reasoning areas.

## 6. Owner steps that cannot be automated
1. Watch https://slprb.ap.gov.in for the SI application press release and https://psc.ap.gov.in from 06/10/2026 for the Detailed Notification. When they come, send them here so the age, physical and date fields can be checked.
2. Never enter passwords or pay fees through Claude. Applying and paying is done by the student.
3. Paste the SCERT Telugu textbook verses (Vemana, Sumathi Satakam) into the Notes to practise "explain the verse".

## 7. Known gaps (honest list)
- Figure-based non-verbal reasoning cannot be generated as text.
- Urdu paper is not covered.
- Group-I English grammar MCQs are not generated by the drill.
- Group-I DSP physical and age rules are unknown until the Detailed Notification.
- Nothing here has been compiled or run on the tablet; the GitHub Actions build is the first compile.
