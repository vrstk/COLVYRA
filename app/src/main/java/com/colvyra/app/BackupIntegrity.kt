package com.colvyra.app

/** Structural checks for metadata-only backups, without Android dependencies. */
internal object BackupIntegrity {
    const val MAX_COLLECTIONS = 10_000
    const val MAX_REFERENCES = 100_000

    fun validateCollections(ids: List<String>, names: List<String>) {
        require(ids.size == names.size && ids.size <= MAX_COLLECTIONS) {
            "Некорректное количество коллекций"
        }
        require(ids.all { it.isNotBlank() } && names.all { it.isNotBlank() }) {
            "Некорректные коллекции"
        }
        require(ids.size == ids.toSet().size) {
            "Дублирующиеся идентификаторы коллекций"
        }
    }

    fun validateReferences(collectionIds: Set<String>, references: List<Pair<String, String>>) {
        require(references.size <= MAX_REFERENCES) { "Слишком много ссылок" }
        require(references.all { (collectionId, uri) ->
            collectionId in collectionIds && uri.isNotBlank()
        }) { "Ссылка на отсутствующую коллекцию или пустой адрес" }
        require(references.size == references.toSet().size) {
            "Дублирующиеся ссылки в резервной копии"
        }
    }
}
