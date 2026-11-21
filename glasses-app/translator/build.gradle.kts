plugins {
    id("com.android.application")
}

android {
    namespace = "com.stevencastro.worntranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stevencastro.worntranslate"
        // M6 glasses run Android 9 (API 28).
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // The glasses are arm64; skip the other ABIs' ML Kit native libs.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // CameraX and ML Kit pull in different Kotlin stdlib versions; align them.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.activity:activity:1.9.3")

    // On-device ML Kit. The text-recognition model is bundled in the APK, so
    // OCR needs neither Play services nor a network connection.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
}
