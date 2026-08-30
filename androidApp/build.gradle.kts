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
// 本地 R8 烟测如确需 debug 证书，必须显式传 -Punu.allowDebugReleaseSigning=true。
val unuStoreFile = findProperty("unu.storeFile") as String? ?: ""
val unuStorePass = findProperty("unu.storePassword") as String? ?: ""
val unuKeyAlias = findProperty("unu.keyAlias") as String? ?: ""
val unuKeyPass = findProperty("unu.keyPassword") as String? ?: ""
val unuAllowDebugReleaseSigning =
    (findProperty("unu.allowDebugReleaseSigning") as String?)?.toBooleanStrictOrNull() == true
val releaseStoreFile = unuStoreFile.takeIf { it.isNotBlank() }?.let { file(it) }
val releaseSigningConfigurationIssues = buildList {
    if (unuStoreFile.isBlank()) add("unu.storeFile")
    else if (releaseStoreFile?.isFile != true) add("unu.storeFile(文件不存在)")
    if (unuStorePass.isBlank()) add("unu.storePassword")
    if (unuKeyAlias.isBlank()) add("unu.keyAlias")
    if (unuKeyPass.isBlank()) add("unu.keyPassword")
}
val hasFormalReleaseSigning = releaseSigningConfigurationIssues.isEmpty()

android {
    namespace = "io.github.weiyongzenqi.unuplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.weiyongzenqi.unuplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.2.3"
        ndk {
            abiFilters += "arm64-v8a"   // 先只打 arm64(libmpv 预编译含 4 ABI, 后续可放开)
        }
    }

    signingConfigs {
        create("release") {
            if (hasFormalReleaseSigning) {
                storeFile = releaseStoreFile
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
            signingConfig = when {
                hasFormalReleaseSigning -> signingConfigs.getByName("release")
                unuAllowDebugReleaseSigning -> signingConfigs.getByName("debug")
                else -> null
            }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "验证 Android release 使用完整正式签名，或显式启用本地 debug 签名烟测"
    doLast {
        if (!hasFormalReleaseSigning && !unuAllowDebugReleaseSigning) {
            throw GradleException(
                "Android release 正式签名配置缺失或无效: ${releaseSigningConfigurationIssues.joinToString()}。" +
                    "完整配置四项属性，或仅为本地 R8 烟测显式传 " +
                    "-Punu.allowDebugReleaseSigning=true。",
            )
        }
    }
}

// 挂在所有 release 编译/打包共用的前置任务上，避免缺签名时先白跑 R8，也覆盖 APK 与 AAB。
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
