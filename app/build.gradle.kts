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
        minSdk = 23
        targetSdk = 28   // Hạ xuống 28 — MIUI cũ hay từ chối APK targetSdk > 30
        versionCode = 2
        versionName = "2.0"

        // Tắt multidex tự động — tránh lỗi parse trên máy cũ
        multiDexEnabled = false
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11  // Hạ từ 17 xuống 11 — tương thích máy cũ hơn
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }

    // Tắt lint block build
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // WebRTC
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
