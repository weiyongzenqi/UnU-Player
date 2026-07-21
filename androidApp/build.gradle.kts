// androidApp: Android 壳工程, 生成 APK
// 依赖 composeApp, 托管 MainActivity 与 Compose 入口
//
// 注: AGP 9.0+ 内置 Kotlin 支持, 不再需要 org.jetbrains.kotlin.android 插件。
// 见 https://kotl.in/gradle/agp-built-in-kotlin

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// 正式发布签名: 从 ~/.gradle/gradle.properties 读路径/密码(不入库)。
// 未配置(unu.storeFile 空)时 release 回退 debug 签名, 便于本地装机测试。
val unuStoreFile = findProperty("unu.storeFile") as String? ?: ""
val unuStorePass = findProperty("unu.storePassword") as String? ?: ""
val unuKeyAlias = findProperty("unu.keyAlias") as String? ?: "unu"
val unuKeyPass = findProperty("unu.keyPassword") as String? ?: ""

android {
    namespace = "io.github.weiyongzenqi.unuplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.weiyongzenqi.unuplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"   // 先只打 arm64(libmpv 预编译含 4 ABI, 后续可放开)
        }
    }

    signingConfigs {
        create("release") {
            if (unuStoreFile.isNotBlank()) {
                storeFile = file(unuStoreFile)
                storePassword = unuStorePass
                keyAlias = unuKeyAlias
                keyPassword = unuKeyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true    // R8: 缩减+优化+混淆, 减包体+略快启动(对播放功耗无影响, native 在 libmpv)
            isShrinkResources = true  // 配合 minify, 移除未引用资源
            // 配了 unu.storeFile 用正式签名; 没配回退 debug 签名(本地装机测试, 与 debug 同证书可覆盖装)。
            signingConfig = if (unuStoreFile.isNotBlank()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
