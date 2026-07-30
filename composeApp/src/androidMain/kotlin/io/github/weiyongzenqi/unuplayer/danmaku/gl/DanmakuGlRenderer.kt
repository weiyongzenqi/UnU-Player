package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.content.Context
import android.opengl.GLES30
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.render.ActiveDanmaku
import io.github.weiyongzenqi.unuplayer.danmaku.render.GlesDanmakuEngine
import java.nio.FloatBuffer

/**
 * SDF 实例化弹幕渲染器（TextureView + 手动 EGL）。
 *
 * 每帧流程：
 * 1. [SdfAtlas.beginFrame] 重置活跃标记
 * 2. 读引擎 [readActiveRef] 拿活跃弹幕快照
 * 3. [DanmakuVboBuilder.build] 构建实例 FloatBuffer
 * 4. [SdfAtlas.flushDirty] 批量上传脏 glyph
 * 5. glBufferSubData 上传实例 VBO
 * 6. glDrawElementsInstanced 单次 draw call
 * 7. [SdfAtlas.endFrame] 回收未引用 glyph
 *
 * EGL 上下文由宿主 [GlDanmakuTextureView] 管理；此渲染器只负责 GL 资源与绘制逻辑。
 */
internal class DanmakuGlRenderer(
    private val context: Context,
    private val engine: GlesDanmakuEngine,
) {

    // ---- GL 资源 ----
    private var shader: ShaderProgram? = null
    private var quad: QuadMesh? = null
    private var sdfAtlas: SdfAtlas? = null
    private var vboBuilder: DanmakuVboBuilder? = null

    // 实例 VBO（stream，每帧更新）
    private var instanceVbo = 0
    private var instanceVboSize = 0 // bytes allocated

    // 屏幕尺寸
    private var screenW = 1
    private var screenH = 1

    // ---- Shader 源码 ----

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
            // aQuadPos 在 [-1,1]，映射到 [0,1] 再乘 scale + 位移
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
        uniform vec4 uStrokeColor;   // 描边颜色（全局统一）
        uniform float uStrokeWidth;  // SDF 描边阈值 0.0-1.0（全局统一）

        in vec2 vTexCoord;
        in vec4 vTextColor;
        flat in vec4 vTexRect;

        out vec4 fragColor;

        void main() {
            float dist = texture(uSdfTexture, vTexCoord).r;
            // SDF 中心值 0.5 = 字形边缘
            // 文字 alpha：dist > 0.5 的区域
            float textAlpha = smoothstep(0.5 - 0.06, 0.5 + 0.04, dist);
            // 描边 alpha：dist 在 (0.5 - uStrokeWidth) 附近的区域
            float outlineMin = 0.5 - uStrokeWidth;
            float outlineAlpha = smoothstep(outlineMin - 0.06, outlineMin + 0.04, dist);
            // 描边在文字之下（textAlpha 覆盖描边）
            vec4 stroke = vec4(uStrokeColor.rgb, uStrokeColor.a * outlineAlpha);
            vec4 text = vec4(vTextColor.rgb, vTextColor.a * textAlpha);
            fragColor = mix(stroke, text, textAlpha);
            if (fragColor.a < 0.01) discard;
        }
    """.trimIndent()

    // ---- 磁盘缓存 ----
    // ---- 公开方法（由 TextureView 宿主调用） ----

    fun onSurfaceCreated(width: Int, height: Int) {
        GLES30.glClearColor(0f, 0f, 0f, 0f)

        // 开启预乘 Alpha 混合
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // 编译 shader
        shader = ShaderProgram.compile(
            vertexSource = vertSource,
            fragmentSource = fragSource,
            attributes = listOf("aQuadPos", "aInstancePos", "aInstanceScale", "aTexRect", "aTextColor"),
            uniforms = listOf("uScreenSize", "uSdfTexture", "uStrokeColor", "uStrokeWidth"),
        )
        quad = QuadMesh()

        // 创建 SDF 图集纹理
        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        val texId = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // 初始化为全 0.0（远处 = SDF 值 0，无字形显示）
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
            SdfAtlas.PAGE_SIZE, SdfAtlas.PAGE_SIZE, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
        )

        sdfAtlas = SdfAtlas(texId)

        // 优先从预生成缓存恢复（含 4000+ CJK + ASCII；格式：pageBytes + glyph→slot 映射）
        val restored = AtlasPreGenerator.loadInto(context, sdfAtlas!!)
        if (!restored) {
            // 缓存不存在：后台生成 4000 CJK（首次安装），当前帧先生成 ASCII 兜底
            AtlasPreGenerator.ensureAsync(context) { /* 生成完成后下次启动自动加载 */ }
            for (cp in 32..126) {
                sdfAtlas!!.ensureGlyph(cp)
            }
            sdfAtlas!!.flushDirty()
        }
        // 若已从预生成缓存恢复，无需再 flush（数据已在 deserializeFromBytes 中上传 GL）

        // 创建实例 VBO
        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        instanceVbo = vboArr[0]
        instanceVboSize = INITIAL_VBO_BYTES
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instanceVboSize, null, GLES30.GL_STREAM_DRAW)

        // 绑定实例属性到 VAO
        setupInstanceAttribs()

        vboBuilder = DanmakuVboBuilder()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        screenW = width
        screenH = height
        GLES30.glViewport(0, 0, width, height)
    }

    fun onDrawFrame(width: Int, height: Int) {
        screenW = width; screenH = height
        val prog = shader ?: return
        val mesh = quad ?: return
        val atlas = sdfAtlas ?: return
        val builder = vboBuilder ?: return

        val active = engine.readActiveRef.get()
        if (active.isEmpty()) return

        atlas.beginFrame()

        val laneH = engine.currentLaneHeight.coerceAtLeast(1f)

        val (vboData, instanceCount) = builder.build(
            activeSnapshot = active,
            screenW = screenW.toFloat(),
            screenH = screenH.toFloat(),
            laneYFn = { mode, lane, _ ->
                when (mode) {
                    DanmakuMode.BOTTOM -> screenH - (lane + 1) * laneH
                    else -> lane * laneH
                }
            },
            laneHeight = laneH,
            sdfAtlas = atlas,
        )

        if (instanceCount == 0) {
            atlas.endFrame()
            return
        }

        // 批量上传脏 glyph
        atlas.flushDirty()

        // 清除 + 绘制
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        prog.use()

        // Uniforms
        GLES30.glUniform2f(prog.uniform("uScreenSize"), screenW.toFloat(), screenH.toFloat())
        GLES30.glUniform1i(prog.uniform("uSdfTexture"), 0) // GL_TEXTURE0
        GLES30.glUniform4f(prog.uniform("uStrokeColor"), 0f, 0f, 0f, 1f) // 黑色描边（统一）
        // SDF 描边阈值：strokePx / fontPx（0 描边 = 阈值 0 = 无描边）
        val strokePx = engine.currentStrokeWidth
        val fontPx = engine.currentFontPx.coerceAtLeast(1f)
        val sdfStroke = if (strokePx <= 0f) 0f else (strokePx / fontPx).coerceIn(0f, 0.5f)
        GLES30.glUniform1f(prog.uniform("uStrokeWidth"), sdfStroke)

        // 上传实例 VBO
        val bytesNeeded = vboData.remaining() * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        if (bytesNeeded > instanceVboSize) {
            // 扩容
            instanceVboSize = bytesNeeded * 2
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instanceVboSize, null, GLES30.GL_STREAM_DRAW)
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytesNeeded, vboData)

        // 绑定 quad VAO（含实例 VBO attribute）
        mesh.bind()

        // 一次 draw call 绘制所有字符
        GLES30.glDrawElementsInstanced(
            GLES30.GL_TRIANGLES, mesh.indexCount,
            GLES30.GL_UNSIGNED_SHORT, 0, instanceCount,
        )

        mesh.unbind()

        atlas.endFrame()
        // 预生成 atlas 不可变，运行时罕见字在内存中生成，不写盘
    }

    /** EGL 销毁前清理 GL 资源。 */
    fun onSurfaceDestroyed() {
        quad?.delete(); quad = null
        shader?.delete(); shader = null
        sdfAtlas = null; vboBuilder = null
    }

    /**
     * 在已绑定的 VAO 上设置实例化 attribute（VBO 1 的 divisor=1）。
     * 调用前需已 bindVertexArray 且 instanceVbo 已创建。
     */
    private fun setupInstanceAttribs() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        val stride = FLOATS_PER_INSTANCE * 4 // 12 floats × 4 bytes

        // location=1: aInstancePos (2f)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribDivisor(1, 1)

        // location=2: aInstanceScale (2f) at offset 8
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribDivisor(2, 1)

        // location=3: aTexRect (4f) at offset 16
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribDivisor(3, 1)

        // location=4: aTextColor (4f) at offset 32
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, stride, 32)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribDivisor(4, 1)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        /** 每个实例 12 floats = 48 bytes。 */
        private const val FLOATS_PER_INSTANCE = 12
        /** 初始 VBO 容量：2MB（约 40000 实例）。 */
        private const val INITIAL_VBO_BYTES = 2 * 1024 * 1024
    }
}
