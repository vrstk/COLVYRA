# COLVYRA: Product specification (M0)

**Product:** Open-source universal Android file manager, media gallery and virtual collections platform.

## Agreed design
- File manager and gallery are equally important.
- Light, dark and eventually system themes.
- Collections can organize references to content across multiple sources without moving or deleting originals.
- Privacy-first, no mandatory COLVYRA account.
- Public GitHub repository, Apache-2.0.

## Phased connectors
- M0/M1: user-granted local Android folders (SAF), MediaStore.
- M3: Mail.ru Cloud and generic WebDAV with secure credential storage.
- M4: website bookmarks, user-initiated downloads and Android sharesheet.
- Later: Google Drive OAuth, Google Photos Picker, Telegram integration, VK ID, approved Reddit API, SMB/SFTP and further clouds.

## Restrictions
- Social media APIs must use platform-authorized permissions; no bypasses or mass scraping.
- Google Photos does not offer unrestricted access to all existing library items using its current Library API.
- No fake cloud connection UI representing an unimplemented connector.
- Never include credentials, photos or user data in this repository.
