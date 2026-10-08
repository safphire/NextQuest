# MCP tools

The server exposes the quest log to AI assistants at `<PUBLIC_URL>/mcp`
(Streamable HTTP, stateless, JSON responses — see D-501). Claude and ChatGPT
add it as a custom connector; the connector signs in with OAuth 2.1, and the
server owner approves it once with the admin password.

**AI proposes, you ratify.** Read tools return data. Write tools never change
a quest: each one creates a *proposal* that shows up in the app's Proposals
screen (with the connector's name and its reason), where you accept, edit or
reject it. Nothing an assistant does is irreversible.

Input schemas below are generated from the protocol DTOs; the exact JSON
Schemas are in [`/schema`](../schema) and in `tools/list`.

## Connecting

1. Run the server (`docker compose up -d`, see the README) behind HTTPS.
2. In Claude: *Settings → Connectors → Add custom connector*, URL
   `https://your-server/mcp`. In ChatGPT: *Settings → Connectors → Create*
   (developer mode), same URL.
3. The browser opens the server's consent page. Enter the admin password and
   approve. The connection appears on the server page, where it can be
   revoked at any time.

## Read tools

| Tool | Input | Returns |
|---|---|---|
| `get_today` | — | Date, tracked quests (each with its one next step), slots used / cap, streak, level, XP, pending proposal count. **Start here.** |
| `list_quests` | `status?`: `inbox`, `tracked`, `later`, `done`, `dropped` | Quests with that status; without a status, all open quests. |
| `get_quest` | `quest_id` | One quest: why, status, next step (or `needs_next_step`), subquests with progress, and the ids proposals need. |
| `get_history` | `days?` (1–90, default 7) | Captures, completed steps and quests, XP gained, accepted proposals — each tagged with who did it (`user` or the connector). |
| `get_stats` | — | Level, XP, current/best streak, lifetime counts, unlocked achievements. |
| `list_proposals` | `status?`: `pending` (default), `accepted`, `rejected`, `edited` | Proposals so far and what the user decided. Check before proposing again. |

## Write tools (proposals)

Every write tool requires a `reason` (one or two sentences, shown to the user).
The result is the created proposal with `status: "pending"`; tell the user it
is waiting for them in the app.

| Tool | Input | What accepting it does |
|---|---|---|
| `propose_add_quest` | `title`, `why?`, `next_step?`, `track?`, `due_at?`, `reason` | Creates the quest in the inbox, or tracked (then `next_step` is required and a slot must be free or a park proposal pending). |
| `propose_set_next_step` | `quest_id`, `text`, `due_at?`, `reason` | Replaces the quest's (or subquest's) current step. Refused for a quest whose visible step belongs to a focused subquest — propose it for that subquest. |
| `propose_complete_step` | `quest_id`, `step_id?`, `reason` | Marks the step done (+XP); the app then asks the user for the next step. |
| `propose_add_subquest` | `parent_id`, `title`, `next_step?`, `reason` | Adds a subquest to a top-level quest. |
| `propose_schedule_step` | `quest_id`, `step_id?`, `due_at` (or `null` to clear), `alarm?`, `reason` | Sets or removes the step's date/time and alarm. |
| `propose_park_quest` | `quest_id`, `reason` | Moves the quest to Later, freeing a tracked slot. |

`due_at` is ISO-8601 (`2026-10-02T09:00`, interpreted in the server's `TZ`,
or with an explicit offset).

### Validation

Proposals are checked when created *and* again when accepted (D-113), so a
proposal that became stale (quest finished, step replaced, slots filled in
the meantime) is explained to the user instead of applied blindly. Errors are
returned as tool results with `isError: true` and a message the model can act
on, e.g. *"Not proposed: all 5 slots are full; propose_park_quest for a tracked quest first,
or set track=false so the quest goes to the inbox"*.

Rules worth knowing:

- The tracked cap is hard. `track: true` needs a free slot or a pending
  `propose_park_quest` (each pending park frees one slot only once).
- A second pending park for the same quest is refused.
- Titles are 1–500 characters and step text 1–2,000; reasons are kept up to 1,000.

## Limits and logging

- 60 requests per minute (any method) and 30 proposals per hour per
  connector (`MCP_CALLS_PER_MINUTE`, `PROPOSALS_PER_HOUR`). Over the limit, a
  `tools/call` gets a tool result with `isError: true`; other methods get
  JSON-RPC error `-32000`.
- Every request (`initialize`, `ping`, `tools/list`, `tools/call`) is logged
  with the connector name, method, tool and outcome, including rate-limited
  requests and server faults; the server page shows the latest calls and the
  newest 1,000 are kept. Bodies and tokens are never logged. Notifications
  aren't logged.
- Request bodies are capped at 1 MB (`413` beyond).

## Protocol notes

- `POST /mcp` with JSON-RPC 2.0 (single message or batch). Supported:
  `initialize`, `ping`, `tools/list`, `tools/call`; notifications get
  `202 Accepted`.
- Malformed messages get JSON-RPC errors, per message in a batch: `-32600`
  (not `"jsonrpc": "2.0"`, no method, bad id, empty batch), `-32601` (unknown
  method), `-32602` (params or `arguments` not an object, missing or unknown
  tool name), `-32603` (server fault), `-32000` (rate limited, except for
  `tools/call`). Tool failures — bad arguments, refused
  proposals — are tool results with `isError: true`, as above.
- `GET`/`DELETE /mcp` → 405: no SSE stream and no sessions (stateless server).
- Protocol versions: `2025-06-18`, `2025-03-26`, `2024-11-05`.
- Unauthenticated requests get `401` with
  `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"`,
  which starts discovery: protected-resource metadata → authorization-server
  metadata → dynamic client registration → authorization code + PKCE (S256).
- Browser requests must come from an allowed `Origin` (`ALLOWED_ORIGINS`).
