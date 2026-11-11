# M3c

Multiple HTTPS WebDAV accounts; previous encrypted single account is read and migrated when saving accounts. IDs remain derived from endpoint and username, so existing virtual references remain compatible. Last folder is recorded per account and restored; root button is a fallback if a folder is removed. Disk image caching is opt-in, bounded per account and in app-private cache. Cache is not encrypted and can hold original downloaded image bytes. Clear cache removes active account cached data. No credentials in Git.

Manual tests: upgrade from M3b with existing account; add second account; switch, open nested folder, switch back, restart; open collection entry belonging to non-active account; disconnect one account; test cache toggle and clear. Video thumbnails and persistent transfer queue remain pending.
