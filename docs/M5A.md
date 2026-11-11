# M5A — Stable signing and local gallery cache

## What changed
- Local folders and virtual collections now share a single, bounded Coil ImageLoader (15% application memory-cache budget, 128 MiB private disk cache), rather than rebuilding caches on each screen switch. Original media files are never copied into this thumbnail cache.
- Android version code 11; version name 0.5.0-m5a.
- Regular CI still produces an **unsigned-for-distribution debug build**, which is not guaranteed to update an APK created on a different CI machine.
- On main-branch push or manual workflow launch, a separate release job **only** builds a signed APK when the complete secret set is configured. This job verifies the signature with apksigner and publishes a SHA-256 checksum.

## One-time release-key creation (on a trusted offline computer)
```sh
keytool -genkeypair -v -keystore colvyra-release.jks -alias colvyra -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 colvyra-release.jks > colvyra-keystore.b64
```
On systems without `base64 -w`, use `base64 colvyra-release.jks | tr -d '\\n'`.
Choose unique, strong passwords. Keep the JKS and passwords **outside GitHub and outside the repository**, with encrypted offline backups. Losing the key may prevent future updates without reinstalling and losing private app data. Do not change its alias or identity between versions.

Add the following GitHub Actions repository secrets via Settings → Secrets and variables → Actions (never in a commit or in chat):
- `COLVYRA_KEYSTORE_BASE64`: the full base64 JKS contents
- `COLVYRA_KEYSTORE_PASSWORD`
- `COLVYRA_KEY_ALIAS` (e.g. `colvyra`)
- `COLVYRA_KEY_PASSWORD`

Do not use signing material from untrusted forks. PR builds do not run the signing job; partial/missing secrets cause a safe skip. The job deletes the temporary JKS even if the build fails. Never paste passwords, keystores, or their base64 encoding into issues or logs.

## Installation and migration warnings
- Android requires the exact same package ID and compatible certificate for an in-place update. A newly signed release **will not** install over an older APK carrying a different debug certificate. Back up the JSON metadata through Settings before any uninstall, but note: JSON backup **does not** contain passwords, original media, private imports, persisted Android access grants, or cloud credentials.
- Do **not** uninstall COLVYRA until private imported content has been separately copied to a safe, user-controlled directory. Private app data is generally removed on uninstall.
- Before switching to a release key, test a clean installation and a subsequent upgrade signed by the **same** key on a spare device or emulator.
- The debug APK remains for testing only. Release APKs are intended for sustained upgrades once stable signing secrets are securely configured.

## Manual regression checklist
1. Open a folder of hundreds of images in grid and list modes, switch repeatedly between Local and Collections; verify scrolling, thumbnails and memory stability.
2. Swipe through photographs and video; verify audio stops on navigation.
3. Confirm no changes to original media or collections after returning to the screen.
4. Test light/dark/system themes and Android storage permission persistence after app restart.
5. Verify signing via `apksigner verify --print-certs`; compare certificate fingerprints across two release builds.
6. Upgrade signed release vN → vN+1 without uninstall; verify collections, settings and private imports remain.
7. Test without secrets: debug succeeds and signed-release job skips without an artifact.

CI compilation is not device testing. No release APK exists until authorized secrets are configured.
