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
        minSdk = 24          // Android 7.0 — yêu cầu tối thiểu
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        multiDexEnabled = false

        // Hỗ trợ TẤT CẢ chip: ARM 32/64, x86, x86_64 (Huawei dùng ARM64 + ARM32)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        abortOnError = false
    }

    // Build APK riêng cho từng ABI — ai dùng chip gì tải cái đó
    splits {
        abi {
            isEnable = false  // Tắt split, dùng fat APK (1 file cài được mọi chip)
        }
    }

    packaging {
        // Giữ tất cả .so của WebRTC cho mọi ABI
        jniLibs {
            useLegacyPackaging = true
        }
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

    // WebRTC — fat AAR chứa tất cả ABI
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
