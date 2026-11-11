# M2 virtual collections

Room stores collection metadata and references to user-granted Android documents. A reference is not a copy. Removing it or its collection does not delete the original. Folder references are links, not recursive imports. Tags are comma-separated text. Missing/revoked or moved originals can be unavailable; opening checks availability. Existing M0/M1 names migrate once into Room. Cloud connectors and backup/export remain future work.

Manual acceptance: create/edit collection, add two files and a folder, add one file to two collections, choose a cover, search by tags, restart, remove a reference and verify original remains, delete collection and verify original remains. Test with permission revoked and file moved. No device tests completed yet.
