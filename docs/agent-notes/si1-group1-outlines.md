# SI1 - APPSC Group-I official outlines

Built from the official APPSC Brief Notification 07/2026 (15-09-2026), read page by page from the scanned PDF.

## Files
- `data/syllabus/appsc_g1_prelims_2026.json` (key `appsc-g1-prelims-2026`, supersedes `appsc-group1-prelims`): 229 nodes, 180 leaves, 2 papers. Source pages 4-11.
- `data/syllabus/appsc_g1_mains_2026.json` (key `appsc-g1-mains-2026`, supersedes `appsc-group1-mains`): 441 nodes, 370 leaves, 8 level-0 nodes (Paper I to V, Telugu, English, Interview). Source pages 5, 11-24.
- Coverage: `data/exam-specs/coverage/appsc-g1-prelims-2026.json` (233 items) and `appsc-g1-mains-2026.json` (445 items). Every item path resolves in its outline and every non-interview leaf is referenced by an item (checked by `tools/_si1_build/validate.py`).
- Old outlines moved with `mv -n` to `data/syllabus/_superseded/` (`appsc_group1_prelims.json`, `appsc_group1_mains.json`), so the server no longer loads them.
- Both new files pass `clean_tree` / `count_nodes` in `backend` (229 and 441 nodes, nothing dropped).
- `tools/_si1_build/` holds the Python generator scripts (common.py, prelims.py, mains_a/b/c.py, build_mains.py, validate.py). Regenerate with `python prelims.py <repo>` and `python build_mains.py <repo>`. Safe to delete if not wanted.

## How it is structured
- Prelims Paper I: parts A-D (History and Culture 1-6; Constitution, Polity, Social Justice and IR 1-6; Indian and AP Economy and Planning 1-5 with 5(i) and 5(ii); Geography 1-4). Paper II: Part A (17 numbered aptitude items), Part B(i) (units 18-22, official numbering continues), Part B(ii) current events split into regional, national, international from the heading wording.
- Mains: Paper I sections I-III plus areas of testing and evaluation; Paper II units 1-15 in sections A-C; Paper III units 1-15 in sections A-C; Paper IV units 1-12; Paper V units 1-9; Telugu 13 question types; English 10 question types (grammar list a-n as leaves).
- Interview (75 marks) leaves are app suggestions only (DAF and bio-data, current affairs, home-state knowledge, ethics scenarios, communication). They have no coverage items.
- Marks scheme (page 5): five Mains papers 150 each, Telugu and English qualifying 150 each, Interview 75, total 825. Prelims: two papers, 120 questions / 120 marks / 120 minutes each, negative marking 1/3.
- Each unit has a coverage item whose `official` is the opening text of the unit as printed; each leaf item's `official` is the leaf title.

## Judgement calls and caveats
- Titles keep official wording. Obvious typos were fixed in leaf titles (landfornns to landforms, angels to angles, rade to Trade, Distasters to Disasters, Geotharmal/Tidel to Geothermal/Tidal, "Contribution of to GDP" to "Contribution to GDP", homopones to homophones). Unit-opening `official` strings and a few leaf titles keep the printed spelling (e.g. Tharmal, Rastrakutas, Hoyasalas, "weird symbols").
- Long lists were split at dashes/commas into several leaves or grouped in one leaf whose title contains the phrases. Numbers are estimates: est_hours 0.5-4 per leaf, importance 4-10 (AP history, AP economy, polity, economy, current events, S&T highest).
- Prelims Part A: the syllabus heading says "General Mental and Psycological Abilities" while the scheme table says "General Mental Ability, Administrative and Psychological Abilities". The outline uses the scheme wording; both are covered.
- "Science and Technology" (syllabus heading, page 22) vs "Science, Technology and Environmental Issues" (scheme table, page 5) for Paper V: outline uses the table title; both are covered.
- Prelims Paper II numbering continues 18-22 in Part B(i); kept as printed.
- Pages 1-3 hold only vacancies, eligibility and dates; no interview syllabus is printed anywhere. Page 5 gives the 75-mark interview line only.
- The PDF is scanned; a few words in dense paragraphs (e.g. unit 14 of Paper III, unit 4 of Paper V) were read from image and may differ slightly from the printed spelling. Nothing was skipped.
- The Paper I General Essay instruction (three essays, one from each section, about 800 words each) is recorded in a coverage item, not as a topic node.
