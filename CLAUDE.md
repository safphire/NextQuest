# CLAUDE.md — engineering rules

Local-first Android quest log + optional self-hostable sync/MCP server.
`docs/SPEC.md` is the product contract (the v1 brief); `docs/DECISIONS.md`
records every judgment call made against it. Read both before changing behavior.

## Principles (they decide every judgment call)

1. One thing visible — each tracked quest shows only its single next step.
2. Capture in two seconds from anywhere; capture never forces sorting.
3. Hard cap of tracked quests (3–7, default 5) — never exceedable by any path.
4. Every tracked quest has a next step; only a notification/widget Done may
   leave it flagged "needs next step".
5. AI proposes, the user ratifies — nothing from the server/MCP mutates quest
   state without a user acceptance event.
6. Re-entry without guilt — no overdue walls of red.
7. Local-first — fully functional offline, no account.

## Modules

| Module | What | Rules |
|---|---|---|
| `:core` | Events, fold (events → state), game rules, invariants, proposal validation | Pure Kotlin. **No `android.*`/`androidx.*` ever.** ≥90% line coverage (`koverVerify`). |
| `:protocol` | Wire DTOs (sync, MCP tool I/O), JSON Schema emission to `/schema` | Backward compatible; regenerate `/schema` after changes. |
| `:data` | Room event store, repositories, DataStore settings, sync client | Room discipline below. |
| `:app` | Compose UI, notifications, alarms, widget, vault export | Adapts `:core` types; never duplicates domain logic. |
| `:server` | Ktor sync API + MCP endpoint + OAuth, SQLite | Every table carries `user_id`. |

## Event sourcing

- The append-only event log is the only source of truth. XP, level, streak,
  achievements, history and Chronicle are derived by the fold in `:core`.
- Local writes go through `Commit` validation in `:core` (all-or-nothing);
  merged/imported/synced events go through the deterministic fold, which
  skips (and records) any event that would break an invariant.
- Canonical order: `(ts, deviceId, seq)`. See `docs/SYNC.md`.
- All IDs are UUIDv7 strings (`Ids.newId()`).

## Room discipline (data durability is non-negotiable)

- `exportSchema = true`; `data/schemas/*.json` are **committed**.
- Every schema change ships with a `Migration(n, n+1)` (or auto-migration),
  the exported schema JSON, and a migration test (Robolectric in `:data:test`,
  plus instrumented in `:data:androidTest`).
- `fallbackToDestructiveMigration()` and its variants are **banned**.

## Naming

- The product name lives in exactly two places: `app_name` in
  `app/src/main/res/values/strings.xml` and the server's `APP_NAME` setting.
  Never hard-code it anywhere else. Package id: `dev.dreamweave.quest`.

## Workflow

- Conventional commits (`feat:`, `fix:`, `docs:`, `test:`, `build:`, `ci:`,
  `refactor:`, `chore:`; scope optional).
- SPDX header `// SPDX-License-Identifier: AGPL-3.0-or-later` on every source file.
- CI must stay green. Never commit keystores, tokens or passwords.

## Build

- `./gradlew test` — everything (needs Android SDK + Google Maven).
- `./gradlew -Pquest.jvmOnly=true test` — `:core`, `:protocol`, `:server` only.
- `./gradlew :core:koverVerify` — coverage gate.
- `./gradlew :protocol:generateSchemas` — refresh `/schema`.
- `./gradlew :app:assembleDebug` — debug APK. `./gradlew :server:run` — server.
