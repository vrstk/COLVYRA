plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.colvyra.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.colvyra.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.5.2-m5c"
    }

    // Release signing is opt-in and never embeds credentials in the repository.
    // Android's signing identity remains stable only if the same key is reused.
    val releaseStorePath = providers.environmentVariable("COLVYRA_KEYSTORE_PATH").orNull
    val releaseStorePassword = providers.environmentVariable("COLVYRA_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("COLVYRA_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("COLVYRA_KEY_PASSWORD").orNull
    val releaseSigningConfigured = listOf(
        releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("colvyraRelease") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("colvyraRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
