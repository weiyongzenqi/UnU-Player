import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// desktopApp: 桌面(Linux/Windows)壳工程, 生成可运行 JVM 应用
// 依赖 composeApp(desktop variant), 托管 Compose Desktop 入口(main + Window)
// 对齐 androidApp 壳模式: composeApp 是共享 KMP 库, 壳工程做薄装配
//
// 阶段1: 极简窗口占位(不调 App); 阶段3 App 提 commonMain 后接入完整 UI。

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.desktop.currentOs)
    // Compose UI 依赖: composeApp 的 implementation 不传递, 壳需显式声明。
    // 用 compose DSL(非 libs 硬坐标): compose 插件做依赖替换解析到正确 variant
    // (libs 别名硬坐标 org.jetbrains.compose.material3:material3:1.11.1 在 mavenCentral 无 .pom)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.ui)
}

val generatedAppResourcesRoot = layout.buildDirectory.dir("generated/compose-app-resources")
val bundledWindowsMpvDir = rootProject.layout.projectDirectory.dir("tools/libmpv/win64")
val desktopPackageVersion = "0.1"

val stageWindowsLibmpv by tasks.registering(Sync::class) {
    from(bundledWindowsMpvDir) {
        include("*.dll")
        include("VulkanRT-License.txt")
    }
    includeEmptyDirs = false
    into(generatedAppResourcesRoot.map { it.dir("windows-x64") })
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    doFirst {
        val requiredFiles = listOf("libmpv-2.dll", "vulkan-1.dll", "VulkanRT-License.txt")
        val missingFiles = requiredFiles.filterNot { bundledWindowsMpvDir.file(it).asFile.isFile }
        check(missingFiles.isEmpty()) {
            "Windows 打包缺少 libmpv 运行时文件: ${missingFiles.joinToString()}；请放入 tools/libmpv/win64"
        }
    }
}

compose.desktop.application {
    mainClass = "io.github.weiyongzenqi.unuplayer.app.MainKt"  // Kotlin 顶层 main() 编译为 MainKt(文件名+Kt), 非 Main 类
    nativeDistributions {
        packageName = "UnU-Player"
        packageVersion = desktopPackageVersion
        vendor = "UnU-Player"
        description = "面向动漫媒体库的 WebDAV 视频播放器"
        targetFormats(TargetFormat.Msi)
        appResourcesRootDir.set(generatedAppResourcesRoot)
        modules("java.instrument", "java.management", "java.sql", "jdk.unsupported")
        // Linux deb/rpm 与 Windows msi/exe 打包参数阶段5/6 按需补(图标/维护者等)
        windows {
            // 应用图标(随包 icon.ico, 由 scripts/windows/generate-icons.ps1 从 img/icon.png 生成)
            iconFile.set(project.file("icons/icon.ico"))
            // MSI ProductVersion 要求三段 x.y.z, packageVersion "0.1" 不符, 单独设; Inno EXE 仍用 packageVersion 0.1。
            msiPackageVersion = "0.1.0"
            // 固定升级链；后续只递增 packageVersion，MSI 即可识别并替换旧版。
            upgradeUuid = "7A225EAA-62EC-3756-B2B1-53A74246592D"
            // jpackage/WiX 负责创建并在卸载时移除两个快捷方式。
            shortcut = true
            menu = true
            menuGroup = "UnU-Player"
        }
    }

    buildTypes.release.proguard {
        // native/JNA 播放器只做收缩，不做耗时且收益有限的字节码重写优化。
        optimize.set(false)
        configurationFiles.from(project.file("proguard-rules.pro"))
    }
}

// 仅开发期 Gradle run 使用 tools；发布包由 compose.application.resources.dir 定位随包 DLL。
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        systemProperty("unu.libmpv.dir", rootProject.file("tools/libmpv/win64").absolutePath)
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageWindowsLibmpv)
}

val innoCompiler = rootProject.layout.projectDirectory.file("tools/inno-setup/ISCC.exe")
val innoScript = rootProject.layout.projectDirectory.file("installer/windows/UnU-Player.iss")
val releaseAppImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/UnU-Player")
val releaseWindowsExeDir = layout.buildDirectory.dir("compose/binaries/main-release/exe")
val releaseWindowsExe = releaseWindowsExeDir.map {
    it.file("UnU-Player-Setup-$desktopPackageVersion-x64.exe")
}

// release app-image 由 Compose/jpackage 生成，Inno Setup 只负责当前用户级 Windows 安装体验。
val packageReleaseWindowsExe by tasks.registering(Exec::class) {
    group = "compose desktop"
    description = "生成 UnU-Player x64 Windows release EXE 安装包"
    dependsOn("createReleaseDistributable")

    inputs.dir(releaseAppImageDir)
    inputs.file(innoScript)
    inputs.file(innoCompiler)
    inputs.property("appVersion", desktopPackageVersion)
    outputs.file(releaseWindowsExe)

    doFirst {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "packageReleaseWindowsExe 只能在 Windows 上运行"
        }

        val compilerFile = innoCompiler.asFile
        val scriptFile = innoScript.asFile
        val appImage = releaseAppImageDir.get().asFile
        val outputDir = releaseWindowsExeDir.get().asFile

        check(compilerFile.isFile) {
            "缺少 Inno Setup 编译器: $compilerFile；请先运行 scripts/windows/prepare-inno.ps1"
        }
        check(scriptFile.isFile) { "缺少 Inno Setup 脚本: $scriptFile" }
        check(appImage.resolve("UnU-Player.exe").isFile) {
            "缺少 release app-image: $appImage"
        }
        outputDir.mkdirs()

        commandLine(
            compilerFile.absolutePath,
            "/Qp",
            "/DAppVersion=$desktopPackageVersion",
            "/DAppImage=${appImage.absolutePath}",
            "/DOutputDir=${outputDir.absolutePath}",
            scriptFile.absolutePath,
        )
    }

    doLast {
        check(releaseWindowsExe.get().asFile.isFile) {
            "Inno Setup 未生成预期安装包: ${releaseWindowsExe.get().asFile}"
        }
    }
}
