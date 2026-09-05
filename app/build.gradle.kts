plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.taimaninviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.taimaninviewer"
        minSdk = 23
        targetSdk = 35
        versionCode = 8
        versionName = "0.7"
    }

    signingConfigs {
        create("stableLocal") {
            storeFile = file("viewer-test-key.jks")
            storePassword = "viewer123"
            keyAlias = "viewer"
            keyPassword = "viewer123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableLocal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
