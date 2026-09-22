# Daily current-affairs YouTube channels (reference list)

Found 2026-09-22 by web search, for use with the Videos feature (paste a link there to get it filed under a topic
and, when YOUTUBE_API_KEY is set, an AI "what to listen for" summary). The app never downloads or stores video
files, only the link, title, channel and your own notes - so use these the same way: open the channel, paste the
day's video link into Videos.

| Channel | Language | Fits | Note |
|---|---|---|---|
| [Hareesh Academy](https://www.youtube.com/@HareeshAcademy) ("HAREESH THE BEST ACADEMY") | Telugu | SI (Civil), APPSC Group-I/II | Posts a "Daily Current Affairs in Telugu" video every day, explicitly tagged APPSC / TSPSC / Group-2 / SI. Closest match to what you described (like Unacademy's daily reading), and in your exam's own language. |
| [Unacademy - APPSC and TSPSC Live](https://unacademy.com/goal/appsc-and-tspsc-live/QKHZF/free-platform/current-affairs/QQEGJ) / [Unacademy Current Affairs](https://www.youtube.com/channel/UCeiuJ9Y2pa53WvGnl4RZ8kQ) | English/Telugu mix | APPSC, Group-I | The Unacademy current-affairs stream you already know; has a dedicated APPSC/TSPSC track. |
| [StudyIQ IAS - Daily Current Affairs](https://www.youtube.com/playlist?list=PLpuxPG4TUOR7q-0k3RIqpGXDzPACvMs5E) | English/Hindi | UPSC | One of the most consistent daily UPSC current-affairs shows; good second source to cross-check against the app's briefs. |
| [The Hindu Newspaper Analysis LIVE for UPSC](https://www.youtube.com/playlist?list=PLJxZBt-LTVo-j3UilMmrcTBxY_U-XS6Ti) | English | UPSC, Group-I (The Hindu is already one of the app's own RSS sources) | Daily video walkthrough of the same paper the app's briefs are built from - useful to hear it read aloud and compare. |
| [Sarat Chandra IAS Academy - Weekly Current Affairs](https://www.youtube.com/watch?v=DFCkLPwBK-w) | English/Telugu | UPSC, APPSC, TSPSC | Weekly roundup rather than daily; good for a Sunday recap. |

## How this compares to what the app already does
The app's briefs are not a video reading: they are built from real RSS feeds (The Hindu, Indian Express, PIB, RBI
and others, listed in `data/feeds.yaml`), each item is summarised by the AI from the actual fetched article text
only (title, source and date are passed to it; it is not free-writing from nothing), and every brief item keeps its
source link so you can open the original and check it. A YouTube "daily reading" video is a second, independent
read of the same kind of news - a good cross-check, not a replacement.

## Not done automatically
The app does not (and per its rules never will) download, transcribe or auto-summarise these videos. To use one:
open Videos in the app, paste that day's video link, and it will file it under a topic; the "what to listen for"
line only appears if a YouTube Data API key is set (`YOUTUBE_API_KEY` in `.env`).
