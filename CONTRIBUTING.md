# Contributing

Thanks for wanting to help. A few things to know first.

## Contributor License Agreement (required)

**All contributions require agreeing to the [Contributor License Agreement](CLA.md).**
The CLA lets the maintainer offer the code under a commercial license in
addition to the AGPL (that is how the hosted version can fund the project),
while guaranteeing every version stays available as open source.

Sign it by adding this line to your first pull request description:

```
I have read the CLA in CLA.md and I agree to it. — Full Name (@handle)
```

Pull requests without it cannot be merged.

## Before you code

1. Read the [product principles](README.md#principles). They decide every
   judgment call — a feature that breaks one of them will not be merged.
2. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The short version: state
   is a fold over an append-only event log, and all domain rules live in the
   pure-Kotlin `:core` module.
3. Open an issue before large changes so we can agree on the approach.

## Ground rules

- **License headers.** Every source file starts with
  `// SPDX-License-Identifier: AGPL-3.0-or-later` (or the comment syntax of
  its language).
- **Domain rules go in `:core`** with JVM unit tests, never in UI code.
  `:core` has zero Android imports and a 90% line-coverage gate.
- **Schema changes** to Room ship with a migration, the exported schema JSON
  (`data/schemas/`), and a migration test. `fallbackToDestructiveMigration` is banned.
- **Wire changes** in `:protocol` must regenerate `/schema`
  (`./gradlew :protocol:generateSchemas`) and stay backward compatible.
- **AI proposes, the user ratifies.** Nothing reachable from the server or MCP
  may mutate quest state without a user acceptance event.
- **No secrets** in the repository, ever.
- **Conventional commits** (`feat:`, `fix:`, `docs:`, `test:`, `build:`, `ci:`,
  `refactor:`, `chore:`).

## Building

```bash
./gradlew test                    # everything (needs the Android SDK)
./gradlew -Pquest.jvmOnly=true test   # just :core, :protocol, :server
./gradlew :app:assembleDebug      # debug APK
./gradlew :server:run             # sync + MCP server on :8080
```

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
