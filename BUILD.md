# 构建说明

本文档说明如何从源码构建 UnU-Player 的 Android 与 Windows 版本。

## 环境要求

- **JDK 21**（Android 与桌面构建均需）
- **Android 构建**：Android SDK（含 NDK / CMake，用于 libmpv）
- **Windows 构建**：Windows x64；打包安装程序需 [Inno Setup](https://jrsoftware.org/isinfo.php)
- 仓库 `tools/` 为本地工具目录，放置 SDK、内核产物、Inno Setup 等；**所有工具链、下载物与缓存一律放 `tools/` 对应子目录，禁止散落仓库根或 `tools/` 根**（临时文件进 `tools/tmp/` 用完即清，下载物进 `tools/downloads/`，Gradle 缓存归 `~/.gradle`）。

> 本地低内存机器可用 `--no-daemon --max-workers=1` 降低 Gradle 占用。

---

## Android

### 1. libmpv AAR（播放内核）

Android 端依赖本地 maven 仓库中的 `dev.jdtech.mpv:libmpv:1.0.0` AAR。

- **（首选：直接下载 CI release）** 前往 [weiyongzenqi/libmpv-android Releases](https://github.com/weiyongzenqi/libmpv-android/releases)，**release 资产直接就是 `.aar` 文件，下载即用**（ffmpeg 8.1.2 + mpv 0.41.0 + Vulkan/OpenSSL/AAudio 定制构建）。放入 `tools/libmpv/maven/dev/jdtech/mpv/libmpv/1.0.0/libmpv-1.0.0.aar`（版本号恒为 1.0.0），并同目录放置最小 POM（文件名 `libmpv-1.0.0.pom`，全文如下；POM 缺失时 Gradle 解析失败）。也可用命令：

  ```powershell
  gh release download --repo weiyongzenqi/libmpv-android --pattern "*.aar" --dir tools/downloads
  ```

  POM 内容（UTF-8，照抄）：

  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <project xmlns="http://maven.apache.org/POM/4.0.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      <modelVersion>4.0.0</modelVersion>
      <groupId>dev.jdtech.mpv</groupId>
      <artifactId>libmpv</artifactId>
      <version>1.0.0</version>
      <packaging>aar</packaging>
      <description>libmpv-android v1.0.0 (vulkan-enabled) 预编译 AAR, 仅 Android 框架 + kotlin stdlib 依赖(由消费方模块提供)。</description>
  </project>
  ```

- **（兜底：自行重编）**：运行仓库根目录的 `build-libmpv.sh`，它会自动克隆 [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)、打补丁（Vulkan HDR、FFmpeg TLS 后端 mbedTLS→OpenSSL）并编译 arm64 AAR，产物替换到 `tools/libmpv/maven/`。耗时约 20–40 分钟，需要 Android NDK / CMake。

```bash
./build-libmpv.sh            # 无 ../libmpv-android 时自动克隆 v1.0.0
./build-libmpv.sh --existing # 仅用已有 ../libmpv-android
```

CR-017 只修改 JNI 包装层时，可在 Windows 使用现有 AAR 中的 `libmpv.so`/FFmpeg 库作为链接输入，避免重编完整媒体栈（该脚本与补丁仅存在于开发仓 `tools/libmpv/`，公开快照不含 `tools/`， 公开构建走上方 release 下载或 `build-libmpv.sh`）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\libmpv\rebuild-player-wrapper.ps1
```

该脚本要求相邻 `../libmpv-android` 为 `custom-r10` 的后代，自动应用仓库内安全补丁，固定使用 NDK 29.0.14206865/CMake 4.1.2，并校验下载的 mpv/FFmpeg 头文件 SHA-256；替换 AAR 前还会比较新旧 JNI 导出符号、SONAME、动态依赖和所有非 `libplayer.so` 条目哈希。完整重编脚本也会应用同一补丁，并拒绝仍含原始 logcat 格式字符串的产物。

### 2. 构建 APK

```powershell
$env:ANDROID_HOME = (Resolve-Path 'tools\android-sdk').Path
.\gradlew.bat --no-daemon :androidApp:assembleRelease
```

产物：`androidApp/build/outputs/apk/release/androidApp-release.apk`（arm64-v8a）。

release 默认启用 R8（缩减 + 资源收缩 + 混淆），并要求在用户级 Gradle 属性中配置
`unu.storeFile`、`unu.storePassword`、`unu.keyAlias` 和 `unu.keyPassword`；`unu.storeFile` 必须指向
实际 keystore 文件，任一配置缺失或无效时任务会失败。

仅需验证本地 R8/打包链时，可显式使用 debug 证书：

```powershell
.\gradlew.bat --no-daemon :androidApp:assembleRelease -Punu.allowDebugReleaseSigning=true
```

该 opt-in 产物不是正式发布包，不得上传或分发；发布前仍须核对正式签名证书 SHA-256、ABI、R8 和覆盖安装。
不要把 `unu.allowDebugReleaseSigning=true` 写入用户级或仓库级 `gradle.properties`，应只在单次本地命令中传入。

### 3. 真机媒体服务器重定向测试

连接已授权 USB 安装的 arm64 Android 设备后执行：

```powershell
$env:ANDROID_HOME = (Resolve-Path 'tools\android-sdk').Path
.\gradlew.bat :composeApp:connectedAndroidDeviceTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.weiyongzenqi.unuplayer.core.player.MpvHttpRedirectDeviceTest"
```

测试在设备进程内创建两个不同端口的 localhost origin，只使用固定假 canary：FOLLOW 对照组必须访问
第二 origin 并转发自定义头，`HttpRedirectPolicy.DENY` 组的媒体服务器头只能到达首个 origin，第二
origin 必须在 3 秒观察窗内零命中。测试不读取或保存真实服务器 token，也不能替代真实 Jellyfin/Emby 播放验收。

---

## Windows x64

### 1. libmpv dll（播放内核）

桌面端经 JNA 调用随包的 `libmpv-2.dll`。该 dll 取自公开构建仓库 [zhongfly/mpv-winbuild](https://github.com/zhongfly/mpv-winbuild)（GPL，基于上游 mpv）：

1. 前往 [zhongfly/mpv-winbuild Releases](https://github.com/zhongfly/mpv-winbuild/releases)，下载 **`mpv-dev-x86_64-<日期>-git-<hash>.7z`**（libmpv 开发包；不要下 `mpv-x86_64-*` 播放器完整包）。
2. 解压取出以下文件放入 `tools/libmpv/win64/`：
   - `libmpv-2.dll`（开发包内）
   - `vulkan-1.dll`（Khronos 官方 [Vulkan Loader](https://vulkan.lunarg.com/sdk/home)，zhongfly 构建静态导入 Vulkan，运行前需先加载；也可从本机 `System32` 复制）
   - `VulkanRT-License.txt`（Vulkan Runtime 许可证，随包分发）

> `tools/libmpv/win64/` 不入库（可下载产物）；构建时 `stageWindowsLibmpv` 会将其中 `*.dll` 与 `VulkanRT-License.txt` 复制到分发资源目录，三文件缺一不可。

### 2. 构建安装程序

```powershell
.\gradlew.bat --no-daemon :desktopApp:packageReleaseWindowsExe
```

该任务调用 Inno Setup（便携版可置于 `tools/inno-setup/`，脚本 `installer/windows/UnU-Player.iss` + `scripts/windows/prepare-inno.ps1`）打包。

Windows 包版本与运行时 Jellyfin 客户端版本统一读取 `desktopApp/src/main/resources/app-version.txt`；升级版本时只修改该文件，格式必须为 `x.y.z`。

产物：`desktopApp/build/compose/binaries/main-release/exe/UnU-Player-Setup-<version>-x64.exe`。

---

## 目录约定

| 路径 | 用途 | 入库 |
|---|---|---|
| `tools/libmpv/maven/` | 自建 Android libmpv AAR + 最小 POM | ✅ |
| `tools/libmpv/win64/` | Windows libmpv dll（下自 zhongfly） | ❌ |
| `tools/android-sdk/`、`tools/gradle-home/`、`tools/inno-setup/` 等 | 本地工具链 | ❌ |
| `tools/research/` | 调研报告 | ✅ |
| `tools/downloads/` | 一次性下载物 | ❌ |
| `tools/tmp/` | 临时文件（用完即清） | ❌ |

**目录纪律**：工具/SDK/下载物/缓存一律放 `tools/` 对应子目录，禁止散落仓库根或 `tools/` 根；Gradle 缓存归 `~/.gradle`（或显式启用的 `tools/gradle-home`）。
