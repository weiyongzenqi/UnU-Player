# OkHttp 针对 GraalVM 与可选 TLS Provider 的适配类不参与 JVM 桌面运行。
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNA 通过反射读取接口方法与 Structure 字段，不能被收缩。
-keep interface io.github.weiyongzenqi.unuplayer.core.player.LibMpv { *; }
-keep interface io.github.weiyongzenqi.unuplayer.core.player.MpvRenderUpdateCallback { *; }
-keep class io.github.weiyongzenqi.unuplayer.core.player.** extends com.sun.jna.Structure { *; }
-keep class com.sun.jna.** { *; }

# WGL 共享纹理适配通过反射连接 JBR API。
-keep class com.jetbrains.SharedTextures { *; }

# sqlite-jdbc 通过 ServiceLoader/JNI 发现驱动与 native 资源。
-keep class org.sqlite.** { *; }

# === 枚举 values/valueOf 反射保留(镜像 Android proguard-android 默认规则) ===
# 2026-08-15 锚点模式变 NFO 事故根因: R8 对 ScanMode 做了 enum unboxing(枚举拆箱成 int),
# 枚举类不再 extends java.lang.Enum, 代码里所有 ScanMode.valueOf(String) 反射调用全部抛
# IllegalArgumentException("not an enum class"), 被 runCatching 吞掉后静默兜底 NFO,
# 造成"写库 ANCHOR 正确 / 读库永远 NFO"。补上标准枚举 keep 规则后 R8 不再拆箱任何枚举,
# valueOf/values/entries 反射语义与 debug 构建一致。勿删。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coil/Ktor 桌面端通过 META-INF/services 加载图片 Fetcher 与 HTTP 引擎。
# ProGuard 只保留服务描述文件却删除 Provider 时，Release 会在海报解码前抛
# ServiceConfigurationError，表现为缓存文件存在但海报卡片变黑。
# Coil 全量类只比收缩后多约 180 KiB，优先保证 Release 与开发运行行为一致。
-keep class coil3.** { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# === kotlinx.serialization(镜像 Android serialization keep, E-05) ===
# 与 androidApp/proguard-rules.pro 的 serialization 段保持一致: 本项目全部序列化走显式
# .serializer(), 收缩器可沿引用链追踪, 理论无需额外 keep; 以下为官方推荐保险,
# 防未来引入 reified serializer<T>() 或边缘裁剪导致桌面 Release 反序列化崩溃。
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 项目内 @Serializable 类: 保住生成的 $$serializer 与 Companion.serializer()。
-keep,includedescriptorclasses class io.github.weiyongzenqi.unuplayer.**$$serializer { *; }
-keepclassmembers class io.github.weiyongzenqi.unuplayer.** { *** Companion; }
-keepclasseswithmembers class io.github.weiyongzenqi.unuplayer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
