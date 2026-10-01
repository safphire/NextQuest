# Secret scan

The repository must never contain secrets (signing keys, API keys, tokens,
passwords). Every push is scanned in CI (`secrets` job in
`.github/workflows/ci.yml`, gitleaks over the full history of all refs).

## Scan log

### 2026-10-01 — P0, before any v1 changes

- **Tool:** gitleaks v8.30.1 (default ruleset), `gitleaks git --log-opts="--all"`
- **Scope:** full history of every ref — the 7 M0 commits on
  `claude/nextquest-android-setup-jpaq1n` (`cba381f` … `164ebcd`), ~93 KB.
- **Findings:** none. `no leaks found`.
- **Manual review:** `.gitignore` already excludes keystores (`*.jks`,
  `*.keystore`), `keystore.properties`, `secrets.properties`, `.env` and
  `local.properties`. No signing material or credentials in any commit.

### 2026-10-01 — P0 review re-scan

- **Scope:** all refs after the v1 reset (9 commits). **Findings:** none.
- CI now runs gitleaks v8.30.1 pinned by image digest on every push.

### 2026-10-01 — final v1 scan (head `153fb02`)

- **Tool:** gitleaks v8.30.1, `gitleaks git --log-opts="--all" --redact`
- **Scope:** every ref, 43 commits (~1.2 MB), including the 7 M0 commits
  (`cba381f` … `164ebcd`) on `claude/nextquest-android-setup-jpaq1n`.
- **Findings:** none. `no leaks found`.
- **Manual review:** `.env.example` holds no values for secrets
  (`ADMIN_PASSWORD=` is empty); the CI `docker` job generates its password at
  run time; the release workflow reads the keystore only from Actions secrets.

## What is sensitive in this project

| Secret | Where it lives | Never in |
|---|---|---|
| Release keystore + passwords | GitHub Actions secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; the release workflow decodes the keystore to a temp file and passes its path as `RELEASE_KEYSTORE_PATH` | git |
| Server admin password | `ADMIN_PASSWORD` env var / `.env` next to `docker-compose.yml` | git, logs |
| Device tokens, OAuth tokens | Server DB stores only SHA-256 hashes; app keeps its device token in `sync_secrets.xml` (excluded from Android backup) | git, logs, backups |
