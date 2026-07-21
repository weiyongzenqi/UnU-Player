# OkHttp 针对 GraalVM 与可选 TLS Provider 的适配类不参与 JVM 桌面运行。
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNA 通过反射读取接口方法与 Structure 字段，不能被收缩。
-keep interface io.github.weiyongzenqi.unuplayer.core.player.LibMpv { *; }
-keep interface io.github.weiyongzenqi.unuplayer.core.player.MpvGetProcAddress { *; }
-keep interface io.github.weiyongzenqi.unuplayer.core.player.MpvRenderUpdateCallback { *; }
-keep class io.github.weiyongzenqi.unuplayer.core.player.** extends com.sun.jna.Structure { *; }
-keep class com.sun.jna.** { *; }

# WGL 共享纹理适配通过反射连接 JBR API。
-keep class com.jetbrains.SharedTextures { *; }

# sqlite-jdbc 通过 ServiceLoader/JNI 发现驱动与 native 资源。
-keep class org.sqlite.** { *; }

# Coil/Ktor 桌面端通过 META-INF/services 加载图片 Fetcher 与 HTTP 引擎。
# ProGuard 只保留服务描述文件却删除 Provider 时，Release 会在海报解码前抛
# ServiceConfigurationError，表现为缓存文件存在但海报卡片变黑。
# Coil 全量类只比收缩后多约 180 KiB，优先保证 Release 与开发运行行为一致。
-keep class coil3.** { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
