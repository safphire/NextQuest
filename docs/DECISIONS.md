# Decisions

Where the brief (docs/SPEC.md) was ambiguous or could not be followed
literally, the decision and its reason are recorded here. Each entry names the
product principle (§1) it serves where one applies.

## P0 — Reset

### D-001 · Where v1 lives
The build session was started from an unrelated repository; the brief
clearly targets this one (it names the M0 branch that exists only here). v1 is
built here on branch `v1`; nothing was changed anywhere else.

### D-002 · Fresh application id, no M0 data migration
The brief mandates the package id `dev.dreamweave.quest`. M0 used
`com.nextquest`, so on a device the two are different apps and M0 data cannot
be migrated in place. M0 was never released, so v1 starts at Room schema
version 1 with no migration from the M0 schema. All M0 source was replaced;
reused: Gradle wrapper (8.14.3), version-catalog layout, Android SDK pins,
CI shape (unit tests + debug APK + committed-schema check), backup rules.
M0's spec is kept as `docs/SPEC_M0_LEGACY.md`.

### D-003 · Android modules are verified in CI, not in the dev container
The cloud container used to write v1 has an allowlisted network that does not
include Google's Maven repository (`dl.google.com`), so AGP and AndroidX cannot
be resolved there. Consequences:
- `:core`, `:protocol` and `:server` are built and tested locally
  (`./gradlew -Pquest.jvmOnly=true …`, a flag that drops the Android modules
  and the Android Gradle plugin — also useful to contributors without the SDK).
- `:data` and `:app` are compiled, linted and tested by GitHub Actions on every
  push; CI is the source of truth for them.

### D-004 · Toolchain stays Kotlin 2.1 / AGP 8.7.3
M0's verified Android toolchain (Kotlin 2.1.0, KSP 2.1.0-1.0.29, AGP 8.7.3,
Room 2.6.1, Compose BOM 2024.12.01, compileSdk 35) is kept. The newest MCP
Kotlin SDK (0.15) is built against Kotlin 2.4 / Ktor 3.5 and would force a
simultaneous bump of every Android tool that cannot be verified locally
(D-003). How MCP is implemented is recorded in the P5 decisions.

### D-005 · License text and fonts
- `LICENSE` is the AGPL-3.0-or-later text from the SPDX license list
  (paragraph-reflowed; word-for-word identical to the FSF text).
- **IBM Plex Mono** declares the Reserved Font Name "Plex", so it ships
  unmodified: IBM's own complete (unsubsetted) WOFF 1.0 files from the
  `@ibm/plex-mono` 2.5.0 npm package, unwrapped to TTF (a lossless container
  change; every font table is byte-identical to IBM's, verified with
  fontTools), with IBM's `LICENSE.txt`.
- **Bricolage Grotesque** and **Manrope** declare no Reserved Font Name. They
  come from the Fontsource npm packages (OFL-1.1) as WOFF; the latin and
  latin-ext subsets are merged into one TTF per weight. Glyphs outside those
  subsets fall back to the system font.
- All three OFL licenses ship in `app/src/main/assets/licenses/` and are shown
  in Settings → About.

### D-006 · Product name in exactly two places
`app/src/main/res/values/strings.xml` (`app_name`) for the app and the
`APP_NAME` setting (default "NextQuest") for the server. Everything else —
docs aside — references those. Code, packages, Gradle project names, Docker
image and database names use the neutral `quest` / `dreamweave` naming.

## P1 — Core

### D-101 · Only notification Done may leave "Needs next step"
Principle 4 is applied literally: the only exception is a step completed from
a notification action (`StepVia.NOTIFICATION`). The widget's tap-to-done
opens a small dialog-style "What's next?" activity over the home screen, so
widget completions carry their next step like app completions. (An earlier
draft also exempted the widget; the P1 review rejected that as unjustified.)

### D-102 · Atomic events carry the next step
"Done → What's next?" is one `step.completed` event with an optional `next`
step (and `finishQuest`). `quest.completed`, `quest.status_set` and
`quest.focus_set` carry an optional `next` too. Because every event is
atomic, the fold checks the next-step invariant after *every* event — the
same check guards UI writes, imports, sync merges and accepted proposals.

### D-103 · Invalid events are skipped deterministically, never "fixed"
The fold is a pure function of the ordered log. An event that would break an
invariant (cap, next step, actor rules) or references something missing is
skipped with a recorded reason, identically on every replica. Local writes are
validated as an all-or-nothing commit *before* they are stored
(`Commits.validate`), so the UI never writes an event that would be skipped.
For concurrent offline edits this means "first in canonical order wins": two
devices tracking a fifth quest each → the earlier one is tracked, the other
stays in its previous list (tests: `FoldTest.cap_concurrentTracks…`,
`PropertyTest`).

### D-104 · Hybrid-clock timestamps
Event `ts` is never earlier than the wall clock, strictly increasing per
device, and later than any event the device has already seen. A device's own
commit therefore can't be reordered by its clock jumping back, and a local
edit always sorts after the remote state it was made on.

### D-105 · Focus rules
- Adding a subquest never moves focus: auto-focusing a new, stepless subquest
  would hide the quest's visible step.
- Focus auto-advances only when the focused subquest is finished or dropped
  (to the next open subquest by order, wrapping to the first; otherwise back
  to the quest's own step). Completing a *step* of the focused subquest
  doesn't move focus unless the user also finishes the subquest.
- The user can focus any open subquest (or the quest itself) at any time,
  supplying a next step if the target has none.

### D-106 · No lost XP in edge cases
A step replaced by a newer one ("superseded") can still be completed and
earns its +15 XP; completing an already-done step is ignored (no double XP
after concurrent completion on two devices). Parking, dropping or
re-tracking never removes XP.

### D-107 · The slot cap is an event, not a device setting
The cap is part of the synced state (`settings.slot_cap_set`), so every
replica enforces the same cap. Lowering it below the number of tracked
quests is refused ("park quests first").

### D-108 · Streak details
A day counts when ≥1 step is completed, on the **completing device's** local
calendar: every `step.completed` carries a `local` stamp (wall-clock time,
local date, minute of day). Streaks, achievements and "done at" use the
stamp, so they don't change when the phone travels, when another replica (or
the server) folds the log in a different time zone, or when a device with a
wrong clock skews event timestamps. Events without a stamp (none are written
by v1) fall back to the event timestamp in the folding replica's zone. A one-day gap is forgiven when the first step
of the next active day is before 12:00. The displayed current streak stays
alive through "today", "tomorrow", and the grace morning after a missed day,
so nobody sees a zero before they had a chance to act (principle 6).

### D-109 · Counting rules for achievements
"Inbox items sorted" counts moves out of the inbox (to Tracked, Later or
Dropped). "Quests" are top-level quests; subquests count separately.
"First accepted proposal" counts accepted and edited-then-accepted proposals.

### D-110 · AI tracking needs a free slot or a pending park
`propose_add_quest` with `track=true` is refused unless a slot is free or a
`propose_park_quest` for a tracked quest is pending and not already claimed by
another tracking proposal. On accept, the user still chooses Track (with a
swap dialog if needed) or To Inbox; the fold enforces the hard cap.

### D-111 · Event envelopes are validated
Before any rule, the fold checks the envelope: event id and device id are
UUIDv7, `seq` ≥ 1, `ts` > 0, and a `(deviceId, seq)` pair is never reused by
a different event. Malformed envelopes are skipped like any invalid event.
A proposal's `client` must equal the `mcp:<client>` of its actor, and its
`source` must be the one derived from that client name, so the source chip
can't be spoofed.

### D-112 · Causality over clock sanity
Event timestamps are a hybrid logical clock: a device's next event always sorts
after every event it has seen, even if a peer's clock is days ahead. (An
earlier draft capped how far ahead a device would follow a peer; the P1
round-2 review showed that let a local edit sort *before* the remote quest it
was made on, so it vanished on refold.) Clock sanity is handled where it
matters to the user instead: streaks and "done at" use each completion's own
local stamp (D-108), stamps whose day doesn't match their instant are ignored,
and days after "today" never count toward the current streak. To keep a fast
peer from dragging everyone else along, every ingestion point — the sync
server on push, the app on pull, snapshot import — refuses events more than
24 hours ahead of its own clock (the device with the wrong clock sees a sync
error instead), and the fold refuses timestamps beyond the year 9999. As a
safety net, a local commit that would sort before already-applied events is
refused.

### D-113 · Proposal rules re-checked at accept time
`ProposalRules.decide` re-runs the proposal validation on the (possibly
edited) payload, except for problem codes the accept flow resolves itself:
`SLOTS_FULL` / `PARK_PENDING` (the swap dialog) and `NEEDS_STEP` (the sheet). `set_next_step` targets one
quest's own step; on a quest whose visible step belongs to a focused
subquest it is refused with a message naming the subquest. A second
`park_quest` for the same quest is refused, and pending parks free each slot
only once.

## P2 — Data + UI

### D-201 · Capture and "What's next?" are bottom panels in a plain dialog
They started as Material 3 `ModalBottomSheet`s. On an API 34 emulator the
capture sheet stayed on screen after a quick save (the quest was saved, the
field cleared), with or without hiding through the sheet state first. Both
are now `BottomPanel`: a bottom-anchored Compose `Dialog` that is open
exactly while it is in composition, so closing is synchronous and an action
runs once. Lost: drag-to-dismiss on capture (Back and an outside tap still
close it). "What's next?" ignores Back and outside taps as before; "Not yet"
keeps the step open. The core-loop instrumented test checks both close.

## P3 — Presence

### D-301 · Reminders are planned, then armed
`ReminderPlan.compute(state, settings, now, zone, firedAt)` is a pure function
that yields everything that should be armed (digest, streak guard, step
reminders). `ReminderScheduler` diffs that against what it armed last time and
re-arms on every state or settings change, on boot, on time and time-zone
changes, after an app update and when the exact-alarm grant changes. Because
it is idempotent, every trigger calls it the same way. A step reminder whose
time passed while the phone was off fires once after boot. The "fired" marker
stops it from firing again.

### D-302 · Alarm fallbacks
- A step with `alarm = true` and the exact-alarm grant uses
  `setAlarmClock`. That shows the alarm icon and is never deferred by Doze.
  If full-screen intents are allowed, it also opens `AlarmActivity`.
- Without the grant, everything uses `setAndAllowWhileIdle` (may be a few
  minutes late). Settings explains this and links to the system screen.
- Without notification permission nothing can be shown. The permission is
  asked for once, in context: when the user first sets a reminder or turns on
  the digest or streak guard. Those two are on by default, so they count as the
  first reminders, and the question comes shortly after the main screen first
  appears (never during onboarding). Settings shows the permission's state.

### D-303 · Snooze is an event, Skip is a dismissal
Snooze 1h writes `step.snoozed` (synced, so other devices don't ring as well).
Skip records that this reminder was dismissed. The step stays current and is
not completed. Done from a notification is the one path that may leave a
quest without a next step (D-101). The quest then shows "Needs next step".

### D-304 · Widget Done asks for the next step
The Glance widget's Done opens a small translucent dialog activity instead of
completing silently. Principle 4 has no widget exemption, so the widget asks
for the next step the same way the app does.

## P4 — Durability

### D-401 · The vault mirror is one-way and touches only its own files
The user picks a folder through SAF. The app writes `Quests/<title>.md` per
quest, with `id:` front matter, and daily snapshots to `Quests/.backups/`.
Edits made in Obsidian are not read back: the event log is the source of
truth, and two-way merge of free text is out of scope for v1.

Files it didn't write are never modified or deleted:
- A note is overwritten only if its `id:` is the quest's own id, or it is
  exactly what the last mirror wrote. A manifest
  (`.backups/.mirror-manifest.json`) records the name and SHA-256 of every
  note the mirror wrote.
- If another file already has a quest's name, the quest's note gets a short
  id suffix (`Title (1a2b3c).md`), or the full id if that is taken too.
  Wikilinks use the same names.
- A note is deleted after a rename only if the manifest lists it and its
  content is unchanged. Copies and edited notes stay.
- User text is flattened to one line in the note body and escaped. It can't
  start a heading, quote, list, table or code fence, an Obsidian `%%`
  comment, or a Dataview `key:: value` field.
- File names are safe on Windows too: no reserved device names, no leading
  dots, no trailing dots or spaces. They are cut between code points, within
  180 UTF-8 bytes.

Snapshots:
- `snapshot-<date>.json` is replaced only by a snapshot that contains every
  event it holds. Otherwise the new one goes beside it as
  `snapshot-<date>-<HHmmss>.json`. A fresh install that re-picks the vault
  can't overwrite the old phone's backup.
- Each snapshot is written to a temporary file, then renamed.
- The app never writes an empty snapshot.
- It keeps the newest 8, plus the one with the most events if that one is
  older.

Snapshot and mirror run independently, one at a time. The last error shows
in Settings. A folder whose grant was lost shows "choose it again". Changing
the folder releases the old grant.

### D-402 · Snapshots are the event log, and import merges
A snapshot is a versioned header plus every event. Import validates the file
and ingests the events through the same path as sync. The fold's dedupe by
event id makes re-importing harmless, and import never wipes. Events more
than 24h in the future are refused (D-112) and listed in the report.

A malformed file never crashes the import; the decoder reports it as
invalid. That covers wrong types, nesting more than 64 levels deep,
`schemaVersion` ≤ 0, and a header without a positive `exportedAt` or a valid
`deviceId`.

Events that carry this install's device id but aren't in its log are
refused. They would take `(deviceId, seq)` slots that the local event factory
is about to use. Android backup never restores the device id, so a real
restore never hits this.

Files over 50 MB are refused before they are read in full. Decoding runs off
the main thread.

## P5 — Server

### D-501 · MCP implemented directly, not with the Kotlin SDK
The spec asked for the official SDK or a documented reason. Reasons:
- SDK 0.15+ needs Kotlin 2.4. The project is on 2.1 (D-004).
- In 0.8.4, the last release that builds with 2.1, the Streamable-HTTP
  endpoint function is `internal`.
- The public `mcpStatelessStreamableHttp` returned 406 to a spec-conformant
  request (`Accept: application/json, text/event-stream`) in a probe.

The server only needs the stateless subset, about 150 lines in
`McpServer.kt`:
- JSON-RPC 2.0 over `POST /mcp` with `application/json` replies.
- `initialize`, `ping`, `tools/list`, `tools/call`. Notifications get 202.
- No sessions and no SSE: `GET` and `DELETE /mcp` return 405, which the
  spec allows.

Tool input schemas are generated from the same `@Serializable` DTOs as
`/schema`. Protocol versions 2025-06-18, 2025-03-26 and 2024-11-05 are
accepted. Revisit when the SDK and the toolchain line up.

### D-502 · Single-user server, multi-user tables
A self-hosted server belongs to one person: the admin password is the login.
Every table still has `user_id`, and every query filters by it, so a hosted
multi-user mode only needs a user table and a login.

### D-503 · OAuth 2.1 details
- Dynamic client registration is open, as the MCP clients require. A
  registered client can do nothing until the owner approves it on the
  consent page with the admin password (or the signed-in admin session plus
  its CSRF token).
- At most 50 *approved* clients. Registrations the owner never approved don't
  count and are deleted after 24h; so are approved clients with no live token
  and no approval in the last 24h (every consent refreshes the approval, so a
  client being re-authorized is never purged between code and token).
  Disconnecting on the server page deletes the client and its tokens: the
  connector has to be added again.
- At most 200 never-approved registrations are kept. Beyond that the oldest
  ones are evicted, but only those older than 10 minutes, so a registration
  flood can't evict the connector the owner is approving; if none is that old,
  registration answers `429 temporarily_unavailable`. Registration is also
  limited to 20 per hour per address (IPv6: per /64), at most 10 redirect URIs
  of at most 2,048 characters, and a 40-character name.
- Only PKCE S256 is accepted.
- Codes are single-use. Replaying one — including losing the race against a
  concurrent exchange — revokes every token of that client (tokens aren't
  linked to the code they came from, and the client is suspect either way).
  The revocation is committed outside the failing transaction, so it sticks.
- Refresh tokens rotate. Reusing a rotated refresh token revokes its whole
  family. A refresh token only works for the client it was issued to.
- Tokens carry the audience of the code (RFC 8707 `resource`) through every
  rotation. It is always `<PUBLIC_URL>/mcp`, whether or not the client sent
  `resource`; `/mcp` refuses a token bound to any other audience (e.g. after
  `PUBLIC_URL` changed). Unbound tokens issued before this still work and are
  bound on their next refresh.
- Errors are redirected to the client, except when the client or redirect
  URI itself is invalid (RFC 6749 §4.1.2.1) or the client was never approved
  (D-507).
- Only hashes of codes and tokens are stored.

### D-504 · Proposals are written by the server as events
Write tools validate with `ProposalRules` against the server's own fold, then
append a `proposal.created` event with the server's device id and actor
`mcp:<client name>`. Phones receive it through normal sync. The server never
writes domain events itself. Validation and append happen under the event
store's lock against the same fold, so two concurrent proposals can't both
pass against a stale state (e.g. both claim the last free slot).

Devices push user events of any device id except the server's own and
another active (paired, not removed) device's. A phone that imported the
snapshot of a dead or removed phone can therefore push that phone's events,
while no live device can occupy another live device's `(deviceId, seq)`
slots.

Known limitation: a paired device could push events under a removed device's
id and occupy sequence numbers that device would use if it re-paired with the
same id. Acceptable because every paired device belongs to the owner.

### D-505 · Limits and the call log
Defaults, each configurable by environment variable:
- 60 MCP requests per minute per connector, whatever the method (every
  request is a log write); a refused `tools/call` is a tool error, other
  methods get JSON-RPC error `-32000`.
- 30 proposals per hour per connector.
- Login and pairing: 10 attempts per 10 minutes per IP.
- Wrong admin passwords: 100 per hour across all IPs. A botnet can't
  out-guess the per-IP limit; while the budget is spent nobody can sign in,
  but a signed-in owner keeps their session and can still approve connectors.
  Each attempt reserves a failure atomically and a correct password returns
  it, so concurrent guesses can't overshoot. The counters are in memory: an
  owner locked out by someone else's guessing can restart the server to clear
  them (the sign-in page says so).
- Request bodies: 64 KB for OAuth, login, admin forms and pairing, 4 MB for
  `POST /v1/events`, 1 MB for `/mcp`; larger ones get 413, whether declared
  in `Content-Length` or streamed.

The IP is the socket peer's address, never a reverse-DNS name; IPv6
addresses are keyed by their /64, which one client usually holds whole.
Behind a reverse proxy every client would share the proxy's address, so
`TRUST_PROXY` (number of proxy hops; 0 in the server, 1 in
`docker-compose.yml`) takes it from `X-Forwarded-For`, counting that many
entries from the right; entries further left are client-supplied. The header
is honoured only when the peer is loopback, RFC 1918 (including Docker
bridges), link-local or IPv6 unique-local — where a proxy on the same host or
network connects from. A client reaching a published port directly from the
internet can't choose its key, but one relayed by Docker's userland proxy
(e.g. over IPv6) can appear private, hence `TRUST_PROXY=0` when nothing sits
in front. Expired
limiter keys are swept, so memory stays bounded.

Every MCP request (not notifications) is logged with the connector name,
method, tool, outcome and a short detail, including rate-limited ones and
internal errors (`-32603`); the newest 1,000 per user are kept.
The log is visible on the admin page. Query strings and bodies are never
logged.

### D-506 · Origin check on /mcp
Browser requests to `/mcp` must come from an allowed origin
(`ALLOWED_ORIGINS`, default claude.ai and chatgpt.com). Requests without an
`Origin` header, from server-side clients, are allowed. They still need a
bearer token.

### D-507 · An unapproved client is unverified
Registration is open, so a client's name and redirect URIs are whatever
anyone sent. Until the owner first approves a client:
- Its redirect URI isn't trusted: authorization errors and a *Deny* are shown
  on a page instead of being redirected (no open redirect via registration).
- Its name is ASCII letters, digits, spaces and `._-()` only, so a look-alike
  from another script ("Сlaude" with a Cyrillic С) can't read as a vendor
  while mapping to none: the foreign letters are dropped.
- Its name can't claim a vendor. A name that maps to Claude or ChatGPT
  (`ProposalSource.fromClientName`) is kept only when every redirect URI is on
  that vendor's hosts (`claude.ai`, `claude.com`, `anthropic.com`;
  `chatgpt.com`, `openai.com`, and their subdomains) or loopback, where the
  code never leaves the owner's machine. Otherwise it is stored as
  "Unverified client (<host>)", or just "Unverified client" if the host
  itself names a vendor. The name becomes the consent title, the
  `mcp:<name>` actor and the proposal's source chip, so it is fixed at
  registration.
- The consent page shows the redirect host — where the code goes, the one
  thing a client can't fake — above everything else.

### D-508 · Security headers
Every response carries `X-Frame-Options: DENY`, `X-Content-Type-Options:
nosniff`, `Referrer-Policy: no-referrer` and `Content-Security-Policy:
frame-ancestors 'none'; default-src 'none'; style-src 'unsafe-inline';
img-src 'self' data:; base-uri 'none'`. Pages have no scripts; QR codes are
inline SVG. `form-action` is left out on purpose: the consent form posts to
the server, which redirects to the connector's redirect URI, and Chrome
applies `form-action` to that redirect too.

## P6 — Integration

### D-601 · The event log is the offline queue
Room schema v2 adds a `pushed` flag to the events table (migration 1→2;
existing events start unpushed). A sync round:
1. Pushes every unpushed user event in batches of 1,000, keyset-paged by id.
2. Pulls after the stored cursor, page by page, saving the cursor after each
   page.

Both directions are idempotent, so a round cut short is simply repeated.
Pulled events are marked pushed. Events written on behalf of AI clients are
never pushed, because only the server writes them.

A refusal is final: the event is marked and never blocks the queue. The one
exception is "dated in the future", which waits for the server's clock to
catch up. Only refusals of this device's own events are shown to the user.

If a push response's cursor is behind ours, the server was reset, and we pull
from 0.

### D-602 · When sync runs
WorkManager runs sync, always with a network constraint and exponential
backoff from 30 s:
- every 15 minutes while paired (WorkManager's minimum);
- when the app opens;
- 5 s after a local change. This is debounced: a newer request replaces a
  pending one.

A 401 (device removed on the server) stops retrying and asks the user to pair
again. Disconnecting deletes nothing. Pairing with a server marks the whole
log unpushed, so a new server receives it.

### D-603 · Pairing links never pair by themselves
The server page's QR code / `dwquest://pair` link only pre-fills the form in
Settings. The user sees the server address and taps Pair. The launcher
activity is exported and any app or web page can fire the link, so pairing
silently would hand the quest log to whoever made it. Links and typed addresses must be `https://`. Android blocks cleartext traffic
anyway, and the server is meant to sit behind HTTPS.

### D-604 · Imported events from other devices are uploaded
A phone that imported a lost phone's snapshot pushes those events too. The
server refuses only events that belong to another *active* paired device or
to the server itself (D-504). That still stops one live device from occupying
another live device's sequence numbers.
