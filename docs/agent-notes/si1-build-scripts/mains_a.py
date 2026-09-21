from common import *

# ---------- Qualifying: Telugu ----------
def q(t, h, p, off, imp=4):
    return leaf(t, h, imp, p, off)
telugu = N("Telugu (qualifying, SSC standard, 150)", [
 q("Essay (200-250 words, one topic from three: descriptive, analytical, philosophical, current affairs) - 20 marks", 3, 13, "ESSAY (A minimum of 200 words and a maximum of 250 words): Choose any one topic from a list of three. (Descriptive/Analytical/ Philosophical/ based on Current Affairs)"),
 q("Elaborate the meaning of a poem or verse, any two of the three (about 100 words) - Vemana and Sumathi Satakam - 20 marks", 3, 13, "To ELABORATE the meaning of a poem or verse (any two of the three) (about 100 words)- (Vemana and Sumathi Satakam)"),
 q("Precis writing: 1/3rd summary of the given passage in your own words - 10 marks", 2, 13, "PRECIS WRITING: 1/3rd summary of the given passage in your words"),
 q("Comprehension: reading passage of about 250 words with five short-answer questions - 10 marks", 2, 13, "COMPREHENSION: A reading passage of about 250 words to be given followed by five short-answer type questions."),
 q("Formal speech: Welcome, Farewell, Inauguration etc. / speech for the press conference (about 150 words) - 10 marks", 2, 13, "Write a FORMAL SPEECH (Welcome, Farewell, Inauguration etc.)/Speech for the press conference (in about 150 words)"),
 q("Prepare the statements for publicity media (Press-note) (about 100 words) - 10 marks", 1.5, 13, "To PREPARE THE STATEMENTS for publicity media (Press-note) (in about 100 words)"),
 q("Letter writing: Congratulation, Best wishes, Request, Complaint etc. (about 100 words) - 10 marks", 1.5, 13, "LETTER WRITING (in about 100 words)- (Congratulation/Best wishes/Request / Complaint etc.)"),
 q("Application writing (about 150 words) - 10 marks", 1.5, 13, "APPLICATION WRITING (in about 150 words)"),
 q("Report writing (about 150 words) - 10 marks", 1.5, 13, "REPORT WRITING (in about 150 words)"),
 q("Translation from English to Telugu language - 10 marks", 2, 13, "TRANSLATION: Translation from English to Telugu Language"),
 q("Translation from Telugu to English language - 10 marks", 2, 13, "TRANSLATION: Translation from Telugu to English Language"),
 q("Word Meanings: English to Telugu and Telugu to English - 10 marks", 2, 13, "Word Meanings: English to Telugu and Telugu to English"),
 q("Synonyms and Antonyms: English to Telugu and Telugu to English - 10 marks", 2, 13, "Synonyms and Antonyms: English to Telugu and Telugu to English"),
], imp=4, page=13, extra=[("Paper in Telugu - Qualifying Nature - 180 minutes - 150 Marks", 5), ("TELUGU (S.S.C STANDARD) Marks-150 Medium: Telugu Time- 180 Minutes", 13)])

# ---------- Qualifying: English ----------
def g(t, h=1):
    return leaf(t, h, 4, 12)
english = N("English (qualifying, SSC standard, 150)", [
 q("Essay (200-250 words, one topic from five: descriptive, analytical, philosophical, current affairs) - 20 marks", 3, 11, "ESSAY (A minimum of 200 words and a maximum of 250 words):Choose any one topic from a list of five. (Descriptive/ analytical/ philosophical/ based on Current Affairs)"),
 q("Letter writing (about 100 words): formal letter expressing one's opinion about an issue - 10 marks", 1.5, 11, "LETTER WRITING (in about 100 words): A formal letter expressing one's opinion about an issue. The issues can deal with daily office matters/ a problem that has occurred in the office/ an opinion in response to one sought by a ranked officer etc."),
 q("Press release / appeal (about 100 words) on a recent concern, problem, disaster or rumours - 10 marks", 1.5, 11, "PRESS RELEASE/ APPEAL (in about 100 words): The PR or appeal should be on an issue pertaining to a recent concern/problem/disaster/rumours etc."),
 q("Report writing (about 150 words): official function, event, field trip, survey etc. - 15 marks", 2, 11, "REPORT WRITING (in about 150 words): A report on an official function/event/field trip/survey etc."),
 q("Writing on visual information (about 150 words): graph, image, flow chart, table of comparison, simple statistical data - 15 marks", 2, 11, "WRITING ON VISUAL INFORMATION (in about 150 words): A report on a graph/image/ flow chart/table of comparison/ simple statistical data etc."),
 q("Formal speech (about 150 words): inauguration speech, educational seminar or conference, formal ceremony - 15 marks", 2, 12, "FORMAL SPEECH (in about 150 words): A speech (in a formal style) that is to be read out in a formal function. This could be an inauguration speech, an educational seminar/conference, a formal ceremony of importance etc."),
 q("Precis writing: about 100 words for a 300-word passage - 15 marks", 2, 12, "PRECIS WRITING: A precis in about 100 words for a 300-word passage."),
 q("Reading comprehension: passage of about 250 words with short-answer questions - 15 marks", 2, 12, "READING COMPREHENSION: A reading passage of about 250 words to be given followed by short-answer type questions."),
 N("English grammar: multiple choice questions - 20 marks", [
   g("Tenses"), g("Voice"), g("Narration (Direct-Indirect)"), g("Transformation of sentences", 1.5),
   g("Use of Articles and Determiners"), g("Use of Prepositions"), g("Use of Phrasal verbs"), g("Use of idiomatic expressions"),
   g("Administrative Glossary"), g("Synonyms/Antonyms"), g("One-word substitution"), g("Cohesive devices/Connectives/Linkers"),
   g("Affixes"), g("Words that cause confusion like homonyms/homophones")],
   imp=4, page=12, off="ENGLISH GRAMMAR: Multiple choice questions set from the following list:"),
 q("Translation of a short passage (about 150 words) from regional language to English - 15 marks", 2, 12, "TRANSLATION: Translation of a short passage (of about 150 words) from Regional Language to English."),
], imp=4, page=11, extra=[("Paper in English - Qualifying Nature - 180 minutes - 150 Marks", 5), ("SYLLABUS FOR GROUP-I MAINS EXAMINATION ENGLISH (S.S.C STANDARD) Marks — 150 Medium: English Time- 180 Minutes", 11)])

# ---------- Paper I Essay ----------
def e(t, off=None, h=2):
    return leaf(t, h, 7, 14, off)
essay = N("Paper I - General Essay (150)", [
 N("Section I - Current affairs", [e("Current affairs", "Section –I i.Current affairs", 3)], imp=8, page=14),
 N("Section II - Socio-political, socio-economic and socio-environmental issues", [
    e("Socio- political issues", "(i) Socio- political issues"), e("Socio- economic issues", "(ii)Socio- economic issues"), e("Socio- environmental issues", "(iii)Socio- environmental issues")], imp=8, page=14, off="Section – II"),
 N("Section III - Cultural, civic and reflective topics", [
    e("Cultural and historical aspects", "(i) Cultural and historical aspects"), e("Issues related to civic awareness", "(ii) Issues related to civic awareness"), e("Reflective topics", "(iii) Reflective topics")], imp=7, page=14, off="Section – III"),
 N("Areas of testing", [
    e("Ability to compose a well-argued piece of writing", None, 1.5), e("Ability to express coherently and sequentially", None, 1.5), e("Awareness of the subject chosen", None, 1)], imp=7, page=14, off="Areas of Testing: This paper would test the following:"),
 N("Evaluation and marking", [
    e("Observing established rules and format for essay writing", None, 1.5), e("Grammatical correctness of expression", None, 1), e("Originality of thought and expression", None, 1)], imp=7, page=14, off="Evaluation / Marking: Credit will be given for the following:"),
], imp=7, page=14, extra=[("Paper - I General Essay - on contemporary themes and issues of regional, national and international importance - 180 minutes - 150 Marks", 5),
   ("PAPER-I - GENERAL ESSSAY (DEGREE STANDARD) Marks - 150 Medium: English/Telugu Time- 180 Minutes", 14),
   ("The candidates are required to attempt three essays, one from each of the three sections, in about 800 words each.", 14),
   ("This paper is designed to test candidate's (i) knowledge / awareness of a variety of Subjects and (ii) their ability to compose a sustained piece of writing in the form of an essay.", 14)])

interview = N("Interview (75 marks)", [
  leaf("DAF and bio-data questions", 3, 6, 5), leaf("Current affairs", 4, 7, 5), leaf("Home-state knowledge (Andhra Pradesh)", 4, 7, 5),
  leaf("Ethics scenarios", 3, 6, 5), leaf("Communication", 2, 6, 5)], imp=6, page=5,
  extra=[("INTERVIEW - 75 Marks", 5), ("TOTAL MARKS 825 Marks", 5)])
# interview leaf coverage: the leaves are app suggestions, not official; handled in build (marked)
for l in interview.kids:
    l.suggest = True
