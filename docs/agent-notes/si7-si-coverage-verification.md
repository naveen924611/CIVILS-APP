# SI 7: independent verification of SI (Civil) coverage and facts

Source read page by page: SLPRB_AP_SI_Notification_2026.pdf, all 42 pages (pp 1-30 main text and annexures I(A), I(B), II; pp 31-42 forms only).
Coverage test after this review: `tests/test_syllabus_coverage.py` 8 passed.

## Check 1: coverage of Annexure II (page 29-30; page 9 says Prelim papers 1 and 2 use Paper III and IV of Annexure II)

Every named item is in the coverage file AND has an outline node. Nothing invented (all 55 `official` strings appear in the PDF; the "etc." endings are not items).
- Paper I English: short essay, comprehension, precis, letter writing, paragraph/report writing, translation English to Telugu, plus the general "understanding, correct usage, writing ability" line. Covered.
- Paper II Telugu: same forms, translation Telugu to English. Covered.
- Paper II Urdu (the OR option, translation Urdu to English): NOT in coverage or outline. Deliberate: Naveen writes Telugu. Not added (it would add study hours he will never use). If he ever switches, add a sibling paper.
- Paper III (Prelim Paper 1): 13 arithmetic items and 9 reasoning items (verbal and non-verbal). Covered.
- Paper IV (Prelim Paper 2): 5 General Studies heads. Covered.
- Not syllabus, but stated: English-in-syllabus questions stay in English in the Telugu/Urdu paper; language of Papers III/IV is chosen once (English, Telugu or Urdu) and cannot be changed; no textbook is prescribed (Subject Experts Committee decides disputes).
- Omissions fixed: none needed. No outline or coverage edit made.

## Check 2: facts in data/exam-specs/slprb_si_2026.json

Every figure agreed with the PDF: vacancies (253 / 116 / 9 = 378), zones I-VI for PC 11 (42, 39, 39, 48, 43, 42), fees (600 OC/EWS/BC, 300 SC/ST, 600 non-local), age (21 to under 27 on 01-07-2026, born 02-07-1999 to 01-07-2005), relaxations (EWS/BC/SC/ST 5; govt employee 5; ex-servicemen 3 plus service; NCC instructor 3 plus service, min 6 months; census 3), certificate date on or after 01-04-2026, PMT (men 167.6 / 86.3 / +5; women 152.5 / 40; ABO-ST men 160 / 80 / +3, women 150 / 38), PET (8:00, 9:30, 10:30; 15, 16.5, 18; 3.80, 3.65, 2.75), 40/35/30 per paper, papers and marks, 100 and 200 questions, 3 hours, tie-break (born earlier), pay 44,570-1,27,480, 5%/95% local rule, women 33 1/3 percent, Papers III and IV in English/Telugu/Urdu.
Facts that are silent in the PDF: negative marking (page 30 only says full marks for one correct bubble, zero if none darkened; none stated), application dates (press release).

No numeric errors found. Three wording fixes made in the JSON:
1. Age proof: "SSC certificate only" changed to "SSC, Matriculation or equivalent" (page 6).
2. SC/ST qualification wording brought closer to page 7 (Intermediate plus "should have studied degree").
3. PET note added: PC 11 PET is pass/fail only. The Annexure I gradation marks (pages 12, 28) apply only to PC 12 and 31.

Points in the PDF that are ambiguous (worded as in the JSON, flagged here): Final-paper qualifying percentages are stated "in each paper" (page 13 note 1a), yet the note then talks about Papers I and II only being qualifying; the JSON applies 40/35/30 to all four final papers, which is the safe reading.

### docs/si-civil-plan.md (not edited)
- Section 2, row "2026-07-01": says "age 21 to 27 completed". PDF: must have attained 21 and must NOT have attained 27. Wording error, harmless numerically.
- Section 4.1 S14 mentions the 40/35/30 cut-off only for Prelims. The same per-paper cut-off also applies to all Final papers (page 13). Add to the Goals screen if not there.
- Section 3 and the rest of 4.1: all numbers match. Group-I rows and dates in section 2 are outside this PDF and were not checked.

## Check 3: other things the exam tests

The PDF has NO interview, viva, personality test, typing test, driving test, swimming test, computer test or separate sports-quota trial (sportspersons take the same written tests and events, page 18 point a). Only stages: Prelim written (OMR) then PMT then PET then Final written. Also not tests but required: certificate verification, antecedent verification, medical examination (pages 22, 26). Handwritten descriptive answers are in Final Papers I and II only.
Physical standards (PMT/PET) are not syllabus items and are handled by the Goals screen, not the outline.
