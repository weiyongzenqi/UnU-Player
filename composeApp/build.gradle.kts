// composeApp: KMP + Compose Multiplatform 共享模块
// commonMain 放跨平台代码, androidMain 放 Android 实现(含 libmpv 引擎)
//
// AGP 9.0+ KMP 库用 com.android.kotlin.multiplatform.library 插件,
// Android 配置移到 kotlin { android { } } 块内, 无顶层 android { }。

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // expect/actual classes 在 Kotlin 2.1 仍为 Beta, 需显式 opt-in。
    // 作用于所有 target(commonMain 的 expect object Crypto 需要)。
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // === Android 目标 ===
    android {
        namespace = "io.github.weiyongzenqi.unuplayer"
        compileSdk = 36
        minSdk = 26

        // AGP 9 KMP library 默认不处理 Android 资源; 显式启用,
        // Compose 多平台资源(files/licenses 许可证全文)才能打包进 assets 供 Res.readBytes 读取。
        androidResources { enable = true }

        withHostTest {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    // === 桌面目标(JVM/Linux) ===
    // 桌面端共用 commonMain 的 UI/逻辑, desktopMain 提供平台 actual。
    // 不在此配 compose.desktop.application(可运行应用壳在 :desktopApp 模块)。
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    // === 共享 source set ===
    sourceSets {
        commonMain {
            dependencies {
                // Kotlin 生态
                implementation(libs.kotlin.stdlib)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                // Compose 多平台
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                // Compose resources 运行时(Res.readBytes 读取 files/licenses 许可证全文)
                implementation(compose.components.resources)
                // 图片加载(海报墙)
                implementation(libs.coil3)
                // JetBrains 多平台导航/lifecycle
                implementation(libs.navigation.compose)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                // Ktor(多平台 HTTP, commonMain 声明, 各平台提供引擎)
                implementation(libs.ktor.core)
                implementation(libs.ktor.logging)
                // SQLDelight 协程扩展(播放记录 Flow 支持)
                implementation(libs.sqldelight.coroutines)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.coroutines.android)
                // Ktor Android 引擎
                implementation(libs.ktor.okhttp)
                // 播放内核(libmpv-android, 本地 maven 仓库)
                implementation(libs.libmpv)
                // AndroidX(Compose 互操作)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                // 持久化(DataStore, Storage 实现)
                implementation(libs.androidx.datastore.preferences)
                // SAF DocumentFile(本地文件浏览 tab 遍历 tree URI)
                implementation(libs.androidx.documentfile)
                // SQLDelight Android driver(播放记录数据库)
                implementation(libs.sqldelight.android.driver)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.coroutines.swing)       // Compose Desktop(Swing) 互操作(Dispatchers.Swing)
                // Ktor JVM 引擎: okhttp 是 JVM 通用, 桌面直接复用(与 Android 同)
                implementation(libs.ktor.okhttp)
                // SQLDelight jdbc-driver + sqlite-jdbc: 桌面播放记录数据库
                implementation(libs.sqldelight.jdbc.driver)
                implementation(libs.sqlite.jdbc)
                // coil3 桌面网络层(海报墙图片加载)
                implementation(libs.coil3.network.ktor)
                // JNA: 桌面 libmpv 绑定(播放器内核)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                // 注: WGL 诊断 spike(WglSharedContext.kt)用 JNA 直绑 opengl32.dll, 不依赖 LWJGL。
                // LWJGL 依赖已于上线前清理(CR-044 后全仓零 org.lwjgl 引用, 且其 windows natives 徒增安装包体积)。
                // 注: compose.desktop.currentOs(Swing/AWT 平台绑定)放 desktopApp 壳, 此库不需
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // 像素级 Skia renderer 测试需要当前 Windows native runtime；生产依赖仍由 desktopApp 壳提供。
                runtimeOnly(compose.desktop.currentOs)
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // commonTest { ... }  // 测试后续补
    }
}

// Compose resources: 生成 Res 类(关于页许可证全文经 Res.readBytes 读取 files/licenses/*.txt)。
// 默认 auto 仅在检测到代码引用时生成, 此处显式 always; 包名用默认(与资源打包目录一致, 否则运行时读不到)。
compose {
    resources {
        generateResClass = always
    }
}

// SQLDelight 播放记录数据库(KMP; Android 用 android-driver; .sq 在 commonMain/sqldelight)
sqldelight {
    databases {
        create("UnuDatabase") {
            packageName.set("io.github.weiyongzenqi.unuplayer.playback")
        }
    }
}
