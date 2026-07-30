package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 纯 CPU SDF（Signed Distance Field）字形生成器。
 *
 * 无 GL 依赖，可在任何线程安全使用。每个实例持有可复用的渲染缓冲区
 * （Bitmap / Canvas / Paint / EDT 网格），单字符生成开销 ~1-2ms。
 *
 * 生成流程：Canvas 渲染字符到 64×64 高分辨率位图 → 8SSEDT 计算距离场 → 写入目标缓冲区。
 *
 * @param sourceSize SDF 高分辨率源图尺寸（默认 64px）
 * @param maxDist   距离场最大有效距离，超出钳位（默认 16px）
 */
internal class SdfGenerator(
    private val sourceSize: Int = 64,
    private val maxDist: Float = 16f,
) {
    /** 临时渲染位图：ALPHA_8 格式，sourceSize × sourceSize。 */
    private val bitmap: Bitmap = Bitmap.createBitmap(sourceSize, sourceSize, Bitmap.Config.ALPHA_8)

    /** 包裹 [bitmap] 的 Android Canvas。 */
    private val canvas: Canvas = Canvas(bitmap)

    /** 字形绘制画笔：抗锯齿、系统默认字体、textSize=sourceSize×0.75（留 12.5% 边距防裁剪）。 */
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = sourceSize * 0.75f
        isFakeBoldText = false
    }

    /** 8SSEDT 临时网格：sourceSize × sourceSize 浮点数，每次生成覆盖写入。 */
    private val edtGrid: FloatArray = FloatArray(sourceSize * sourceSize)

    /** √2 ≈ 1.414，对角线距离权重。 */
    private val sqrt2: Float = 1.41421356f

    /**
     * 为单个 [codepoint] 生成 SDF，写入外部 [pageBytes] 的指定区域。
     *
     * 调用者负责管理 [pageBytes] 的并发访问（SdfAtlas 在同一线程调用，天然安全）。
     *
     * @param codepoint  Unicode 码点
     * @param pageBytes  目标 atlas 像素缓冲区（GL_R8 格式，字节数组）
     * @param pageSize   atlas 纹理宽度（px），用于行优先索引计算
     * @param outRow     目标区域在 atlas 中的起始行（px）
     * @param outCol     目标区域在 atlas 中的起始列（px）
     * @param glyphSize  输出字形尺寸（px，含 gutter）
     */
    fun generate(
        codepoint: Int,
        pageBytes: ByteArray,
        pageSize: Int,
        outRow: Int,
        outCol: Int,
        glyphSize: Int,
    ) {
        // 1. 渲染字符到高分辨率 bitmap
        val charStr = String(Character.toChars(codepoint))
        bitmap.eraseColor(0)

        val fm = paint.fontMetrics
        // 垂直居中基线计算
        val baseline = (sourceSize - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val textWidth = paint.measureText(charStr)
        val x = (sourceSize - textWidth) / 2f
        canvas.drawText(charStr, x, baseline, paint)

        // 2. 读取像素 → 二值掩码（ALPHA_8：值 > 127 视为前景）
        val pixels = IntArray(sourceSize * sourceSize)
        bitmap.getPixels(pixels, 0, sourceSize, 0, 0, sourceSize, sourceSize)
        val mask = BooleanArray(sourceSize * sourceSize) { i -> (pixels[i] and 0xFF) > 127 }

        // 3. 8SSEDT 计算有符号距离场 → edtGrid
        computeEdt(mask)

        // 4. 降采样到 glyphSize × glyphSize，写入 pageBytes
        val scale = sourceSize.toFloat() / glyphSize
        for (gy in 0 until glyphSize) {
            for (gx in 0 until glyphSize) {
                val srcX = (gx * scale).toInt().coerceIn(0, sourceSize - 1)
                val srcY = (gy * scale).toInt().coerceIn(0, sourceSize - 1)
                val dist = edtGrid[srcY * sourceSize + srcX]

                // 有符号距离 → [0, 1]（0 = 无限远的外部，0.5 = 字形边缘，1 = 无限深的内部）
                val normalized = ((dist / maxDist + 1f) / 2f).coerceIn(0f, 1f)
                val row = outRow + gy
                val col = outCol + gx
                pageBytes[row * pageSize + col] = (normalized * 255f).toInt().toByte()
            }
        }
    }

    // ---- 8SSEDT（8-point Signed Sequential Euclidean Distance Transform） ----

    /**
     * 计算二值掩码的有符号欧氏距离场，结果写入 [edtGrid]。
     *
     * 正值 = 像素在字形内部，距边缘的距离；负值 = 像素在字形外部。
     * 两遍扫描（左上→右下、右下→左上），每像素检查 4 个前驱邻居（4-邻接 + 4-对角共 8-方向）。
     */
    private fun computeEdt(mask: BooleanArray) {
        val n = sourceSize
        val large = n * n.toFloat()
        val grid = edtGrid

        // Pass 0: 初始化 —— 边缘像素=0，内部=+large，外部=-large
        for (y in 0 until n) {
            for (x in 0 until n) {
                val idx = y * n + x
                val inside = mask[idx]
                // 边缘判定：4-邻接中至少一个与自身不同
                val left = if (x > 0) mask[idx - 1] else false
                val right = if (x < n - 1) mask[idx + 1] else false
                val up = if (y > 0) mask[idx - n] else false
                val down = if (y < n - 1) mask[idx + n] else false
                val isEdge = inside && (!left || !right || !up || !down)
                grid[idx] = if (isEdge) 0f else if (inside) large else -large
            }
        }

        // Pass 1: 左上 → 右下
        for (y in 0 until n) {
            for (x in 0 until n) {
                propagate(grid, n, x, y, -1, 0, 1f)       // 左
                propagate(grid, n, x, y, -1, -1, sqrt2)   // 左上
                propagate(grid, n, x, y, 0, -1, 1f)        // 上
                propagate(grid, n, x, y, 1, -1, sqrt2)    // 右上
            }
        }

        // Pass 2: 右下 → 左上
        for (y in n - 1 downTo 0) {
            for (x in n - 1 downTo 0) {
                propagate(grid, n, x, y, 1, 0, 1f)         // 右
                propagate(grid, n, x, y, 1, 1, sqrt2)     // 右下
                propagate(grid, n, x, y, 0, 1, 1f)         // 下
                propagate(grid, n, x, y, -1, 1, sqrt2)    // 左下
            }
        }

        // Pass 3: 赋符号 —— 内部为正，外部为负
        for (y in 0 until n) {
            for (x in 0 until n) {
                val idx = y * n + x
                val ad = if (grid[idx] >= 0f) grid[idx] else -grid[idx]
                grid[idx] = if (mask[idx]) ad else -ad
            }
        }
    }

    /**
     * 用邻居 (x+dx, y+dy) 的距离 + 权重 更新当前像素网格值。
     * 邻居超出边界或当前像素已有更小距离时跳过。
     */
    private fun propagate(
        grid: FloatArray,
        n: Int,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        weight: Float,
    ) {
        val nx = x + dx; val ny = y + dy
        if (nx !in 0 until n || ny !in 0 until n) return

        val idx = y * n + x
        val nd = grid[ny * n + nx]
        val ndAd = if (nd >= 0f) nd else -nd
        val cand = ndAd + weight

        val cur = grid[idx]
        val curAd = if (cur >= 0f) cur else -cur
        if (cand < curAd) {
            grid[idx] = if (nd >= 0f) cand else -cand
        }
    }
}
