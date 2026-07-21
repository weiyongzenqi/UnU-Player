// UnU-Player 项目设置

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // 本地 maven 仓库: 预编译的 libmpv-android v1.0.0 AAR 布局在此
        // 见 tools/libmpv/maven/dev/jdtech/mpv/libmpv/1.0.0/
        maven { url = uri(rootDir.resolve("tools/libmpv/maven")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "UnU-Player"

include(":composeApp")
include(":androidApp")
include(":desktopApp")
