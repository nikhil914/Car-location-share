plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// "Send to Car": one APK for both ends. On the phone it adds "Send to car" to the
// Share menu; on the head unit it runs a receiver that opens Waze.
android {
    namespace = "com.nik.sendtocar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nik.sendtocar"
        minSdk = 23
        targetSdk = 34
        // Bump both for every release: CI publishes v<versionName> when that tag doesn't exist yet,
        // and Obtainium updates from those releases.
        versionCode = 3
        versionName = "0.2.0"
    }

    // Release key comes from CI secrets (SIGNING_KEYSTORE_BASE64 / SIGNING_PASSWORD), never from git.
    // Every release must use the same key or Android refuses the update.
    val keystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = System.getenv("SIGNING_ALIAS") ?: "sendtocar"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Without the key (forks, local builds) the release APK is debug-signed: fine to try, not to update.
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false }
}

dependencies {
    // QR codes for the diagnostics report.
    implementation("com.google.zxing:core:3.5.3")
    // Phone side: Google's QR scanner (no camera permission; needs Play services, which phones have).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
