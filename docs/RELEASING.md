# Releasing and signing

Two keys sign the app. Neither is ever committed: both live in GitHub Actions
secrets and are decoded into the runner's temp folder only for the build.

## Stable debug signing (one-time setup)

Android only installs an update over an existing app when both are signed with
the same key. GitHub's runners create a fresh debug key for every build, so
without this setup each CI debug APK must be uninstalled (deleting the app's
data) before the next one installs. With it, every CI debug APK installs over
the previous one and keeps your quests.

1. Generate a dedicated debug keystore on your own machine (never reuse the
   release key, never commit the file):

   ```sh
   keytool -genkeypair -v -keystore quest-debug.keystore -storetype PKCS12 \
     -alias questdebug -keyalg RSA -keysize 2048 -validity 10000 \
     -dname "CN=Quest debug"
   ```

   Pick a password when asked (PKCS12 uses the same one for the store and the key).

2. Base64-encode it:

   ```sh
   base64 -w0 quest-debug.keystore > quest-debug.keystore.b64   # Linux
   base64 -i quest-debug.keystore -o quest-debug.keystore.b64   # macOS
   ```

3. In the repository on GitHub: *Settings → Secrets and variables → Actions →
   New repository secret*, add:

   | Secret | Value |
   |---|---|
   | `DEBUG_KEYSTORE_B64` | the contents of `quest-debug.keystore.b64` |
   | `DEBUG_KEYSTORE_PASSWORD` | the store password |
   | `DEBUG_KEY_ALIAS` | `questdebug` |
   | `DEBUG_KEY_PASSWORD` | the key password (same as the store password for PKCS12) |

4. Keep `quest-debug.keystore` somewhere safe (a password manager is fine) and
   delete the `.b64` file. Losing it only means one more uninstall; it protects
   nothing secret.

The CI job's summary says which key signed each APK. If the secrets are missing
it prints a warning and signs with the runner's throwaway key, so the build
still succeeds.

The first CI APK signed with the stable key can't install over an older build
signed with a throwaway key. Uninstall once (export a snapshot first: Settings
→ Vault → Export), install, import the snapshot. From then on updates install
in place.

CI debug builds also number themselves (`versionCode = 100 + run number`), so a
newer CI APK is always an update. A local `./gradlew :app:installDebug` build
has version code 100 and a different key: it won't install over a CI build.

## Release signing

The signed release workflow (`.github/workflows/release.yml`) runs on `v*`
tags or by hand. It needs `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`; without them it only explains
what is missing. Generate the release key the same way as above, with its own
alias and a strong password, and keep an offline backup: an app signed with a
lost release key can never be updated.

## Network security

Release builds only connect to a sync server over HTTPS. Debug builds also
allow plain `http://`, so a maintainer can pair a phone with a server on the
local network (e.g. `http://192.168.1.20:8080`) while testing. Settings → Sync
shows which mode the installed build is in.
