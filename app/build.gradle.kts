plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "Com.hau.name"
    compileSdk = 34

    defaultConfig {
        applicationId = "Com.hau.name"
        minSdk = 24  // AudioPlaybackCaptureConfiguration yêu cầu API 29,
                     // nhưng app vẫn chạy trên API 26+ (chỉ tắt audio capture < 29)
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CardView — dùng trong activity_controller.xml (nút kết nối lại server cũ)

    // Firebase — Signaling Server cho WebRTC (chỉ dùng khi kết nối đầu tiên)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // WebRTC — stream video + audio + DataChannel điều khiển
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
