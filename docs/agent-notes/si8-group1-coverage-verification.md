# SI8 - Group-I 2026 coverage and facts verification

Source of truth: Group_I_072026.pdf (Brief Notification 07/2026, 24 pages, scanned). Every page was read visually on 2026-09-21.
Page map: 1-2 posts, pay, qualification, dates; 3 instructions; 4 screening scheme; 5 Mains scheme table (+ start of GS syllabus); 5-11 Screening syllabus
(GS Paper I pp5-9, Paper II pp9-11); 11-12 English; 13 Telugu; 14 Paper I Essay; 15-18 Paper II; 18-20 Paper III; 20-22 Paper IV; 22-24 Paper V.

## CHECK 1 - coverage

Method: read each PDF page, compared every unit and phrase with the outline node titles, then checked every coverage item (233 prelims, 445 mains) for path
resolution, title/official overlap and page number.

- Omissions: none. Every line of the Screening syllabus (Paper I A-D, Paper II A 1-17, B(i) 18-22, B(ii) current events), Mains Papers I-V, English (10 question
  types + the 14-item grammar list) and Telugu (13 question types) is present as a node with the same meaning, and each has a coverage item with a resolving path.
- Wrong page: 1. Mains Paper V item 7 leaf "Bio - diversity, fermentation, Immuno - diagnosis techniques" was p23; it is on p24 (top of the page). FIXED in appsc-g1-mains-2026.json.
  Other lines that straddle a page break carry the page they start on; that is consistent and left as is.
- Wrong paths: none found (all resolve; no official/leaf pairs that point at unrelated nodes).
- Invented nodes: none in the Screening or Mains papers. The Interview children (DAF and bio-data, Current affairs, Home-state knowledge, Ethics scenarios,
  Communication) are the app's own suggestions and are declared as such in the outline's source_note; the PDF gives only "Interview 75 marks".
- Notes (not errors): the Paper I Essay format sentence "three essays, one from each of the three sections, about 800 words each" is covered at paper level
  (coverage path = the Paper I node), not as a separate leaf. Screening Paper II part B(ii) is one PDF heading, split into regional/national/international nodes.
  The Mains table (p5) names Paper V "Science, Technology and Environmental Issues"; the syllabus page (p22) headings it "Science and Technology". The outline uses the table name.
- Level-0 titles unchanged. test_syllabus_coverage.py: 8 passed.

## CHECK 2 - facts in data/exam-specs/appsc_group1_2026.json

Confirmed correct: application window 2026-10-06 to 2026-10-27 23:59; Detailed Notification on or before 2026-10-06; 163 posts and every vacancy count
(11,14,36,3,14,3,1,15,12,2,3,14,35 = 163); screening Paper I and II (120 Q, 120 marks, 120 min each; sections 30x4; Paper II A 60, B(i) 30, B(ii) 30);
negative marking 1/3; Mains papers 150 marks, 180 min; Telugu and English are qualifying (SSC standard); interview 75; total 825;
English list = 20+10+10+15+15+15+15+15+20+15 = 150; Telugu list = 20+20+11x10 = 150.

Errors fixed:
1. Post 05 was "Divisional Employment Officer". PDF: "District Educational Officer in A.P. Educational Service" (14). It is NOT an Employment post.
2. Post 11 name expanded to the exact PDF name "District Employment Officer in A.P Employment Exchange Service" (3). This is the only Employment post.
3. Post 13 was "DDO Panchayat Raj"; PDF: "Divisional Development Officer in A.P. Panchayat Raj Service".
4. All post names replaced with the full PDF names (01 Deputy Collectors ... Executive Branch; 02 Asst. Commissioner of State Tax; 03 DSP (Civil) Category-II; 04 DSP (Communications);
   06 Regional Transport Officers; 10 Asst. Treasury Officer/Asst. Accounts Officer in A.P. Treasury & Accounts Service; 12 Assistant Audit Officer in A.P. State Audit Service).
5. Pay: only "pay_pc_01_to_03" was given. PDF: 61,960-1,51,370 for PC 01-05 (not just 01-03); 57,100-1,47,760 for 06-08; 54,060-1,40,540 for 09-12; 65,360-1,54,980 for 13.
   Replaced by "pay_scales".
6. Mains Paper V was "Science and Technology"; the scheme table says "Science, Technology and Environmental Issues" (page heading says Science and Technology). Both recorded.
7. Added missing facts: educational qualification (PC 01-03, 05-13 any bachelor's degree; PC 04 B.E./B.Tech ECE / E&T / Radio Engineering), screening date "to be announced in the
   Detailed Notification", Degree standard for Screening and Mains I-V, medium English/Telugu, and the note that 825 = 5x150 + 75 (qualifying papers not counted).
   The verified_note previously implied nothing was in the PDF about physical requirements; it now quotes the one sentence that exists.
Nothing in the codebase reads this file (grep of backend, android, tools), so the restructure is safe.

## CHECK 3 - DSP / physical / age claims

The PDF contains no physical measurement, no PET/PMT, no medical standard and no age limit of any kind. The only related text:
- Page 2, para 3 (Educational Qualification): "For P.C.No.03,04 please see the physical requirements in Detailed Notification."
- Page 2, para 4: "Detailed Notification with breakup of vacancies, Scale of pay, Age, Community, Educational Qualifications and any other information with instructions will be
  available on the Commission's Website (https://psc.ap.gov.in) on or before 06/10/2026."
- Page 2, para 5: "The date of Detailed Notification will be reckoned as the crucial date in all aspects."
So the claims "no PET for DSP", DSP height 167.6 cm / chest 86.3 cm, and women 45.5 kg are NOT supported by this PDF. In fact the PDF says physical requirements DO exist for
PC 03 (DSP Civil) and PC 04 (DSP Communications) and are in the Detailed Notification, which is contrary to "no PET for DSP" until that notification is read.
Treat all three as unverified until 2026-10-06.

## Answer

Is every part Naveen will write in Group-I covered by the outlines? YES.
