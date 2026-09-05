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
        versionCode = 4
        versionName = "0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
