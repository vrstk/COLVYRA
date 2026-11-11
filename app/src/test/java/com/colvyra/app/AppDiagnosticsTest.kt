package com.colvyra.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDiagnosticsTest {
    @Test
    fun reportContainsVersionAndSigningCertificateIdentifier() {
        val snapshot = DiagnosticsSnapshot(
            versionName = "0.5.2-m5c",
            versionCode = 13L,
            debugBuild = true,
            androidSdk = 36,
            signingCertificateSha256 = "AA:BB:CC",
            selectedRoot = true,
            rootReadable = true,
            rootWritable = false,
            persistedGrantCount = 2,
            collectionCount = 5,
            referenceCount = 24
        )
        val text = snapshot.supportText()
        assertTrue(text.contains("Version: 0.5.2-m5c (13)"))
        assertTrue(text.contains("Build: debug"))
        assertTrue(text.contains("Android SDK: 36"))
        assertTrue(text.contains("Installed APK certificate SHA-256: AA:BB:CC"))
        assertTrue(text.contains("Collections: 5"))
        assertTrue(text.contains("References: 24"))
        assertTrue(text.contains("Folder write permission: false"))
    }

    @Test
    fun reportNeverIncludesStoragePathsOrAccountIdentifiers() {
        val text = DiagnosticsSnapshot(
            "0.5.2-m5c", 13L, false, 36, "00:11", false, false, false, 0, 0, 0
        ).supportText()
        assertFalse(text.contains("rootUri"))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/storage/"))
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertTrue(text.contains("Build: release"))
    }
}
