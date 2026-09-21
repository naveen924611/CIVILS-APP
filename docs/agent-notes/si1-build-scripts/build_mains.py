import sys
from common import *
from mains_a import essay, telugu, english, interview
from mains_b import paper2
from mains_c import paper3, paper4, paper5
base = sys.argv[1]
roots = [essay, paper2, paper3, paper4, paper5, telugu, english, interview]
note = ("Built from APPSC Brief Notification 07/2026 dated 15-09-2026, pages 5, 11-24. Interview node topics are the app's own suggestions, not from the notification.")
write(base + "/data/syllabus/appsc_g1_mains_2026.json", base + "/data/exam-specs/coverage/appsc-g1-mains-2026.json",
      "appsc-g1-mains-2026", "APPSC Group-I Mains 2026 - official syllabus", "appsc-group1-mains", note, roots, "5, 11-24")
