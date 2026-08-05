# 构建说明

本文档说明如何从源码构建 UnU-Player 的 Android 与 Windows 版本。

## 环境要求

- **JDK 21**（Android 与桌面构建均需）
- **Android 构建**：Android SDK（含 NDK / CMake，用于 libmpv）
- **Windows 构建**：Windows x64；打包安装程序需 [Inno Setup](https://jrsoftware.org/isinfo.php)
- 仓库 `tools/` 为本地工具目录，放置 SDK、内核产物、Inno Setup 等

> 本地低内存机器可用 `--no-daemon --max-workers=1` 降低 Gradle 占用。

---

## Android

### 1. libmpv AAR（播放内核）

Android 端依赖本地 maven 仓库中的 `dev.jdtech.mpv:libmpv:1.0.0` AAR。

- **（自行重编）**：运行仓库根目录的 `build-libmpv.sh`，它会自动克隆 [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)、打补丁（Vulkan HDR、FFmpeg TLS 后端 mbedTLS→OpenSSL）并编译 arm64 AAR，产物替换到 `tools/libmpv/maven/`。耗时约 20–40 分钟，需要 Android NDK / CMake。

```bash
./build-libmpv.sh            # 无 ../libmpv-android 时自动克隆 v1.0.0
./build-libmpv.sh --existing # 仅用已有 ../libmpv-android
```

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

1. 前往 [zhongfly/mpv-winbuild Releases](https://github.com/zhongfly/mpv-winbuild/releases)，下载含 `libmpv-2.dll` 的 Windows x64 构建包。
2. 取出以下文件放入 `tools/libmpv/win64/`：
   - `libmpv-2.dll`
   - `vulkan-1.dll`（zhongfly 构建静态导入 Vulkan，运行前需先加载）
   - `VulkanRT-License.txt`（Vulkan Runtime 许可证，随包分发）

> `tools/libmpv/win64/` 不入库（可下载产物）；构建时 `stageWindowsLibmpv` 会将其中 `*.dll` 与 `VulkanRT-License.txt` 复制到分发资源目录。

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
| `tools/libmpv/maven/` | 自建 Android libmpv AAR | ✅ |
| `tools/libmpv/win64/` | Windows libmpv dll（下自 zhongfly） | ❌ |
| `tools/android-sdk/`、`tools/gradle-home/`、`tools/inno-setup/` 等 | 本地工具链 | ❌ |
