package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES30
import android.os.Process
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.render.GlesDanmakuEngine

/**
 * GLES_HB 历史路径的离屏 FBO 渲染器（GL 线程）。
 *
 * 与 [DanmakuGlRenderer] 共享完全相同的：
 * - Shader 程序（vertex + fragment SDF）
 * - QuadMesh（单位四边形 VAO）
 * - SdfAtlas（4096×4096 R8 字形图集）
 * - DanmakuVboBuilder（active→FloatBuffer 实例数据）
 *
 * 唯一差异：渲染到普通 RGBA FBO，再由 [OffscreenGLBridge] 回读 Bitmap；
 * 当前没有使用 Android HardwareBuffer，类名仅为兼容既有实现保留。
 *
 * 线程：构造 + 所有方法必须在专用 GL 线程（THREAD_PRIORITY_DISPLAY）。
 */
internal class HardwareBufferRenderer(
    private val context: Context,
    private val engine: GlesDanmakuEngine,
    private val bridge: OffscreenGLBridge,
) {
    private var shader: ShaderProgram? = null
    private var quad: QuadMesh? = null
    private var atlas: SdfAtlas? = null
    private var vboBuilder: DanmakuVboBuilder? = null
    private var instanceVbo = 0
    private var instanceVboSize = 0

    // ---- Shader 源码（与 DanmakuGlRenderer 相同） ----

    private val vertSource = """
        #version 300 es
        layout(location = 0) in vec2 aQuadPos;
        layout(location = 1) in vec2 aInstancePos;
        layout(location = 2) in vec2 aInstanceScale;
        layout(location = 3) in vec4 aTexRect;
        layout(location = 4) in vec4 aTextColor;
        uniform vec2 uScreenSize;
        out vec2 vTexCoord;
        out vec4 vTextColor;
        flat out vec4 vTexRect;
        void main() {
            vec2 local = aQuadPos * 0.5 + 0.5;
            vec2 pos = aInstancePos + local * aInstanceScale;
            vec2 ndc = (pos / uScreenSize) * 2.0 - 1.0;
            ndc.y = -ndc.y;
            gl_Position = vec4(ndc, 0.0, 1.0);
            vTexCoord = aTexRect.xy + local * (aTexRect.zw - aTexRect.xy);
            vTextColor = aTextColor;
            vTexRect = aTexRect;
        }
    """.trimIndent()

    private val fragSource = """
        #version 300 es
        precision mediump float;
        uniform sampler2D uSdfTexture;
        uniform vec4 uStrokeColor;
        uniform float uStrokeWidth;
        in vec2 vTexCoord;
        in vec4 vTextColor;
        flat in vec4 vTexRect;
        out vec4 fragColor;
        void main() {
            float dist = texture(uSdfTexture, vTexCoord).r;
            float textAlpha = smoothstep(0.5 - 0.06, 0.5 + 0.04, dist);
            float outlineMin = 0.5 - uStrokeWidth;
            float outlineAlpha = smoothstep(outlineMin - 0.06, outlineMin + 0.04, dist);
            vec4 stroke = vec4(uStrokeColor.rgb, uStrokeColor.a * outlineAlpha);
            vec4 text = vec4(vTextColor.rgb, vTextColor.a * textAlpha);
            fragColor = mix(stroke, text, textAlpha);
            if (fragColor.a < 0.01) discard;
        }
    """.trimIndent()

    // ---- 公开方法 ----

    /** 初始化 GL 资源（shader / atlas / VBO）。在 GL 线程首次 makeCurrent 后调用一次。 */
    fun init() {
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        shader = ShaderProgram.compile(
            vertexSource = vertSource, fragmentSource = fragSource,
            attributes = listOf("aQuadPos", "aInstancePos", "aInstanceScale", "aTexRect", "aTextColor"),
            uniforms = listOf("uScreenSize", "uSdfTexture", "uStrokeColor", "uStrokeWidth"),
        )
        quad = QuadMesh()
        val texArr = IntArray(1); GLES30.glGenTextures(1, texArr, 0)
        val texId = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, SdfAtlas.PAGE_SIZE, SdfAtlas.PAGE_SIZE, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
        atlas = SdfAtlas(texId)
        AtlasPreGenerator.loadInto(context, atlas!!)
        val vboArr = IntArray(1); GLES30.glGenBuffers(1, vboArr, 0)
        instanceVbo = vboArr[0]; instanceVboSize = INITIAL_VBO_BYTES
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instanceVboSize, null, GLES30.GL_STREAM_DRAW)
        setupInstanceAttribs()
        vboBuilder = DanmakuVboBuilder()
    }

    /** 渲染一帧弹幕到离屏 FBO，并同步回读到 Bitmap。 */
    fun renderFrame() {
        val prog = shader ?: return; val mesh = quad ?: return
        val at = atlas ?: return; val builder = vboBuilder ?: return
        val active = engine.readActiveRef.get()
        if (active.isEmpty()) return
        at.beginFrame()
        val laneH = engine.currentLaneHeight.coerceAtLeast(1f)
        val (vboData, instanceCount) = builder.build(
            activeSnapshot = active,
            screenW = bridge.alignedWidth.toFloat(),
            screenH = bridge.height.toFloat(),
            laneYFn = { mode, lane, _ ->
                when (mode) { DanmakuMode.BOTTOM -> bridge.height - (lane + 1) * laneH; else -> lane * laneH }
            },
            laneHeight = laneH,
            sdfAtlas = at,
        )
        if (instanceCount == 0) { at.endFrame(); return }
        at.flushDirty()

        bridge.bindFbo()
        GLES30.glViewport(0, 0, bridge.alignedWidth, bridge.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        prog.use()
        GLES30.glUniform2f(prog.uniform("uScreenSize"), bridge.alignedWidth.toFloat(), bridge.height.toFloat())
        GLES30.glUniform1i(prog.uniform("uSdfTexture"), 0)
        GLES30.glUniform4f(prog.uniform("uStrokeColor"), 0f, 0f, 0f, 1f)
        val strokePx = engine.currentStrokeWidth
        val sdfStroke = if (strokePx <= 0f) 0f else (strokePx / engine.currentFontPx.coerceAtLeast(1f)).coerceIn(0f, 0.5f)
        GLES30.glUniform1f(prog.uniform("uStrokeWidth"), sdfStroke)
        val bytesNeeded = vboData.remaining() * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        if (bytesNeeded > instanceVboSize) { instanceVboSize = bytesNeeded * 2; GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instanceVboSize, null, GLES30.GL_STREAM_DRAW) }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytesNeeded, vboData)
        mesh.bind()
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_SHORT, 0, instanceCount)
        mesh.unbind()
        GLES30.glFlush()
        bridge.copyPixelsToBitmap()
        bridge.unbindFbo()
        at.endFrame()
    }

    /** 释放 GL 资源。 */
    fun destroy() {
        quad?.delete(); quad = null
        shader?.delete(); shader = null
        atlas = null; vboBuilder = null
    }

    private fun setupInstanceAttribs() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        val stride = 12 * 4 // 12 floats × 4 bytes
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 0); GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 8); GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 16); GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, stride, 32); GLES30.glEnableVertexAttribArray(4); GLES30.glVertexAttribDivisor(4, 1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val INITIAL_VBO_BYTES = 2 * 1024 * 1024
    }
}
