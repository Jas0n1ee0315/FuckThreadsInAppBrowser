plugins {
    id("com.android.application")
}

import java.util.Properties

val appVersion = "1.0.1"

// Release signing: keystore.properties in the project root (git-ignored), or
// environment variables (for CI). Falls back to the debug key when neither is set.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
// A PKCS12 keystore uses one password for the store and the key, so either field is enough.
val storePasswordValue = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val keyPasswordValue = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
val releaseStorePassword = storePasswordValue ?: keyPasswordValue
val releaseKeyAlias = signingValue("keyAlias", "ANDROID_KEYSTORE_ALIAS")
val releaseKeyPassword = keyPasswordValue ?: storePasswordValue
val hasReleaseKey = releaseStoreFile != null && file(releaseStoreFile).isFile &&
    releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "io.github.jas0n1ee0315.ftiab"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.jas0n1ee0315.fuckthreadsinappbrowser"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("No release keystore configured; signing release with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // Modern Xposed API, provided in-process by the framework (LSPosed).
    compileOnly("io.github.libxposed:api:102.0.0")
    // Finds the obfuscated immersive-browser route helper by its strings.
    implementation("org.luckypray:dexkit:2.2.0")
}
