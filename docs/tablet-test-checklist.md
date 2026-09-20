# Tablet test checklist (real Lenovo Tab M10) - run at every milestone

- [ ] Install the APK; app opens; cold start under 3 s
- [ ] Log in to the HTTPS server
- [ ] Offline acceptance test (airplane mode): open app, listen to today's brief, read a note/document, revise, take a downloaded mock, scan an English page, ask a question (queued), use voice commands, 7 PM alarm fires; reconnect and confirm sync + queued answers
- [ ] Screen-off audio plays 30 min
- [ ] Alarms still fire after a reboot
- [ ] FCM test push arrives
- [ ] Headphone buttons work (single / double / triple press)
- [ ] No crash after 30 min of use (memory)
- [ ] Battery use over a normal day is acceptable

## M2 additions (briefs, audio, notifications)
- [ ] First launch after login shows "Set up this tablet": allow notifications, exact alarms, battery, offline English (India) voice (each step shows a tick when done)
- [ ] Settings > Daily briefs: change the morning time by 5 minutes, tap Save; it says "Saved"; the server log shows the job re-scheduled
- [ ] Briefs > "Prepare a brief now": after a few minutes the brief appears with items, audio and a notification (Play now / Read / Remind in 30 min)
- [ ] Notification "Play now" opens the app and starts audio; "Remind in 30 min" brings it back
- [ ] Play a brief with the screen off for 10 minutes; lock-screen controls and headphone buttons work; speed and sleep timer buttons work
- [ ] Airplane mode: the brief still opens and plays (audio was saved: header says "saved for offline")
- [ ] Alerts screen lists "Your morning brief is ready" with Play now / Read
- [ ] Reboot the tablet: brief alarms still fire (check at the next brief time)
- [ ] 3 days in a row: brief arrives at the set time with audio
