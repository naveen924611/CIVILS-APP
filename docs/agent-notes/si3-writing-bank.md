# SI3: writing practice prompt bank

File: `data/writing/si_and_group1_prompts.json` (version 1, 90 items). Note: `docs/si-civil-plan.md` mentions a `.yaml` name; the file is JSON as requested.

## Counts
- Total 90: 35 English (`w-en-001..035`), 35 Telugu (`w-te-001..035`), 20 General Essay (`w-es-001..020`).
- By exam tag: SI 29, APPSC 53, BOTH 8.
- English (35): essay 4, letter 5, application 1, press_release 3, report 4, paragraph 2, visual_information 3, speech 3, precis 4, comprehension 4, translation_en_te 2.
- Telugu (35): essay 4, verse_meaning 4, precis 3, comprehension 3, speech 2, press_release 2, letter 3, application 2, report 2, paragraph 1, translation_en_te 3, translation_te_en 3, word_meanings 3.
- General Essay (20): Section I current affairs 7, Section II socio issues 7, Section III cultural/civic/reflective 6. Each 50 marks, 800 words, no dated facts.

## Rules followed
- Keys are unique and must never change (append new keys only).
- All passages (precis, comprehension, translation) were written for this bank. Nothing copied from papers or books.
- Visual-information tables are imaginary and say so in the prompt. No real statistics anywhere.
- verse_meaning prompts quote no verse; the student picks one from the SCERT textbook, with themes: truthfulness, patience, education, friendship.
- Marks per form for SI items are practice estimates (the notification gives only 100 per paper); Group-I marks follow `appsc_group1_2026.json`.
- Every Telugu item contains Telugu script and an English gloss in brackets.

## Caveat
The Telugu text was written by an AI assistant and has NOT been checked by a Telugu teacher. Spelling, sandhi and register may have mistakes. Have a teacher review before treating the model wording as correct. The `note` field in the JSON says the same.

## Validation
Python check: valid JSON, unique keys, all 9 fields present, forms in the allowed set, exam in SI/APPSC/BOTH, Telugu items contain U+0C00-U+0C7F, English items contain none.
