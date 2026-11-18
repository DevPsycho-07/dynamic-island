plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rsv.dynamicisland"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rsv.dynamicisland"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "2.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload build: signed with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU type (plus a universal one): app-arm64-v8a-release.apk, app-universal-release.apk, …
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
        buildConfig = true
    }
}
