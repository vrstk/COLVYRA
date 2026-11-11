# M5C — Installed APK diagnostics and upgrade readiness

Version: `0.5.2-m5c` (`versionCode=13`).

## What the Settings screen now shows

- Installed Android package version and debug/release status.
- Current Android SDK version.
- SHA-256 digest of the installed APK **signing certificate** (public certificate, not a secret private key).
- Whether a local folder was selected and whether the app still has its matching persisted read/write SAF grant.
- Total count of persisted SAF grants, collections and references.
- A **Copy safe report** control for troubleshooting, with no file names, root paths, media, account names, passwords or API credentials.
- A refresh control for current data.

The report is built locally and is never automatically transmitted to a server. The user must choose to paste it elsewhere.

## Upgrade check

1. Install the previous APK on a **spare test device** and open Settings -> Diagnostics. Copy the certificate fingerprint and version.
2. Install the next APK **over the existing installation**, without first uninstalling.
3. If Android refuses with a signature mismatch, inspect the new APK signing certificate; do **not** uninstall to force the install unless private app data has been backed up separately.
4. After a successful upgrade, compare counts and check that collections, imports and cloud settings remain accessible.
5. Debug builds from independent GitHub-hosted runners may use different Android debug signing certificates. The repository's optional release-signing workflow needs a **persistent, user-owned keystore** configured in GitHub Actions secrets before it can provide reliably upgradable APKs.

## Cautions

- A saved SAF permission is not proof that the source is online or the file still exists.
- The app cannot safely verify all collection references without actually opening them.
- The metadata JSON backup does not contain original photos/videos, private imported files, cloud passwords, or Android SAF grants.
- A report placed on the clipboard can be read by the operating system and by applications under Android clipboard access rules. Although designed to exclude secrets, review pasted text before sharing it.
- Unit tests only exercise report formatting; installing, updating and permissions still require Android-device checks.
