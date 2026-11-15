plugins {
    id("com.android.application")
}

val appVersion = "1.0.0"

android {
    namespace = "io.github.jas0n1ee0315.ftiab"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jas0n1ee0315.fuckthreadsinappbrowser"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release keystore configured: sign with the debug key so the APK installs.
            signingConfig = signingConfigs.getByName("debug")
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
