plugins {
    id("com.android.application")
}

android {
    namespace = "com.stevencastro.wornlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stevencastro.wornlauncher"
        // M6 glasses run Android 9 (API 28).
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
