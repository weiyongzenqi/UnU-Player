package io.github.weiyongzenqi.unuplayer.ui.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * ┌─────────────────────────────────────────┐
 * │ FPS  60  (1s均:58  5s均:59  60Hz)     │
 * │ 帧间隔  正常:95%  丢1帧:4%  丢2+:1%    │
 * │ ▂▃▂▃▄▃▂▂▃▄▅▃▂▂▃▄▅▄▃▂▃▄▅▄▃▂▂▃▄▅ (波形) │
 * │ Java堆: 45M   Native: 82M   PSS: 210M   │
 * │ CPU: 12%   ⏱帧: 2.1ms                  │
 * └─────────────────────────────────────────┘
 */
@Composable
fun PerfMonitorOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val am = remember { ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    val refreshRateHz = view.display?.refreshRate
        ?.takeIf { it.isFinite() && it in MIN_REFRESH_RATE_HZ..MAX_REFRESH_RATE_HZ }
        ?: DEFAULT_REFRESH_RATE_HZ
    var frameMetrics by remember(refreshRateHz) {
        mutableStateOf(PerfFrameMetrics.empty(refreshRateHz))
    }
    var memoryMetrics by remember { mutableStateOf(PerfMemoryMetrics()) }
    var cpuPct by remember { mutableIntStateOf(0) }

    // Choreographer callback 只表示 UI 帧间隔，不是 CPU/GPU render duration。原始帧逐次写入普通对象，
    // 每 250ms 才发布一次 Compose 快照，避免监测器自己造成逐帧重组。
    LaunchedEffect(refreshRateHz) {
        val accumulator = PerfFrameAccumulator(refreshRateHz)
        var previousCpuMs = Process.getElapsedCpuTime()
        var previousCpuWallNanos = System.nanoTime()
        while (true) {
            val frameTimeNanos = withFrameNanos { it }
            accumulator.record(frameTimeNanos)?.let { frameMetrics = it }
            if (frameTimeNanos - previousCpuWallNanos >= CPU_SAMPLE_INTERVAL_NANOS) {
                val nowCpu = Process.getElapsedCpuTime()
                val nowWall = System.nanoTime()
                cpuPct = processCpuPercent(
                    cpuDeltaMillis = nowCpu - previousCpuMs,
                    wallDeltaNanos = nowWall - previousCpuWallNanos,
                )
                previousCpuMs = nowCpu
                previousCpuWallNanos = nowWall
            }
        }
    }

    // PSS 查询是 binder 调用，固定 5 秒在后台采样，不再按 60 帧在主线程执行。
    LaunchedEffect(am) {
        while (true) {
            memoryMetrics = withContext(Dispatchers.Default) {
                val runtime = Runtime.getRuntime()
                val pss = runCatching {
                    am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss
                }.getOrNull()
                PerfMemoryMetrics(
                    javaMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MIB,
                    nativeMb = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MIB,
                    pssMb = pss?.div(1024f) ?: 0f,
                )
            }
            delay(MEMORY_SAMPLE_INTERVAL_MS)
        }
    }

    val periodMs = frameMetrics.refreshPeriodMs
    val fpsColor = when {
        frameMetrics.fpsNow >= refreshRateHz * 0.9f -> Color(0xFF4CAF50)
        frameMetrics.fpsNow >= refreshRateHz * 0.6f -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    val intervalColor = when (frameIntervalBucket(frameMetrics.frameIntervalMs, periodMs)) {
        FrameIntervalBucket.ON_TIME -> Color(0xFF4CAF50)
        FrameIntervalBucket.ONE_MISSED -> Color(0xFFFFC107)
        FrameIntervalBucket.MULTIPLE_MISSED -> Color(0xFFF44336)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xDD1A1A2E))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Row 1: FPS
        Row {
            Label("FPS", Color.White.copy(alpha = 0.6f)); Spacer(Modifier.width(4.dp))
            Value("${frameMetrics.fpsNow.roundToInt()}", fpsColor)
            Spacer(Modifier.width(10.dp))
            Label("1s均", Color.White.copy(alpha = 0.4f)); Spacer(Modifier.width(2.dp))
            Value("${frameMetrics.fps1s.roundToInt()}", Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(8.dp))
            Label("5s均", Color.White.copy(alpha = 0.4f)); Spacer(Modifier.width(2.dp))
            Value("${frameMetrics.fps5s.roundToInt()}", Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(8.dp))
            Value("${refreshRateHz.roundToInt()}Hz", Color.White.copy(alpha = 0.5f))
        }

        // Row 2: 帧间隔与按当前刷新周期归一化的分布
        Row {
            Label("间隔", Color.White.copy(alpha = 0.55f)); Spacer(Modifier.width(2.dp))
            Value("${frameMetrics.frameIntervalMs.roundToInt()}ms", intervalColor)
            Spacer(Modifier.width(8.dp))
            Label("正常", Color(0xFF4CAF50)); Spacer(Modifier.width(2.dp))
            Value("${frameMetrics.onTimePercent}%", Color(0xFF4CAF50))
            Spacer(Modifier.width(8.dp))
            Label("丢1帧", Color(0xFFFFC107)); Spacer(Modifier.width(2.dp))
            Value("${frameMetrics.oneMissPercent}%", Color(0xFFFFC107))
            Spacer(Modifier.width(8.dp))
            Label("丢2+", Color(0xFFF44336)); Spacer(Modifier.width(2.dp))
            Value("${frameMetrics.multiMissPercent}%", Color(0xFFF44336))
            Spacer(Modifier.width(8.dp))
            Label("CPU", Color.White.copy(alpha = 0.55f)); Spacer(Modifier.width(2.dp))
            Value("${cpuPct}%", Color.White.copy(alpha = 0.6f))
        }

        // Row 3: 波形图
        val history = frameMetrics.historyMs
        if (history.size > 1) {
            Spacer(Modifier.height(2.dp))
            Canvas(modifier = Modifier.size(width = 160.dp, height = 28.dp)) {
                val graphMaxMs = maxOf(MIN_GRAPH_MAX_MS, periodMs * 4f)
                val step = size.width / (history.size - 1)
                val scale = size.height / graphMaxMs
                for (i in 1 until history.size) {
                    val previous = history[i - 1]
                    val current = history[i]
                    val y1 = (size.height - previous.coerceAtMost(graphMaxMs) * scale).coerceAtLeast(0f)
                    val y2 = (size.height - current.coerceAtMost(graphMaxMs) * scale).coerceAtLeast(0f)
                    drawLine(
                        color = when (frameIntervalBucket(current, periodMs)) {
                            FrameIntervalBucket.ON_TIME -> Color(0xFF4CAF50)
                            FrameIntervalBucket.ONE_MISSED -> Color(0xFFFFC107)
                            FrameIntervalBucket.MULTIPLE_MISSED -> Color(0xFFF44336)
                        },
                        start = Offset((i - 1) * step, y1), end = Offset(i * step, y2), strokeWidth = 2f, cap = StrokeCap.Round,
                    )
                }
                drawLine(
                    Color.White.copy(alpha = 0.2f),
                    Offset(0f, size.height - periodMs * scale),
                    Offset(size.width, size.height - periodMs * scale),
                    0.5f,
                )
            }
        }

        // Row 4: 内存
        Row {
            Label("Java堆", Color.White.copy(alpha = 0.5f)); Spacer(Modifier.width(2.dp))
            Value("${memoryMetrics.javaMb.roundToInt()}M", Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.width(8.dp))
            Label("Native", Color.White.copy(alpha = 0.5f)); Spacer(Modifier.width(2.dp))
            Value("${memoryMetrics.nativeMb.roundToInt()}M", Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.width(8.dp))
            Label("PSS", Color.White.copy(alpha = 0.5f)); Spacer(Modifier.width(2.dp))
            Value("${memoryMetrics.pssMb.roundToInt()}M", Color.White.copy(alpha = 0.55f))
        }
    }
}

internal data class PerfFrameMetrics(
    val refreshRateHz: Float,
    val refreshPeriodMs: Float,
    val fpsNow: Float,
    val fps1s: Float,
    val fps5s: Float,
    val frameIntervalMs: Float,
    val onTimePercent: Int,
    val oneMissPercent: Int,
    val multiMissPercent: Int,
    val historyMs: FloatArray,
) {
    companion object {
        fun empty(refreshRateHz: Float): PerfFrameMetrics = PerfFrameMetrics(
            refreshRateHz = refreshRateHz,
            refreshPeriodMs = 1000f / refreshRateHz,
            fpsNow = 0f,
            fps1s = 0f,
            fps5s = 0f,
            frameIntervalMs = 0f,
            onTimePercent = 0,
            oneMissPercent = 0,
            multiMissPercent = 0,
            historyMs = FloatArray(0),
        )
    }
}

private data class PerfMemoryMetrics(
    val javaMb: Float = 0f,
    val nativeMb: Float = 0f,
    val pssMb: Float = 0f,
)

internal class PerfFrameAccumulator(
    private val refreshRateHz: Float,
    private val publishIntervalNanos: Long = FRAME_METRICS_PUBLISH_INTERVAL_NANOS,
    private val historySize: Int = FRAME_HISTORY_SIZE,
    private val distributionSize: Int = FRAME_DISTRIBUTION_SIZE,
) {
    private val timestamps = ArrayDeque<Long>()
    private val history = FloatArray(historySize)
    private val distribution = FloatArray(distributionSize)
    private var intervalCount = 0
    private var lastFrameNanos = 0L
    private var lastPublishNanos = 0L
    private var hasPublished = false
    private var latestIntervalMs = 0f

    init {
        require(refreshRateHz > 0f)
        require(publishIntervalNanos >= 0L)
        require(historySize > 0)
        require(distributionSize > 0)
    }

    fun record(frameTimeNanos: Long): PerfFrameMetrics? {
        if (lastFrameNanos > 0L && frameTimeNanos > lastFrameNanos) {
            latestIntervalMs = ((frameTimeNanos - lastFrameNanos) / 1_000_000f)
                .coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
            history[intervalCount % historySize] = latestIntervalMs
            distribution[intervalCount % distributionSize] = latestIntervalMs
            intervalCount++
        }
        lastFrameNanos = frameTimeNanos
        timestamps.addLast(frameTimeNanos)
        val oldestAllowed = frameTimeNanos - FIVE_SECONDS_NANOS
        while (timestamps.size > 1 && timestamps.first() < oldestAllowed) timestamps.removeFirst()

        if (hasPublished && frameTimeNanos - lastPublishNanos < publishIntervalNanos) return null
        lastPublishNanos = frameTimeNanos
        hasPublished = true
        return snapshot(frameTimeNanos)
    }

    private fun snapshot(nowNanos: Long): PerfFrameMetrics {
        val periodMs = 1000f / refreshRateHz
        var onTime = 0
        var oneMiss = 0
        var multipleMiss = 0
        val distributionCount = minOf(intervalCount, distributionSize)
        val distributionStart = intervalCount - distributionCount
        repeat(distributionCount) { offset ->
            when (frameIntervalBucket(distribution[(distributionStart + offset) % distributionSize], periodMs)) {
                FrameIntervalBucket.ON_TIME -> onTime++
                FrameIntervalBucket.ONE_MISSED -> oneMiss++
                FrameIntervalBucket.MULTIPLE_MISSED -> multipleMiss++
            }
        }
        val historyCount = minOf(intervalCount, historySize)
        val historyStart = intervalCount - historyCount
        val historySnapshot = FloatArray(historyCount) { offset ->
            history[(historyStart + offset) % historySize]
        }
        return PerfFrameMetrics(
            refreshRateHz = refreshRateHz,
            refreshPeriodMs = periodMs,
            fpsNow = fpsForLastFrames(8),
            fps1s = fpsForWindow(nowNanos - ONE_SECOND_NANOS),
            fps5s = fpsForWindow(nowNanos - FIVE_SECONDS_NANOS),
            frameIntervalMs = latestIntervalMs,
            onTimePercent = percent(onTime, distributionCount),
            oneMissPercent = percent(oneMiss, distributionCount),
            multiMissPercent = percent(multipleMiss, distributionCount),
            historyMs = historySnapshot,
        )
    }

    private fun fpsForLastFrames(maxFrames: Int): Float {
        val iterator = timestamps.descendingIterator()
        var count = 0
        var oldest = 0L
        var newest = 0L
        while (iterator.hasNext() && count < maxFrames) {
            val timestamp = iterator.next()
            if (count == 0) newest = timestamp
            oldest = timestamp
            count++
        }
        return normalizedFrameRate(count, newest - oldest)
    }

    private fun fpsForWindow(cutoffNanos: Long): Float {
        var count = 0
        var first = 0L
        var last = 0L
        for (timestamp in timestamps) {
            if (timestamp < cutoffNanos) continue
            if (count == 0) first = timestamp
            last = timestamp
            count++
        }
        return normalizedFrameRate(count, last - first)
    }
}

internal enum class FrameIntervalBucket { ON_TIME, ONE_MISSED, MULTIPLE_MISSED }

internal fun frameIntervalBucket(intervalMs: Float, refreshPeriodMs: Float): FrameIntervalBucket = when {
    intervalMs <= refreshPeriodMs * 1.5f -> FrameIntervalBucket.ON_TIME
    intervalMs <= refreshPeriodMs * 2.5f -> FrameIntervalBucket.ONE_MISSED
    else -> FrameIntervalBucket.MULTIPLE_MISSED
}

internal fun normalizedFrameRate(sampleCount: Int, elapsedNanos: Long): Float =
    if (sampleCount < 2 || elapsedNanos <= 0L) 0f else (sampleCount - 1) * 1_000_000_000f / elapsedNanos

internal fun processCpuPercent(cpuDeltaMillis: Long, wallDeltaNanos: Long): Int {
    if (cpuDeltaMillis <= 0L || wallDeltaNanos <= 0L) return 0
    return (cpuDeltaMillis * 100_000_000.0 / wallDeltaNanos).roundToInt().coerceAtLeast(0)
}

private fun percent(value: Int, total: Int): Int = if (total == 0) 0 else value * 100 / total

private const val DEFAULT_REFRESH_RATE_HZ = 60f
private const val MIN_REFRESH_RATE_HZ = 30f
private const val MAX_REFRESH_RATE_HZ = 240f
private const val MIN_GRAPH_MAX_MS = 40f
private const val MIN_FRAME_INTERVAL_MS = 0.001f
private const val MAX_FRAME_INTERVAL_MS = 2_000f
private const val FRAME_HISTORY_SIZE = 120
private const val FRAME_DISTRIBUTION_SIZE = 240
private const val FRAME_METRICS_PUBLISH_INTERVAL_NANOS = 250_000_000L
private const val CPU_SAMPLE_INTERVAL_NANOS = 1_000_000_000L
private const val MEMORY_SAMPLE_INTERVAL_MS = 5_000L
private const val ONE_SECOND_NANOS = 1_000_000_000L
private const val FIVE_SECONDS_NANOS = 5_000_000_000L
private const val BYTES_PER_MIB = 1024f * 1024f

// ── 小工具 ──

@Composable
private fun Label(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)
}

@Composable
private fun Value(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}
