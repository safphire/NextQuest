# CLAUDE.md — NextQuest engineering rules

NextQuest is a native Android personal quest log. `SPEC.md` is the product
contract — read it before changing behavior. These are the standing
engineering rules distilled from it.

## Stack

- Kotlin 2.x + Jetpack Compose (Material 3, dark-only custom theme).
- Room (DB) · DataStore (settings) · WorkManager + AlarmManager (notifications)
  · SAF/DocumentFile (vault writes) · kotlinx-serialization.
- minSdk 26, targetSdk current. No backend, no accounts, single user,
  local-first.

## Architecture: the pure `core` package

- All domain logic — XP/level curve, streak computation, next-step invariant,
  tracked-cap rule, versioned JSON snapshot codec — lives in the `:core`
  Gradle module (`com.nextquest.core`), which is **pure Kotlin/JVM with zero
  Android imports**. No `android.*`, no `androidx.*`, ever.
- `core` is covered by plain JVM unit tests (`:core:test`). New domain rules
  land there with tests before any UI wires them up.
- The `:app` module holds Room, Compose, and platform glue; it adapts to
  `core` types, never duplicates their logic.
- The versioned JSON snapshot and the vault markdown format are **public
  contracts** — never change them without bumping `schemaVersion` and keeping
  old versions readable.

## Room discipline (data durability is non-negotiable)

- `exportSchema = true`; generated `app/schemas/*.json` are **committed**.
- Additive changes use auto-migrations; renames/splits get a hand-written
  `Migration(n, n+1)`.
- **One `MigrationTestHelper` test per schema version** — every historical
  schema must open green before release.
- `fallbackToDestructiveMigration()` (and any destructive-migration variant)
  is **banned**. It silently wipes user data.
- `EventLog` is the source of truth for stats; XP/level/streak/achievements
  are derived, never stored as primary state.

## Workflow

- **Conventional commits**: `feat:`, `fix:`, `docs:`, `ci:`, `build:`,
  `test:`, `refactor:`, `chore:` (scope optional, e.g. `feat(core): …`).
- **One milestone per PR** (M0 Loop, M1 Depth, M2 Vault, M3 Notifications,
  M4 AI — see SPEC.md §8). Don't mix milestones in a branch.
- CI must stay green: unit tests + `assembleDebug` run on every push; the
  debug APK is the build artifact.
- Never commit keystores, API keys, or secrets — they are gitignored; keep it
  that way.

## Build

- Gradle wrapper is pinned (`gradle/wrapper/gradle-wrapper.properties`);
  Android SDK versions are pinned in the build and mirrored in CI. Bump them
  deliberately in a dedicated `build:` commit, not as a side effect.
- `./gradlew :core:test` — fast JVM domain tests (no Android SDK needed).
- `./gradlew test :app:assembleDebug` — full check, same as CI.
