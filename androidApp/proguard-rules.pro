# UnU-Player ProGuard 规则

# libmpv-android: native 通过 JNI 按名引用类/方法/常量, 整包 keep。
# 覆盖 MPVLib + 嵌套 MpvFormat/MpvEvent/MpvLogLevel + EventObserver/LogObserver 接口。
-keep class dev.jdtech.mpv.** { *; }

# MPVLib.EventObserver / LogObserver 的实现类(native 回调入口), keep 防 JNI 方法名被混淆失效。
-keep class io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine$MpvEventBridge { *; }
-keep class io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine$MpvLogBridge { *; }

# Kotlin 元数据(枚举 name 等) + 注解 + 内部类/签名信息, 保险保留。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# === kotlinx.serialization(R8 保险) ===
# 本项目全部序列化走显式 .serializer()(DandanplayApi/ManualMatchCache/LocalDirectoryRepository),
# R8 可沿引用链追踪, 理论无需额外 keep; 以下为官方推荐保险, 防未来引入 reified serializer<T>() 或边缘裁剪。
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

# === JNA(集照生成: AndroidEpisodeThumbGenerator 直调 libmpv.so) ===
# JNA 通过反射读取接口方法与 Structure @JvmField 字段顺序, 不能被 R8 收缩。
# 对齐 desktopApp/proguard-rules.pro; 漏 keep 会导致 release 的 Native.load("mpv") 绑定失败,
# generate 早早返回 null(无网络请求、无集照生成), debug 包不走 R8 故不复现。
-keep interface io.github.weiyongzenqi.unuplayer.core.player.LibMpvAndroid { *; }
-keep interface io.github.weiyongzenqi.unuplayer.core.player.MpvRenderUpdateCallback { *; }
-keep class io.github.weiyongzenqi.unuplayer.core.player.** extends com.sun.jna.Structure { *; }
-keep class com.sun.jna.** { *; }
# JNA 含 AWT 桥(Native$AWT, 桌面窗口句柄用), Android 无 java.awt, 忽略缺失类(R8 不报错, 不调用即可)。
-dontwarn java.awt.**

# === SMBJ(Android 可选依赖) ===
# 当前 SMB 登录使用 AuthenticationContext(NTLM)，不走桌面 JGSS/Kerberos；MBassador 也未启用 EL 过滤。
# 两组 API 均不在 Android SDK 中，仅忽略对应不可达可选路径的缺失类告警。
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
