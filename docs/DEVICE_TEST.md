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
