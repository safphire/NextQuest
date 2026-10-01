# Decisions

Where the brief (docs/SPEC.md) was ambiguous or could not be followed
literally, the decision and its reason are recorded here. Each entry names the
product principle (§1) it serves where one applies.

## P0 — Reset

### D-001 · Where v1 lives
The build session was started from an unrelated repository (`diarytale`); the
brief clearly targets `safphire/NextQuest` (it names the M0 branch that exists
only there). v1 is built in `safphire/NextQuest` on branch `v1`; nothing was
changed in the other repository.

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
  (paragraph-reflowed; content identical to the FSF text).
- Fonts come from the Fontsource npm packages (OFL-1.1) and are converted to
  TTF with the latin + latin-ext subsets merged. Glyphs outside those subsets
  fall back to the system font. OFL licenses ship in
  `app/src/main/assets/licenses/` and are shown in Settings → About.

### D-006 · Product name in exactly two places
`app/src/main/res/values/strings.xml` (`app_name`) for the app and the
`APP_NAME` setting (default "NextQuest") for the server. Everything else —
docs aside — references those. Code, packages, Gradle project names, Docker
image and database names use the neutral `quest` / `dreamweave` naming.
