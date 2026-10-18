plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deskbuddy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deskbuddy"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.14.0-design.1"
        manifestPlaceholders["appLabel"] = "桌面伙伴"
        manifestPlaceholders["autoStartEnabled"] = "true"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".design"
            manifestPlaceholders["appLabel"] = "桌面伙伴"
            manifestPlaceholders["autoStartEnabled"] = "false"
        }
        release {
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 人脸检测：端侧运行，不上传画面
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.4")
    // 离线中文语音识别（Vosk），模型随 APK 内置，首次使用时解压
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
