from common import *
D = "/sessions/x"
# ---------------- Paper I ----------------
hist = N("A. History and Culture", [
 unit("1. Indus Valley Civilization to the Gupta Empire", 5, 8,
  "1. Indus Valley Civilization: Features, Sites, Society, Cultural History, Art and Religion. Vedic Age-Mahajanapadas, Religions-Jainism and Buddhism.",
  [("Indus Valley Civilization: Features, Sites, Society, Cultural History, Art and Religion", 3),
   ("Vedic Age", 1.5), ("Mahajanapadas", 1), ("Religions-Jainism and Buddhism", 2),
   ("The Maghadas, the Mauryan", 2), ("Foreign invasions on India and their impact", 1.5), ("the Kushans", 1),
   ("The Sathavahanas the Sangam Age, the Sungas", 2),
   ("the Gupta Empire -their Administration- Social, Religious and Economic conditions-Art, Architecture, Literature, Science and Technology", 3)]),
 unit("2. Kanauj and the South Indian Dynasties", 6, 8,
  "2. The Kanauj and their Contributions, South Indian Dynasties - The Badami Chalukyas, the Eastern Chalukyas, the Rastrakutas, the Kalyani Chalukyas, the Cholas, the Hoyasalas, the Yedavas, the Kakatiyas and the Reddis.",
  [("The Kanauj and their Contributions", 1), ("South Indian Dynasties - The Badami Chalukyas, the Eastern Chalukyas", 2),
   ("the Rastrakutas, the Kalyani Chalukyas", 2), ("the Cholas", 1.5), ("the Hoyasalas, the Yedavas", 1.5), ("the Kakatiyas and the Reddis", 2)]),
 unit("3. Delhi Sultanate, Vijaynagar, Mughals and the Bhakti Movement", 6, 8,
  "3. The Delhi Sultanate, the Vijaynagar Empire and the Mughal Empire, the Bhakti Movement and Sufism - Administration, Economy, Society, Religion, Literature, Arts and Architecture.",
  [("The Delhi Sultanate", 2), ("the Vijaynagar Empire", 2), ("the Mughal Empire", 2.5), ("the Bhakti Movement and Sufism", 2),
   ("Administration, Economy, Society, Religion, Literature, Arts and Architecture", 2.5)]),
 unit("4. European Trading Companies and British expansion", 6, 8,
  "4. The European Trading companies in India- their struggle for supremacy-with special reference to Bengal, Bombay, Madras, Mysore, Andhra and Nizam, Governor-Generals and Viceroys.",
  [("The European Trading companies in India- their struggle for supremacy", 2),
   ("with special reference to Bengal, Bombay, Madras, Mysore, Andhra and Nizam", 2.5), ("Governor-Generals and Viceroys", 2.5)]),
 unit("5. 1857, Reform Movements and the Freedom Movement", 6, 9,
  "5. Indian War of Independence of 1857 - Origin, Nature, causes, consequences and significance with special reference to Concerned State, Religious and Social Reform Movements in 19th century in India and Concerned State, India's Freedom Movement, Revolutionaries in India and Abroad.",
  [("Indian War of Independence of 1857 - Origin, Nature, causes, consequences and significance with special reference to Concerned State", 3),
   ("Religious and Social Reform Movements in 19th century in India and Concerned State", 3),
   ("India's Freedom Movement", 3), ("Revolutionaries in India and Abroad", 2)]),
 unit("6. Gandhi, Patel, Bose, Ambedkar and Independent India", 6, 9,
  "6. Mahatma Gandhi, his thoughts, Principles and Philosophy. Important Satyagrahas, the Role of Sardar Patel, Subash Chandrabose in Freedom Movement and Post independence consolidation.",
  [("Mahatma Gandhi, his thoughts, Principles and Philosophy", 2), ("Important Satyagrahas", 2),
   ("the Role of Sardar Patel, Subash Chandrabose in Freedom Movement and Post independence consolidation", 2.5),
   ("Dr. B.R. Ambedkar, his life and contribution to making of Indian Constitution", 2.5),
   ("India after Independence - Reorganization of the States in India", 2)]),
], imp=8, page=5, extra=[("(A) HISTORY & CULTURE", 5)])

pol = N("B. Constitution, Polity, Social Justice and International Relations", [
 unit("1. Indian Constitution", 6, 10,
  "1. Indian Constitution: Evolution, features, Preamble, Fundamental Rights, Fundamental Duties, Directive Principles of State Policy, Amendments, Significant Provisions and Basic Structure.",
  [("Indian Constitution: Evolution, features, Preamble", 2.5), ("Fundamental Rights, Fundamental Duties", 3), ("Directive Principles of State Policy", 2),
   ("Amendments, Significant Provisions and Basic Structure", 3)]),
 unit("2. Union and States, Parliament and State Legislatures", 6, 10,
  "2. Functions and Responsibilities of the Union and the States, Parliament and State Legislatures: Structure, Function, Power and Privileges. Issues and challenges pertaining to Federal Structure: Devolution of Power and Finances up to local levels and Challenges therein.",
  [("Functions and Responsibilities of the Union and the States", 2.5), ("Parliament and State Legislatures: Structure, Function, Power and Privileges", 3),
   ("Issues and challenges pertaining to Federal Structure: Devolution of Power and Finances up to local levels and Challenges therein", 3)]),
 unit("3. Constitutional Authorities, Panchayati Raj, Public Policy and Governance", 7, 9,
  "3. Constitutional Authorities: Powers, Functions and Responsibilities - Panchayati Raj - Public Policy and Governance.",
  [("Constitutional Authorities: Powers, Functions and Responsibilities", 3), ("Panchayati Raj", 2), ("Public Policy and Governance", 2)]),
 unit("4. Liberalization, Privatization, Globalization and Regulatory Bodies", 7, 8,
  "4. Impact of Liberalization, Privatization and Globalization on Governance - Statutory, Regulatory and Quasi-judicial bodies.",
  [("Impact of Liberalization, Privatization and Globalization on Governance", 2), ("Statutory, Regulatory and Quasi-judicial bodies", 2)]),
 unit("5. Rights Issues", 7, 8,
  "5. Rights Issues (Human rights, Women rights, SC/ST rights, Child rights) etc.",
  [("Rights Issues (Human rights, Women rights, SC/ST rights, Child rights) etc.", 3)]),
 unit("6. India's Foreign Policy and International Relations", 7, 8,
  "6. India's Foreign Policy - International Relations - Important Institutions, Agencies and Fora, their structure and mandate - Important Policies and Programmes of Central and State Governments.",
  [("India's Foreign Policy", 2), ("International Relations", 2), ("Important Institutions, Agencies and Fora, their structure and mandate", 3),
   ("Important Policies and Programmes of Central and State Governments", 3)]),
], imp=9, page=6, extra=[("(B) CONSTITUTION, POLITY, SOCIAL JUSTICE AND INTERNATIONAL RELATIONS.", 6)])

eco = N("C. Indian and Andhra Pradesh Economy and Planning", [
 unit("1. Basic characteristics of Indian Economy", 7, 8,
  "1. Basic characteristics of Indian Economy as a developing economy - Economic development since independence objectives and achievements of planning - NITI Ayog and its approach to economic development - Growth and distributive justice - Economic development Human Development Index - India's rank in the world - Environmental degradation and challenges - Sustainable Development - Environmental Policy",
  [("Basic characteristics of Indian Economy as a developing economy", 2),
   ("Economic development since independence objectives and achievements of planning", 2),
   ("NITI Ayog and its approach to economic development", 1.5), ("Growth and distributive justice", 1.5),
   ("Economic development Human Development Index - India's rank in the world", 1.5),
   ("Environmental degradation and challenges - Sustainable Development - Environmental Policy", 2)]),
 unit("2. National Income, Poverty and Employment", 7, 9,
  "2. National Income and its concepts and components - India's National Accounts - Demographic issues - Poverty and Inequalities - Occupational Structure and Unemployment - Various Schemes of employment and poverty eradication - Issues of Rural Development and Urban Development.",
  [("National Income and its concepts and components - India's National Accounts", 2.5), ("Demographic issues", 1.5), ("Poverty and Inequalities", 2),
   ("Occupational Structure and Unemployment", 2), ("Various Schemes of employment and poverty eradication", 2),
   ("Issues of Rural Development and Urban Development", 2)]),
 unit("3. Indian Agriculture, Industry and Economic Reforms", 7, 9,
  "3. Indian Agriculture - Irrigation and water - Inputs of agriculture - Agricultural Strategy and Agricultural Policy - Agrarian Crisis and land reforms - Agricultural credit - Minimum Support Prices - Malnutrition and Food Security - Indian Industry - Industrial Policy - Make-in India - Start-up and Stand-up programmes - SEZs and Industrial Corridors - Energy and Power policies - Economic Reforms - Liberalisaion, Privatisation and Globalization - International Trade and Balance of Payments - India and WTO",
  [("Indian Agriculture - Irrigation and water - Inputs of agriculture", 2), ("Agricultural Strategy and Agricultural Policy", 1.5),
   ("Agrarian Crisis and land reforms - Agricultural credit", 2), ("Minimum Support Prices - Malnutrition and Food Security", 2),
   ("Indian Industry - Industrial Policy - Make-in India", 2), ("Start-up and Stand-up programmes - SEZs and Industrial Corridors", 1.5),
   ("Energy and Power policies", 1.5), ("Economic Reforms - Liberalisaion, Privatisation and Globalization", 2),
   ("International Trade and Balance of Payments - India and WTO", 2.5)]),
 unit("4. Financial Institutions, Taxation and Public Finance", 7, 9,
  "4. Financial Institutions - RBI and Monetary Policy - Banking and Financial Sector Reforms - Commercial Banks and NPAs - Financial Markets - Instabilities - Stock Exchanges and SEBI - Indian Tax System and Recent changes - GST and its impact on Commerce and Industry - Centre, States financial relations - Financial Commissions - Sharing of resources and devolution - Public Debt and Public Expenditure - Fiscal Policy and Budget",
  [("Financial Institutions - RBI and Monetary Policy", 2.5), ("Banking and Financial Sector Reforms - Commercial Banks and NPAs", 2.5),
   ("Financial Markets - Instabilities - Stock Exchanges and SEBI", 2), ("Indian Tax System and Recent changes - GST and its impact on Commerce and Industry", 2.5),
   ("Centre, States financial relations - Financial Commissions - Sharing of resources and devolution", 2.5),
   ("Public Debt and Public Expenditure - Fiscal Policy and Budget", 2.5)], ),
 unit("5. Andhra Pradesh Economy after bifurcation", 8, 10,
  "5. i) The characteristics/ basic features of Andhra Pradesh economy after bifurcation in 2014",
  [("The characteristics/ basic features of Andhra Pradesh economy after bifurcation in 2014", 2.5, 7),
   ("Impact of bifurcation on the endowment of natural resources and state revenue", 2, 7),
   ("disputes of river water sharing and their impact on irrigation", 2, 7),
   ("new challenges to industry and commerce", 1.5, 7),
   ("the new initiatives to develop infrastructure - power and transport - information technology and e-governance", 2.5, 7),
   ("Approaches to development and initiatives in agriculture, industry and social sector", 2.5, 7),
   ("Urbanisation and smart cities - Skill development and employment - social welfare programmes", 2.5, 7),
   ("ii) A.P. Reorganisation Act, 2014 - Economic Issues arising out of bifurcation", 2.5, 8),
   ("Central government's assistance for building a new capital, compensation for loss of revenue, development of backward districts", 2.5, 8),
   ("Issues such as Vizag railway zone, Kadapa steel factory, Dugarajapatnam airport, Express ways and industrial corridors etc.", 2.5, 8),
   ("Special Status and Special Assistance- Controversy - Government's stand and measure", 3, 8)]),
], imp=9, page=7, extra=[("(C) INDIAN AND ANDHRA PRADESH ECONOMY AND PLANNING", 7)])
# unit 5 leaves default imp is 10 from unit; the per-leaf importance tuple slot is not used by unit(), so set here
for u in eco.kids:
    if u.title.startswith("5."):
        u.imp = 10
        for k in u.kids: k.imp = 10
    if u.title.startswith("4."):
        for l in u.kids: pass
# fix pages for spill of unit 4 (last leaves on page 8)
for l in eco.kids[3].kids[-2:]:
    l.page = 8
for l in eco.kids[4].kids: l.page = 8

geo = N("D. Geography", [
 unit("1. General Geography", 8, 8,
  "1. General Geography: Earth in Solar system, Motion of the Earth, Concept of time, Season, Internal Structure of the Earth, Major landforms and their features. Atmosphere-structure and composition, elements and factors of Climate, Airmasses and Fronts, atmospheric disturbances, climate change. Oceans: Physical, chemical and biological characteristics, Hydrological Distasters, Marine and Continental resources.",
  [("Earth in Solar system, Motion of the Earth, Concept of time, Season", 2.5), ("Internal Structure of the Earth, Major landforms and their features", 2.5),
   ("Atmosphere-structure and composition, elements and factors of Climate", 2.5), ("Airmasses and Fronts, atmospheric disturbances, climate change", 2.5),
   ("Oceans: Physical, chemical and biological characteristics", 2), ("Hydrological Disasters, Marine and Continental resources", 2)]),
 unit("2. Physical Geography of World, India and the State", 8, 9,
  "2. Physical: World, India and concerned State : Major physical divisions, Earthquakes, landslides, Natural drainage, climatic changes and regions, Monsoon, Natural Vegetation, Parks and Sanctuaries, Major Soil types, Rocks and Minerals.",
  [("Major physical divisions", 2), ("Earthquakes, landslides", 1.5), ("Natural drainage", 2), ("climatic changes and regions, Monsoon", 2.5),
   ("Natural Vegetation, Parks and Sanctuaries", 2), ("Major Soil types, Rocks and Minerals", 2)]),
 unit("3. Social Geography of World, India and the State", 8, 8,
  "3. Social: World, India and concerned State : distribution, density, growth, Sex-ratio, Literacy, Occupational Structure, SC and ST Population, Rural-Urban components, Racial, tribal, religious and linguistic groups, urbanization, migration and metropolitan regions.",
  [("distribution, density, growth, Sex-ratio, Literacy", 2), ("Occupational Structure, SC and ST Population, Rural-Urban components", 2),
   ("Racial, tribal, religious and linguistic groups", 1.5), ("urbanization, migration and metropolitan regions", 1.5)]),
 unit("4. Economic Geography of World, India and the State", 9, 8,
  "4. Economic: World, India and concerned State: Major sectors of economy, Agriculture, Industry and Services, their salient features. Basic Industries-Agro, mineral, forest, fuel and manpower based Industries, Transport and Trade, Pattern and Issues.",
  [("Major sectors of economy, Agriculture, Industry and Services, their salient features", 2.5),
   ("Basic Industries-Agro, mineral, forest, fuel and manpower based Industries", 2.5), ("Transport and Trade, Pattern and Issues", 2)]),
], imp=8, page=8, extra=[("(D) GEOGRAPHY", 8)])

p1 = N("Paper I - General Studies (120 marks)", [hist, pol, eco, geo], imp=9, page=4,
       extra=[("Screening Test (Objective Type) Paper -I General Studies. This paper consists of 04 parts i.e., ABCD each part carries 30 marks.", 4),
              ("PAPER -I GENERAL STUDIES (DEGREE STANDARD)", 5)])

# ---------------- Paper II ----------------
def item(title, page, imp, off, leaves=None, h=1.5):
    if leaves:
        return unit(title, page, imp, off, leaves, h)
    return leaf(title, h, imp, page, off)

A = N("A. General Mental Ability, Administrative and Psychological Abilities", [
 leaf("Logical Reasoning and Analytical Ability", 3, 8, 9),
 item("Number Series, Coding-Decoding", 9, 8, "Number Series, Coding —Decoding.", [("Number Series", 1.5), ("Coding-Decoding", 1.5)]),
 leaf("Problems Related to Relations", 1.5, 7, 9),
 item("Shapes and their Sub-sections, Venn Diagram", 9, 7, "Shapes and their Sub-sections, Venn Diagram.", [("Shapes and their Sub-sections", 1.5), ("Venn Diagram", 1.5)]),
 item("Problems based on Clocks, Calendar and Age", 9, 7, "Problems based on Clocks, Calendar and Age.", [("Clocks", 1.5), ("Calendar", 1.5), ("Age", 1.5)]),
 item("Number system and order of Magnitude", 9, 7, "Number system and order of Magnitude.", [("Number system", 1.5), ("order of Magnitude", 1)]),
 leaf("Ratio, proportion and variation", 2, 7, 9),
 item("Central Tendencies - mean, median, mode - including weighted mean", 9, 7, "Central Tendencies - mean, median, mode — including weighted mean.", [("Central Tendencies - mean, median, mode", 2), ("including weighted mean", 1)]),
 item("Power and exponent, Square, Square Root, Cube Root, H.C.F. and L.C.M.", 9, 7, "Power and exponent, Square, Square Root, Cube Root, H.C.F. and L.C.M.", [("Power and exponent", 1), ("Square, Square Root, Cube Root", 1.5), ("H.C.F. and L.C.M.", 1.5)]),
 item("Percentage, Simple and Compound Interest, Profit and loss", 9, 8, "Percentage, Simple and Compound Interest, Profit and loss.", [("Percentage", 1.5), ("Simple and Compound Interest", 2), ("Profit and loss", 2)]),
 item("Time and Work, Time and Distance, Speed and Distance", 9, 8, "Time and Work, Time and Distance, Speed and Distance.", [("Time and Work", 2), ("Time and Distance", 2), ("Speed and Distance", 2)]),
 item("Area and Perimeter of Simple Geometrical Shapes, Volume and Surface Area", 9, 6, "Area and Perimeter of Simple Geometrical Shapes, Volume and Surface Area of Sphere, Cone, Cylinder, cubes and Cuboids.",
      [("Area and Perimeter of Simple Geometrical Shapes", 2), ("Volume and Surface Area of Sphere, Cone, Cylinder, cubes and Cuboids", 2.5)]),
 item("Lines, angles and common geometrical figures", 9, 6, "Lines, angels and common geometrical figures — properties of transverse and parallel lines, properties of triangles, quadrilateral, rectangle, parallelogram and rhombus.",
      [("Lines, angles and common geometrical figures", 1.5), ("properties of transverse and parallel lines", 1.5), ("properties of triangles, quadrilateral, rectangle, parallelogram and rhombus", 2)]),
 item("Introduction to algebra, BODMAS, simplification of weird symbols", 9, 6, "Introduction to algebra — BODMAS, simplification of weird symbols.",
      [("Introduction to algebra", 1.5), ("BODMAS", 1), ("simplification of weird symbols", 1)]),
 item("Data interpretation, Data Analysis, Data sufficiency and Probability", 9, 8, "Data interpretation, Data Analysis, Data sufficiency, and concepts of Probability.",
      [("Data interpretation", 3), ("Data Analysis", 2), ("Data sufficiency", 2), ("concepts of Probability", 2)]),
 item("Emotional Intelligence", 9, 6, "Emotional Intelligence: Understanding and analyzing emotions, Dimensions of emotional intelligence, coping with emotions, empathy and coping with stress.",
      [("Emotional Intelligence: Understanding and analyzing emotions", 1.5), ("Dimensions of emotional intelligence", 1), ("coping with emotions, empathy and coping with stress", 1.5)]),
 item("Social Intelligence, interpersonal skills and decision making", 10, 6, "Social Intelligence, interpersonal skills, Decision making, Critical thinking, problem solving and Assessment of personality.",
      [("Social Intelligence, interpersonal skills", 1.5), ("Decision making", 1), ("Critical thinking, problem solving", 1.5), ("Assessment of personality", 1)]),
], imp=7, page=9, extra=[("(A). GENERAL MENTAL AND PSYCOLOGICAL ABILITIES", 9),
                         ("General Mental Ability, Administrative and Psychological Abilities.", 4)])
# leaf() args: (t,h,imp,p) -> above leaf("Logical...", 3, 8, 9) matches
# item() for multi-leaf uses unit(title,page,imp,off,leaves)

B1 = N("B(i). Science and Technology", [
 unit("18. Science and Technology: nature, scope and institutions", 10, 8,
  "18. Science and Technology: Nature and Scope of Science & Technology; Relevance of Science & Technology to the day to day life; National Policy on Science, Technology and Innovation; Institutes and Organization in India promoting integration of Science, Technology and Innovation, their activities and contribution; Contribution of Prominent Indian Scientists.",
  [("Nature and Scope of Science & Technology", 1.5), ("Relevance of Science & Technology to the day to day life", 1.5), ("National Policy on Science, Technology and Innovation", 1.5),
   ("Institutes and Organization in India promoting integration of Science, Technology and Innovation, their activities and contribution", 2), ("Contribution of Prominent Indian Scientists", 2)]),
 unit("19. Information and Communication Technology (ICT)", 10, 8,
  "19. Information and Communication Technology (ICT): Nature and Scope of ICT; ICT in day to day life; ICT and Industry; ICT and Governance - Various government schemes promoting use of ICT, E-Governance programmes and services; Netiquettes; Cyber Security Concerns - National Cyber Crime Policy.",
  [("Nature and Scope of ICT", 1), ("ICT in day to day life; ICT and Industry", 1.5), ("ICT and Governance - Various government schemes promoting use of ICT, E-Governance programmes and services", 2),
   ("Netiquettes", 0.5), ("Cyber Security Concerns - National Cyber Crime Policy", 1.5)]),
 unit("20. Technology in Space and Defence", 10, 8,
  "20. Technology in Space & Defence: Evolution of Indian Space Programme; Indian Space Research Organization (ISRO) — it's activities and achievements; Various Satellite Programmes — Satellites for Telecommunication, Indian Regional Navigation Satellite System (IRNSS), Indian Remote Sensing (IRS) Satellites; Satellites for defence, Eduset or Satellites for academic purposes; Defence Research and Development Organization (DRDO)- vision, mission and activities.",
  [("Evolution of Indian Space Programme", 1.5), ("Indian Space Research Organization (ISRO) — it's activities and achievements", 2),
   ("Various Satellite Programmes — Satellites for Telecommunication, Indian Regional Navigation Satellite System (IRNSS), Indian Remote Sensing (IRS) Satellites", 2),
   ("Satellites for defence, Eduset or Satellites for academic purposes", 1), ("Defence Research and Development Organization (DRDO)- vision, mission and activities", 1.5)]),
 unit("21. Energy Requirement and Efficiency", 10, 8,
  "21. Energy Requirement and Efficiency: India's existing energy needs and deficit; India's Energy Resources and Dependence, Energy policy of India Government Policies and Programmes. Solar, Wind and Nuclear energy",
  [("India's existing energy needs and deficit", 1), ("India's Energy Resources and Dependence", 1.5), ("Energy policy of India Government Policies and Programmes", 1.5), ("Solar, Wind and Nuclear energy", 2)]),
 unit("22. Environmental Science and Biotechnology", 10, 9,
  "22. Environmental Science: Issues and concerns related to environment; Its legal aspects, policies and treaties for the protection of environment at the national and the international level; Biodiversity- its importance and concerns; Climate Change, International Initiatives (Policies, Protocols) and India's commitment; Forest and Wildlife - Legal framework for Forest and Wildlife Conservation in India; Environmental Hazards, pollution, carbon emission, Global warming. National Action plans on Climate Change and Disaster management. Biotechnology and Nanotechnology; Nature, Scope and application, Ethical, Social, and Legal issues, Government Policies. Genetic Engineering; Issues related to it and its impact on human life. Health & Environment.",
  [("Issues and concerns related to environment", 1), ("Its legal aspects, policies and treaties for the protection of environment at the national and the international level", 2),
   ("Biodiversity- its importance and concerns", 1.5), ("Climate Change, International Initiatives (Policies, Protocols) and India's commitment", 2),
   ("Forest and Wildlife - Legal framework for Forest and Wildlife Conservation in India", 2), ("Environmental Hazards, pollution, carbon emission, Global warming", 2),
   ("National Action plans on Climate Change and Disaster management", 1.5), ("Biotechnology and Nanotechnology; Nature, Scope and application, Ethical, Social, and Legal issues, Government Policies", 2.5),
   ("Genetic Engineering; Issues related to it and its impact on human life", 2, 11), ("Health & Environment", 1, 11)]),
], imp=8, page=10, extra=[("(B)(i) SCIENCE AND TECHNOLOGY", 10), ("(i) Science and Technologies.", 4)])

B2 = N("B(ii). Current Events of Regional, National and International Importance", [
  leaf("Current events of Regional importance", 4, 10, 11),
  leaf("Current events of National importance", 4, 10, 11),
  leaf("Current events of International importance", 4, 10, 11),
], imp=10, page=11, extra=[("(ii) CURRENT EVENTS OF REGIONAL, NATIONAL AND INTERNATIONAL IMPORTANCE", 11)])

p2 = N("Paper II - General Aptitude (120 marks)", [A, B1, B2], imp=8, page=4,
       extra=[("Screening Test (Objective Type) Paper -II General Aptitude.", 4), ("PAPER -II — GENERAL APTITUDE (DEGREE STANDARD)", 9)])

roots = [p1, p2]
if __name__ == "__main__":
    import sys
    base = sys.argv[1]
    note = "Built from APPSC Brief Notification 07/2026 dated 15-09-2026, pages 4-11."
    write(base + "/data/syllabus/appsc_g1_prelims_2026.json", base + "/data/exam-specs/coverage/appsc-g1-prelims-2026.json",
          "appsc-g1-prelims-2026", "APPSC Group-I Screening Test (Prelims) 2026 - official syllabus", "appsc-group1-prelims", note, roots, "4-11")
