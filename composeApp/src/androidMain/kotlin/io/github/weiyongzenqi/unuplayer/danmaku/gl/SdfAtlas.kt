package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.opengl.GLES30
import android.util.Log

/**
 * SDF 字符图集：4096×4096 GL_R8 单通道纹理（16MB，≈14400 字形槽位）。
 *
 * 每个字形固定 32×32 px（含 2px 双边 gutter）。格子布局 120×120，空闲栈分配，无碎片。
 *
 * SDF 生成委托 [SdfGenerator]（纯 CPU），图集管理（packing / LRU / 纹理上传 / 序列化）由此类负责。
 * 支持离线模式（textureId=0）：无 GL 调用，仅做 CPU 侧 glyph 管理 + 序列化。
 *
 * 线程：带纹理的方法需在 GL 线程调用；纯 CPU 操作（generateSdf、序列化等）可在任意线程。
 */
internal class SdfAtlas(
    private var textureId: Int,
    private val pageSize: Int = PAGE_SIZE,
    private val glyphSize: Int = GLYPH_SIZE,
    private val gutter: Int = GUTTER,
) {
    /** 格子行列数 */
    private val gridCols: Int = (pageSize - gutter) / (glyphSize + gutter)
    private val gridRows: Int = (pageSize - gutter) / (glyphSize + gutter)
    val maxSlots: Int = gridCols * gridRows

    /** 空闲槽位栈（FILO） */
    private val freeStack = ArrayDeque<Int>((0 until maxSlots).toList())

    /** glyph codepoint → slotIndex */
    private val glyphToSlot = LinkedHashMap<Int, Int>(MAX_CACHE, 0.75f, true) // access-order LRU

    /** slotIndex → 是否被当前帧活跃弹幕引用（驱逐保护） */
    private val slotActive = BooleanArray(maxSlots)

    /** 待上传的脏 slot 集合 */
    private val dirtySlots = LinkedHashSet<Int>()

    /** 自上次磁盘缓存保存后是否有新增的非 ASCII glyph。 */
    var hasNewContent: Boolean = false
        private set

    /** 整页字节缓冲区：pageSize × pageSize 字节（GL_R8），唯一数据源。 */
    private val pageBytes = ByteArray(pageSize * pageSize)

    /** SDF 字形生成器（纯 CPU，委托）。 */
    private val sdfGenerator = SdfGenerator(sourceSize = SDF_SOURCE_SIZE, maxDist = MAX_DIST)

    /** uploadSlot 复用缓冲区（gutter+glyphSize × gutter+glyphSize 直接字节），避免每 slot 分配。 */
    private val uploadBuffer: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect((glyphSize + gutter) * (glyphSize + gutter))

    // ---- 公开 API ----

    /**
     * 确保 [codepoint] 的 SDF 字形在 atlas 中就绪，返回其 slotIndex。
     * 缓存命中返回已有槽位；miss 则分配新槽位、生成 SDF、标记 dirty。
     * 若图集已满且无可驱逐空闲槽，返回 -1。
     */
    fun ensureGlyph(codepoint: Int): Int {
        glyphToSlot[codepoint]?.let { return it } // LRU 访问序更新

        // 分配空闲槽
        val slot = allocateSlot() ?: return -1
        glyphToSlot[codepoint] = slot

        // 生成 SDF 并标记脏
        generateSdf(codepoint, slot)
        dirtySlots.add(slot)
        if (codepoint > 127) hasNewContent = true // 非 ASCII 新增，需写盘

        return slot
    }

    /** 标记 [slot] 被活跃弹幕引用（驱逐保护）。 */
    fun markActive(slot: Int) {
        if (slot in 0 until maxSlots) slotActive[slot] = true
    }

    /** 帧开始时重置活跃标记（GL 线程在 onDrawFrame 开头调用）。 */
    fun beginFrame() {
        slotActive.fill(false)
    }

    /** 帧结束时：回收未被引用的槽位，清理过期 LRU 条目。 */
    fun endFrame() {
        val iter = glyphToSlot.entries.iterator()
        while (iter.hasNext()) {
            val (cp, slot) = iter.next()
            if (!slotActive[slot]) {
                // 该 glyph 不再被引用 → 回收槽位，移出 LRU
                freeSlot(slot)
                iter.remove()
            }
        }
    }

    /**
     * 上传所有脏 glyph 到 GL 纹理（批量 glTexSubImage2D）。
     * 每帧最多调用一次。
     */
    fun flushDirty() {
        if (textureId == 0 || dirtySlots.isEmpty()) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        for (slot in dirtySlots) uploadSlot(slot)
        dirtySlots.clear()
    }

    /** 将现有 pageBytes 全量上传到 GL 纹理（用于离线生成后绑定纹理时初始化）。 */
    fun uploadFullPage() {
        if (textureId == 0) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        val buf = java.nio.ByteBuffer.allocateDirect(pageSize * pageSize)
        buf.put(pageBytes, 0, pageSize * pageSize)
        buf.flip()
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, pageSize, pageSize, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
    }

    /**
     * 序列化完整图集到字节数组（含像素数据 + glyph→slot 映射）。
     * 格式：[pixelData: pageSize*pageSize bytes] [entryCount: Int32 LE] [entries: (cp:Int32,slot:Int32)*N]
     *
     * 在线模式（textureId != 0）：先 flushDirty 保证纹理最新，然后 glReadPixels 读回。
     * 离线模式（textureId == 0）：直接从 pageBytes 序列化（纯 CPU，无 GL 调用）。
     */
    fun serializeToBytes(): ByteArray {
        if (textureId != 0) {
            flushDirty()
            // GPU 回读路径：兼容旧行为（在线 atlas 纹理可能已被 GPU 修改）
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textureId, 0)
            val buf = java.nio.ByteBuffer.allocateDirect(pageSize * pageSize)
            GLES30.glReadPixels(0, 0, pageSize, pageSize, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.get(pageBytes) // 同步 pageBytes 与 GPU 纹理
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        }
        // 离线模式：pageBytes 已是唯一数据源，直接序列化

        val entryCount = glyphToSlot.size
        val mapBytes = java.io.ByteArrayOutputStream()
        val intBuf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun writeInt(v: Int) { intBuf.clear(); intBuf.putInt(v); mapBytes.write(intBuf.array(), 0, 4) }
        writeInt(entryCount)
        for ((cp, slot) in glyphToSlot) { writeInt(cp); writeInt(slot) }

        val out = java.io.ByteArrayOutputStream()
        out.write(pageBytes)
        out.write(mapBytes.toByteArray())
        hasNewContent = false
        return out.toByteArray()
    }

    /** 从序列化字节恢复图集（pageBytes + 映射表）。若在线模式同时上传到 GL 纹理。 */
    fun deserializeFromBytes(bytes: ByteArray) {
        val pixelLen = pageSize * pageSize
        if (bytes.size < pixelLen + 4) return // 格式不对，走干净初始化
        bytes.copyInto(pageBytes, 0, 0, pixelLen)

        if (textureId != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            val buf = java.nio.ByteBuffer.allocateDirect(pixelLen)
            buf.put(pageBytes, 0, pixelLen)
            buf.flip()
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, pageSize, pageSize, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        }

        // 恢复映射表
        freeStack.clear(); glyphToSlot.clear()
        for (i in 0 until maxSlots) freeStack.addLast(i)
        val intBuf = java.nio.ByteBuffer.wrap(bytes, pixelLen, bytes.size - pixelLen).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        if (intBuf.remaining() < 1) return
        val entryCount = intBuf.get()
        for (i in 0 until entryCount) {
            if (intBuf.remaining() < 2) break
            val cp = intBuf.get(); val slot = intBuf.get()
            if (slot in 0 until maxSlots) {
                glyphToSlot[cp] = slot
                freeStack.remove(slot)
            }
        }
    }

    /** 获取 slot 的纹理坐标（归一化 UV），含 gutter 去除偏移。 */
    fun getTexRect(slot: Int): TexRect {
        val col = slot % gridCols
        val row = slot / gridCols
        val x = gutter + col * (glyphSize + gutter)
        val y = gutter + row * (glyphSize + gutter)
        // 实际有效像素在 gutter 之后
        val effectiveSize = glyphSize.toFloat() // 含 gutter 的完整 cell
        return TexRect(
            u0 = x.toFloat() / pageSize,
            v0 = y.toFloat() / pageSize,
            u1 = (x + effectiveSize) / pageSize,
            v1 = (y + effectiveSize) / pageSize,
        )
    }

    /** 获取 slot 中心像素在 page 中的坐标（用于 SDF 数据写入）。 */
    private fun slotPixelOrigin(slot: Int): Pair<Int, Int> {
        val col = slot % gridCols
        val row = slot / gridCols
        return gutter + col * (glyphSize + gutter) to gutter + row * (glyphSize + gutter)
    }

    // ---- 内部方法 ----

    private fun allocateSlot(): Int? {
        if (freeStack.isNotEmpty()) return freeStack.removeLast()
        // 驱逐最旧的未被活跃引用的条目
        return evictOne()
    }

    private fun freeSlot(slot: Int) {
        freeStack.addLast(slot)
    }

    private fun evictOne(): Int? {
        val iter = glyphToSlot.entries.iterator()
        while (iter.hasNext()) {
            val (cp, slot) = iter.next()
            if (!slotActive[slot]) {
                iter.remove()
                // 清空纹理区域（设为 0 = 无穷远，不显示）
                clearSlot(slot)
                dirtySlots.add(slot)
                return slot
            }
        }
        Log.w("SdfAtlas", "SDF atlas full: all ${glyphToSlot.size} glyphs active, cannot evict")
        return null
    }

    private fun clearSlot(slot: Int) {
        val (ox, oy) = slotPixelOrigin(slot)
        val size = glyphSize + gutter
        for (y in oy until oy + size) {
            for (x in ox until ox + size) {
                val idx = y * pageSize + x
                if (idx in pageBytes.indices) pageBytes[idx] = 0
            }
        }
    }

    /** 渲染单个字符 SDF → 写入 [pageBytes] 对应槽位。委托 [SdfGenerator]。 */
    private fun generateSdf(codepoint: Int, slot: Int) {
        val (ox, oy) = slotPixelOrigin(slot)
        sdfGenerator.generate(codepoint, pageBytes, pageSize, /*outRow=*/oy, /*outCol=*/ox, glyphSize)
    }

    /** 将 [slot] 的 pageBytes 区域上传到 GL 纹理。使用预分配 [uploadBuffer] 复用：每行先拷贝到临时 ByteArray 再写入 Direct Buffer。 */
    private fun uploadSlot(slot: Int) {
        if (textureId == 0) return
        val (ox, oy) = slotPixelOrigin(slot)
        val size = glyphSize + gutter
        val rowBytes = ByteArray(size)
        val buf = uploadBuffer.apply { clear() }
        for (y in oy until oy + size) {
            val srcOffset = y * pageSize + ox
            pageBytes.copyInto(rowBytes, 0, srcOffset, srcOffset + size)
            buf.put(rowBytes)
        }
        buf.flip()
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, ox, oy, size, size, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
    }

    companion object {
        /** Atlas 纹理尺寸（4096px = 16MB R8，≈14400 字形槽位）。 */
        const val PAGE_SIZE = 4096
        /** 每个 glyph 的 SDF 尺寸（含 gutter）。 */
        const val GLYPH_SIZE = 32
        /** 双边 gutter 宽度（防止双线性采样相邻 glyph 边缘渗透）。 */
        const val GUTTER = 2
        /** SDF 高分辨率源图尺寸。 */
        const val SDF_SOURCE_SIZE = 64
        /** 距离场最大有效距离（像素），超出钳位。 */
        const val MAX_DIST = 16f
        /** LRU 缓存容量上限。 */
        const val MAX_CACHE = 3600

        /** 创建离线 SdfAtlas（textureId=0，纯 CPU 模式），用于预生成。 */
        fun createOffline(pageSize: Int = PAGE_SIZE): SdfAtlas = SdfAtlas(0, pageSize)
    }
}

/** SDF 图集中一个 glyph 的归一化纹理坐标矩形。 */
internal data class TexRect(val u0: Float, val v0: Float, val u1: Float, val v1: Float)
