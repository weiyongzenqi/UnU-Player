package io.github.weiyongzenqi.unuplayer.core.gl

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * WGL shared context: `wglCreateContext` + `wglShareLists`(share=skiko HGLRC) 创建独立 context,
 * 隔离 mpv GL state(不黑块, GL state per-context) + 共享 texture id 给 skiaCtx adoptTextureFrom
 * (不黑屏, texture 是 shareable data, 跨 context 可见)。drawable 直接复用 skiko 的 HDC
 * (同 HDC 交替 wglMakeCurrent 到不同 HGLRC, 不需 Pbuffer/hidden window)。
 *
 * **WGL 基础函数用 JNA 绑 opengl32.dll**(直接导出, 不需扩展/caps); **GL 函数(纹理/FBO)用 LWJGL**。
 * 不用 LWJGL 的 WGL 类(3.4.1 签名与文档不符, createCapabilities 不存在), JNA 自定义接口签名可控。
 *
 * **必须在 Compose 渲染线程调用**(skiko context current, 才能拿 HDC/HGLRC)。
 */

/** JNA 绑定 opengl32.dll 的 WGL 基础函数(HGLRC/HDC 用 Pointer)。internal 供 engine/spike 跨文件用。 */
internal interface WglLib : Library {
    fun wglGetCurrentContext(): Pointer?   // HGLRC
    fun wglGetCurrentDC(): Pointer?        // HDC
    fun wglMakeCurrent(hdc: Pointer?, hglrc: Pointer?): Boolean
    fun wglCreateContext(hdc: Pointer?): Pointer?  // HGLRC
    fun wglDeleteContext(hglrc: Pointer?): Boolean
    fun wglShareLists(hglrcSrc: Pointer?, hglrcDst: Pointer?): Boolean
    fun wglGetProcAddress(name: String): Pointer?   // 扩展/1.2+ 函数地址(GL 1.1 返回 null)
}
internal val wgl: WglLib = Native.load("opengl32", WglLib::class.java)

/** opengl32.dll 模块(GL 1.1 函数 wglGetProcAddress 返回 null 时, 用 getGlobalFunctionAddress 兜底)。 */
private val opengl32Lib: NativeLibrary = NativeLibrary.getInstance("opengl32")

/**
 * mpv get_proc_address 回调用: wglGetProcAddress(扩展/1.2+) + opengl32.dll 导出(GL 1.1 兜底)。
 * wglGetProcAddress 对 GL 1.1 函数(glTexImage2D/glBindTexture/glFlush/...)返回 NULL, 必须兜底否则 mpv 黑屏。
 */
fun lookupGlProcAddress(name: String): Pointer? {
    wgl.wglGetProcAddress(name)?.takeIf { pointer ->
        // WGL 历史实现会用 1、2、3、-1 表示失败，不能把这些 sentinel 交给 libmpv 调用。
        Pointer.nativeValue(pointer) !in setOf(0L, 1L, 2L, 3L, -1L)
    }?.let { return it }
    return runCatching { opengl32Lib.getFunction(name) as Pointer }.getOrNull()
}

/** 独立(共享)WGL context。drawable 复用 skiko HDC(交替 makeCurrent, 不需独立 Pbuffer)。 */
internal class SharedWglContext(
    val hdc: Pointer,    // skiko HDC
    val context: Pointer, // 新 HGLRC(share=skiko HGLRC)
    private val api: WglLib = wgl,
) {
    private var destroyed = false

    @Synchronized
    fun destroy(): Boolean {
        if (destroyed) return true
        // 只解绑当前线程确实持有的本 context，不能误解绑调用方原本 current 的 Skiko context。
        val isCurrent = runCatching {
            api.wglGetCurrentContext()?.sameNativeAddress(context)
        }.getOrDefault(false) == true
        val unbound = !isCurrent || runCatching { api.wglMakeCurrent(null, null) }.getOrDefault(false)
        val deleted = runCatching { api.wglDeleteContext(context) }.getOrDefault(false)
        if (deleted) destroyed = true
        return unbound && deleted
    }
}

private fun Pointer.sameNativeAddress(other: Pointer): Boolean =
    Pointer.nativeValue(this) == Pointer.nativeValue(other)

private fun deleteCreatedContext(api: WglLib, context: Pointer): Boolean =
    runCatching { api.wglDeleteContext(context) }.getOrDefault(false)

/**
 * 创建独立(共享)WGL context。**Compose 渲染线程调**(skiko context current)。
 * wglCreateContext(legacy, opengl32.dll 导出) + wglShareLists(share=skiko HGLRC) 共享 texture。
 * 不用 wglCreateContextAttribsARB(扩展函数, JNA 调用复杂); legacy context 与 skiko compat context
 * 像素格式一致即可 shareLists。
 *
 * @return 独立 context, null=创建失败(skiko 未 current / wglCreateContext 失败 / shareLists 失败)
 */
internal fun createSharedWglContext(api: WglLib = wgl): SharedWglContext? {
    val hdc = api.wglGetCurrentDC() ?: return null
    val skiaCtx = api.wglGetCurrentContext() ?: return null
    val newCtx = api.wglCreateContext(hdc) ?: return null
    val shared = try {
        api.wglShareLists(skiaCtx, newCtx)
    } catch (error: Throwable) {
        deleteCreatedContext(api, newCtx)
        throw error
    }
    if (!shared) {  // share=skiaCtx, 让 texture 跨 context 可见
        deleteCreatedContext(api, newCtx)
        return null
    }
    return SharedWglContext(hdc, newCtx, api)
}
