plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qk.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qk.app"
        minSdk = 24
        // targetSdk = 28：低于 29 才享有 Android 10+ 的「数据目录可执行」豁免（W^X），
        // 同时 >= 24 满足 Android 15/16 的最低安装 targetSdk 门槛 → 覆盖 Android 7~16。
        targetSdk = 28
        versionCode = 7
        versionName = "1.1.5"
        ndk {
            // 只带 arm64：termux bootstrap 与 frpc 均为 aarch64
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/quicksite.keystore")
            storePassword = "quicksite2026"
            keyAlias = "quicksite"
            keyPassword = "quicksite2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        noCompress += listOf("zip", "bin", "so")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
