# Device test checklist

CI covers the domain, the database, the server, the sync loop and most UI with
JVM, Robolectric and emulator tests. This checklist covers what CI cannot
prove: real alarms, the lock screen, reboots, launchers, other apps, and the
AI connectors. Run it on a real phone before each release, ideally on two
Android versions (one 13 or 14, one 15+). Note the device, Android version and
build next to each section.

Setup: install the debug APK from the CI run (or `./gradlew :app:installDebug`).
For sync and AI, you need a server reachable over HTTPS (README, "Self-hosting").

## 1. First launch and core loop (offline)
- [ ] Airplane mode on. Fresh install opens onboarding ("Empty your head").
- [ ] Brain-dump 6 items. Rotate the phone mid-way: nothing typed is lost.
- [ ] Pick up to the cap, give each a first step, finish. The rest are in Later.
- [ ] Today shows each tracked quest with exactly one next step.
- [ ] Tap **+**, type, press Enter: saved to the inbox in under 2 s, and the
      capture panel closes at once (D-201). Repeat quickly 5 times: it never
      stays open or saves twice.
- [ ] Capture panel with gesture navigation and with 3-button navigation: the
      keyboard never covers the field, and the panel sits above the
      navigation bar. Back and a tap outside close it.
- [ ] Done on a step: haptic, "+15 XP", then "What's next?". Set it; Today
      updates. The whole loop from capture to next step takes under 30 s.
- [ ] "Not yet — keep the step open" shows "no XP yet", and no XP is added.
- [ ] On "What's next?", Back and a tap outside do nothing; only its buttons
      close it.

## 2. Notifications and exact alarms
- [ ] Set a step reminder 2 minutes ahead. The first time, Android asks for
      notification permission. Allow it.
- [ ] The reminder arrives on time (±1 min) with **Done / Snooze 1h / Skip**.
- [ ] **Done** completes the step without asking. The quest shows "Needs next step".
- [ ] **Snooze 1h** brings it back an hour later. **Skip** dismisses it and the
      step stays current.
- [ ] Set an **alarm** step 2 minutes ahead, lock the phone. At the exact
      minute the full-screen alarm shows over the lock screen and keeps
      sounding until you act.
- [ ] Settings → Apps → special access: revoke "Alarms & reminders". The app's
      Settings explains it, and reminders still arrive (possibly a few minutes late).
- [ ] Android 14+: revoke full-screen notifications. The alarm comes as a
      heads-up notification instead, and Settings says so.
- [ ] Complete a step in the app while its reminder notification is showing:
      the notification disappears.
- [ ] Morning digest and streak guard arrive at their set times. The guard
      stays silent if a step was completed today.

## 3. Reboot, time and updates
- [ ] Set a reminder 5 minutes ahead, reboot the phone, don't open the app.
      The reminder still fires.
- [ ] Set a reminder, power off past its time, power on: it fires once, not
      repeatedly.
- [ ] Change the time zone: due times and today's date shift correctly, and
      streak days don't jump.
- [ ] Install a newer build over the old one: reminders are still armed.

## 4. Widget
- [ ] Add the home-screen widget. It shows today's tracked quests and steps.
- [ ] Complete a step in the app: the widget updates within a few seconds.
- [ ] Tap ✓ on the widget. The "What's next?" dialog opens over the home
      screen; set it, and the widget updates.
- [ ] Tap ✓ on a step already completed elsewhere: the dialog says so and closes.
- [ ] The widget's + opens capture.

## 5. Share target and links
- [ ] Share text from a browser or notes app to "Capture": capture opens
      prefilled, and saving lands it in the inbox.
- [ ] Share styled text (e.g. from Google Docs): the text arrives.

## 6. Vault mirror and backups
- [ ] Settings → Vault: pick a folder (e.g. an Obsidian vault). `Quests/` gets
      one note per quest, and `Quests/.backups/` gets today's snapshot.
- [ ] Put your own `Quests/<same title as a quest>.md` there first: it is
      **not** overwritten; the app writes a suffixed note instead.
- [ ] Duplicate one of the app's notes in Obsidian: the copy survives the
      next mirror.
- [ ] Export a snapshot, clear app data, import it: everything is back
      (quests, XP, streak, achievements).
- [ ] Import the same file again: "already here", nothing duplicated.

## 7. Sync and pairing
- [ ] Server page → Pair a device. Scan the QR code with the camera app: the
      app opens Settings with the address prefilled and **asks to confirm**.
      It does not pair by itself.
- [ ] Pair. Capture on the phone: it appears on the server (check via MCP
      `get_today` or a second device) within about 10 s.
- [ ] Second phone: pair and see the same quests, XP and streak.
- [ ] Airplane mode: make 3 changes. Settings shows "3 change(s) waiting to
      upload". Turn the network on: they upload without opening the app.
- [ ] Remove the device on the server page. The app says it was removed and
      asks to pair again. No data is lost on the phone.
- [ ] Restore the phone from an Android backup (new phone or reset): the app
      shows sync as not paired and asks to pair again (the token is never
      backed up). Pairing again uploads the log.
- [ ] Stop the server, replace its data volume with an older backup, start it,
      and make changes on a second phone. Then sync the first phone: its
      quests reappear on the server and on the second phone.

## 8. Claude and ChatGPT connectors
- [ ] Claude → Settings → Connectors → Add custom connector →
      `https://your-server/mcp`. The consent page shows the connector's name
      and redirect host. Approve with the admin password.
- [ ] Ask Claude "what's on my plate today?": it lists the tracked quests and
      their next steps.
- [ ] Ask Claude to add a quest and track it. Claude says it's a proposal.
      Nothing changes on the phone until sync, then it appears under
      Proposals with Claude's reason.
- [ ] Accept: XP and achievement toasts show, and the quest is tracked with
      its step. Ask Claude again: it sees the accepted quest.
- [ ] Reject another proposal: Claude's `list_proposals` shows it rejected.
- [ ] Repeat with ChatGPT (Settings → Connectors → Create, developer mode).
- [ ] Server page → Connections: revoke Claude. Its next call is refused.

## 9. Accessibility and themes
- [ ] TalkBack: every button on Today, Board, Quest, Calendar, Proposals and
      Settings is announced with a meaningful label.
- [ ] Font size at maximum: nothing is clipped beyond use. The capture field
      and Done stay reachable.
- [ ] Dark and light themes: text is readable everywhere. Lime is only used
      on primary buttons.

## 10. v1.1 — updates, alarms, rotation, LAN
- [ ] **Signing** (with the `DEBUG_KEYSTORE_B64` secret set, docs/RELEASING.md):
      install CI debug APK N, capture a few quests, then install CI APK N+1 from a
      later run directly over it (no uninstall). It installs as an update and every
      quest, XP and streak is still there. The CI job summary says "stable debug key".
- [ ] Without the secret, the CI job summary shows the "throwaway key" warning.
- [ ] **Alarm Back:** let an alarm step ring on the full-screen alarm, press Back.
      The ringing stops at once; a silent reminder with Done / Snooze 1h / Skip stays
      in the shade, and each of its actions works.
- [ ] **Rotation:** open capture, type, rotate: the panel is open with the text.
      Same for "What's next?", Track → "First step", and the swap dialog (all slots
      full). Repeat with "Don't keep activities" on (developer options) and switch
      apps instead of rotating: everything comes back.
- [ ] **LAN pairing (debug build):** run the server on a laptop with
      `PUBLIC_URL=http://<laptop-ip>:8080`, pair the phone with
      `http://<laptop-ip>:8080`: it pairs and syncs. Settings → Sync says plain http
      is allowed (debug build). A release build refuses the http:// address.
- [ ] **Restore gap:** sync, turn on airplane mode right after a capture's upload
      starts (or stop the server mid-sync), restore the server's data volume from a
      backup taken before that capture, start it, sync: the capture reaches the server.

## 11. v1.1 — practices, undo, focus, reset, coming back
- [ ] **Practice:** capture "Singing" with **Practice** on, pick Mon / Wed / Fri; track
      it with "20 min scales". It uses a slot. On a session day the card shows
      "This week 0/3" and Done; Done logs +15 XP and the card rests ("Session logged
      for today. Next session …"). No "What's next?" sheet appears.
- [ ] **Practice reminders on weekdays:** give the practice a reminder 2 minutes
      ahead (Quest detail → ⋮ → Change rhythm → Reminder) on a session day: it
      arrives with Done / Snooze 1h / Skip. Skip, then check the next session day:
      the reminder comes back. On a non-session day nothing fires.
- [ ] Leave the practice alone for a week: no backlog, no red, no count of missed
      sessions; only "This week 0/3" resets on Monday.
- [ ] **Undo in the app:** Done on a step → "What's next?" → set it. An Undo bar
      shows for 10 s; Undo restores the step, the XP and the streak (Trophies). Same
      for completing a subquest, a quest (the celebration, then Undo) and a practice
      session. The quest's history shows both the completion and "Undid: …".
- [ ] **Undo from a notification:** Done on a step reminder. A silent "Done · step"
      notification with Undo appears; Undo puts the step back. Without Undo, the
      confirmation disappears after 5 minutes.
- [ ] **Focus timer with the screen off:** Focus on a card → 10 min → Start. Lock
      the phone and leave it (ideally long enough for Doze; or
      `adb shell dumpsys deviceidle force-idle`). At the end, a chime and "Did you
      finish the step?" arrive on time. Done opens "What's next?"; Keep going starts
      another block; Not yet closes it. Trophies shows the focus minutes.
- [ ] Focus: background the app mid-block: the shade shows the time left with Pause
      and Stop, and both work. Stop says nothing negative and gives no XP. Twelve
      blocks in a day give 60 XP; the next block says today's focus XP is full.
- [ ] **Stuck?:** set the device date 3 days ahead (or the setting to 2 days): the
      card shows "Stuck?". Each option works: Shrink, Focus 10 min, Park it, Ask
      Claude (the share sheet opens with the prompt).
- [ ] **Ask AI, paired and not:** share to Claude from Quest detail. Unpaired, paste
      the numbered reply into capture: "Save N items to inbox" saves one per line.
      Paired, Claude's `propose_add_subquest` proposals arrive in Proposals.
- [ ] **Breathe haptics:** Reset → Breathe → 3 min → Start, then turn the screen off.
      A tap at each inhale, two light taps for the top-up, a long soft hum for the
      exhale continue to the end, then a short double tap. With the screen on, the
      words and the count match the shape. TalkBack reads the phase and the count.
- [ ] **Shrink and Fresh start:** Reset → Shrink a step: the current step is shown,
      the new text replaces it. Reset → Fresh start with "Park all but one": snoozes
      and past dates are cleared, the others are parked, XP and streak unchanged.
- [ ] **Coming back:** with the date moved to a Monday (or after 2+ days), the first
      open shows the resume card with Fresh start. Dismiss it twice on two such
      days: it isn't offered again for 30 days.
- [ ] **Forgiveness:** do a step on day 1, nothing on day 2, open on day 3: one calm
      line with Set step / Dismiss; it never mentions the streak. Trophies shows the
      best streak.
- [ ] **When/where:** in "What's next?", tap the time chip and pick a time, and
      "after ___": the card shows the cue under the step, the calendar and the
      digest show it, and the reminder arrives at that time today.
- [ ] **Obstacle and plan:** Quest detail → Obstacle and plan: both save, show in the
      vault note, and Claude's `get_quest` returns them.
- [ ] **Accessibility:** TalkBack announces Reset, Focus (with the step), Stuck? and
      the Breathe guide; the focus time left is read out.
