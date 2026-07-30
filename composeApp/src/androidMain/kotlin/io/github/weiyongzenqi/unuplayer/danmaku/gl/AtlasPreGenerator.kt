package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * SDF 图集一次性预生成器。
 *
 * 首次安装（或缓存文件丢失）时，在后台线程生成 4000+ 常用字符的 SDF 字形，
 * 并一次性写入磁盘文件（~16.1MB）。后续启动直接从文件 mmap 恢复，**永不追加写入**。
 *
 * 预生成字符集（~4400 个 codepoint）：
 * - ASCII 可打印 32-126（95 字符）
 * - CJK 标点 U+3000..U+303F（64 字符）
 * - 平假名 U+3040..U+3096（86 字符）
 * - 片假名 U+30A0..U+30FA（91 字符）
 * - 全角 ASCII U+FF00..U+FF5E（95 字符）
 * - 最常用 CJK U+4E00..U+4E00+3899（3900 字符）
 * - 补充：常见符号、箭头、制表符等 U+2500..U+25FF（256 字符）
 *
 * 线程：单线程 Executor（后台，最低优先级），进程级只跑一次。
 */
internal object AtlasPreGenerator {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AtlasPreGen").apply {
            priority = Process.THREAD_PRIORITY_LOWEST
        }
    }

    /** 预生成字符集（延迟计算，首次访问时构建）。 */
    private val pregenCodepoints: List<Int> by lazy {
        val set = LinkedHashSet<Int>()
        (32..126).forEach { set.add(it) }                 // ASCII 可打印
        (0x3000..0x303F).forEach { set.add(it) }           // CJK 标点
        (0x3040..0x3096).forEach { set.add(it) }           // 平假名
        (0x30A0..0x30FA).forEach { set.add(it) }           // 片假名
        (0xFF00..0xFF5E).forEach { set.add(it) }           // 全角 ASCII
        (0x2500..0x25FF).forEach { set.add(it) }           // 制表符/几何图形
        (0x4E00..0x4E00 + 3899).forEach { set.add(it) }    // 最常用 CJK 3900 字
        set.toList()
    }

    private fun cacheFile(context: Context): File =
        File(context.filesDir, CACHE_PATH)

    /**
     * 确保预生成缓存文件存在。
     *
     * 若文件已存在（大小 ≥ 16MB），立即返回 true（已在后台线程外调用时检查）。
     * 否则提交后台任务生成，返回 false（调用者应走运行时懒生成路径）。
     *
     * 生成完成后的通知通过 [onComplete] 回调。
     */
    fun ensureAsync(context: Context, onComplete: (Boolean) -> Unit) {
        val file = cacheFile(context)
        if (file.exists() && file.length() >= SdfAtlas.PAGE_SIZE * SdfAtlas.PAGE_SIZE) {
            onComplete(true)
            return
        }

        executor.execute {
            try {
                val ok = generateNow(context)
                onComplete(ok)
            } catch (e: Exception) {
                Log.e("AtlasPreGen", "Pre-generation failed: ${e.message}")
                // 失败时删除可能损坏的文件
                cacheFile(context).delete()
                onComplete(false)
            }
        }
    }

    /**
     * 同步生成预生成缓存（在调用者线程执行，用于冷启动快速路径）。
     *
     * @return 缓存文件路径，若生成失败返回 null
     */
    fun getCacheFileOrNull(context: Context): File? {
        val file = cacheFile(context)
        if (file.exists() && file.length() >= SdfAtlas.PAGE_SIZE * SdfAtlas.PAGE_SIZE) return file
        return null
    }

    /**
     * 加载预生成缓存到 [atlas]。若文件不存在或损坏，返回 false（atlas 保持空状态）。
     * 调用者随后可通过 [SdfAtlas.ensureGlyph] 运行时懒生成。
     */
    fun loadInto(context: Context, atlas: SdfAtlas): Boolean {
        val file = getCacheFileOrNull(context) ?: return false
        return try {
            val bytes = file.readBytes()
            atlas.deserializeFromBytes(bytes)
            true
        } catch (e: Exception) {
            file.delete()
            false
        }
    }

    // ---- 内部 ----

    private const val CACHE_PATH = "sdf_atlas/atlas_4096_pregenerated.bin"

    /** 实际生成逻辑（在后台线程执行）。 */
    private fun generateNow(context: Context): Boolean {
        val atlas = SdfAtlas.createOffline()
        var generated = 0
        val total = pregenCodepoints.size

        for (cp in pregenCodepoints) {
            if (atlas.ensureGlyph(cp) < 0) {
                // 图集已满（理论不应发生：14400 槽位 > 4400 字符）
                Log.w("AtlasPreGen", "Atlas full after $generated/$total glyphs")
                break
            }
            generated++
        }

        val bytes = atlas.serializeToBytes() // 离线模式：直接从 pageBytes 序列化
        val file = cacheFile(context)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        Log.i("AtlasPreGen", "Generated $generated glyphs, wrote ${file.length()} bytes")
        return true
    }
}
