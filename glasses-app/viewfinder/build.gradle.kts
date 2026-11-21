plugins {
    id("com.android.application")
}

android {
    namespace = "com.stevencastro.wornview"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stevencastro.wornview"
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

dependencies {
    // CameraX pulls in mismatched Kotlin stdlib versions; align them.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.activity:activity:1.9.3")
}
