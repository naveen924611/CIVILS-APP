# SI outline agent notes (si2)

## Built
- `data/syllabus/slprb_si_written.json` (key `slprb-si-written`, verified): one outline for the Prelim and Final written papers. 4 papers, 192 nodes, 140 leaves, 275.5 study hours in total (Arithmetic and Reasoning 97.5, General Studies 132, English 23, Telugu 23). Every node is tagged `SI`. Leaves are 1.0 to 3.0 hours.
- `data/exam-specs/coverage/slprb-si-written.json`: 52 items, one per official phrase of Annexure II (pages 29-30), each with an exact path. All paths resolve (checked case-insensitively, non-alphanumerics collapsed).
- App's own additions, labelled in the titles: "Andhra Pradesh basics (helpful, not named in the notification)" under History, Geography and Polity and Economy; "Supporting skills (the app's own addition, not a listed question form)" under English and Telugu; the level 3 subtopics.
- Level 1 and 2 titles are the official ones ("Ratio & proportion", "Profit & loss" keep the notification's "&"). Reasoning verbal / non-verbal is expressed in level 3 titles because the official nine topics are not split that way.
- Physical tests (PMT/PET) are not in the outline (Goals screen).

## Validation
`clean_tree` count with the current server: 192 nodes, 140 leaves (the `SI` tag is dropped until the server allows it, expected). No duplicate sibling titles.

## Spec JSON check (`slprb_si_2026.json`)
Read pages 1-30 (all of the rules, PMT/PET, papers, Annexure I and II) and skimmed 31-36 (certificate forms). No wrong number was found: vacancies (253/116/9, total 378, zones), age 21-27 on 01-07-2026, fees 600/300/600, PMT, ABO-ST, PET standards, qualifying 40/35/30, marks and question counts, syllabus lists all match.
Additions only (facts that were missing):
1. `age_relaxation_years.retrenched_census_employee_1991`: 3, and a fuller `age_relaxation_note` (govt employee = length of service up to 5 years, corporations and local bodies excluded; ex-servicemen and NCC 3 years in addition to service; NCC needs 6 months; page 6).
2. `medical.disqualifying`: added Physically handicapped, Fractured limbs, Decayed teeth, Abnormal psychological behaviour; "Hard hearing" reworded "Hard of hearing" (page 8).
3. Final Written stage: `qualifying_percent` (40/35/30 in each of the four papers) and a note that Papers III and IV can be set in English, Telugu or Urdu (page 13-14).
Formatting kept as before.

## Not covered / unreadable
- Urdu alternative of Paper II ("OR Urdu ... translation from Urdu to English"): intentionally not built (the student takes Telugu); no coverage item, so this official line is uncovered.
- Pages 37-42 (certificate forms, annexures X-XIII) were not read; they carry no exam rules. Page 9 of the notification says the Prelims syllabus is Papers III and IV of Annexure II, which is why one outline serves both.
- The notification never mentions negative marking (page 30: full marks for the darkened correct bubble, zero if none darkened).
