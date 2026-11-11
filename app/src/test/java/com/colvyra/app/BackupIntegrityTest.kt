package com.colvyra.app

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupIntegrityTest {
    private fun invalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    @Test fun acceptsEmptyAndValidCatalogs() {
        BackupIntegrity.validateCollections(emptyList(), emptyList())
        BackupIntegrity.validateReferences(emptySet(), emptyList())
        BackupIntegrity.validateCollections(listOf("one", "two"), listOf("Photos", "Videos"))
        BackupIntegrity.validateReferences(setOf("one", "two"), listOf(
            "one" to "content://photos/1",
            "two" to "https://example.org/video"
        ))
    }

    @Test fun rejectsDuplicateOrMissingCollectionIdentifiers() {
        invalid { BackupIntegrity.validateCollections(listOf("x", "x"), listOf("A", "B")) }
        invalid { BackupIntegrity.validateCollections(listOf(""), listOf("A")) }
        invalid { BackupIntegrity.validateCollections(listOf("x"), listOf("")) }
        invalid { BackupIntegrity.validateCollections(listOf("x"), emptyList()) }
    }

    @Test fun rejectsOrphansEmptyUrisAndDuplicatePairs() {
        invalid { BackupIntegrity.validateReferences(setOf("x"), listOf("y" to "content://a")) }
        invalid { BackupIntegrity.validateReferences(setOf("x"), listOf("x" to "")) }
        invalid { BackupIntegrity.validateReferences(setOf("x"), listOf(
            "x" to "content://a", "x" to "content://a"
        )) }
    }

    @Test fun enforcesCountLimitsBeforeDatabaseWrites() {
        val tooMany = List(BackupIntegrity.MAX_COLLECTIONS + 1) { "id$it" }
        invalid { BackupIntegrity.validateCollections(tooMany, tooMany) }
        val refs = List(BackupIntegrity.MAX_REFERENCES + 1) { "x" to "uri$it" }
        invalid { BackupIntegrity.validateReferences(setOf("x"), refs) }
    }
}
