plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sonic.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sonic.assistant.basic"
        minSdk = 30          // AccessibilityService.takeScreenshot needs API 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device wake word. Free AccessKey from console.picovoice.ai
    implementation("ai.picovoice:porcupine-android:3.0.2")
}
