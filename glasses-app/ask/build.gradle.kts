plugins {
    id("com.android.application")
}

android {
    namespace = "com.stevencastro.wornask"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stevencastro.wornask"
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

    packaging {
        resources {
            // The Anthropic SDK's dependencies (Jackson, OkHttp, Kotlin) ship
            // overlapping metadata files.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

dependencies {
    // CameraX and the Anthropic SDK pull in mismatched Kotlin stdlib versions; align them.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.25"))

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.activity:activity:1.9.3")

    implementation("com.anthropic:anthropic-java:3.3.0")
}
