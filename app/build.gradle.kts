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
        // targetSdk 28: 保持 <29 以豁免 Android 10+ 的 W^X 执行限制（数据目录可执行），
        // 同时 >=24 满足 Android 15/16 的最低安装 targetSdk 门槛 → 安卓 7~16 全可用
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
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
        // 发布流程不跑 lint（本地网络环境下 lint 依赖下载不稳定，CI 上另行校验）
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
        // bootstrap.zip / frpc.bin 不做二次压缩，加快首次解包
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
