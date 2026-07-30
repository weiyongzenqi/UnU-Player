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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ┌─────────────────────────────────────────┐
 * │ FPS  60  (1s均:58  5s均:59)           │
 * │ 帧时间  <16ms:95%  16-32:4%  >32:1%    │
 * │ ▂▃▂▃▄▃▂▂▃▄▅▃▂▂▃▄▅▄▃▂▃▄▅▄▃▂▂▃▄▅ (波形) │
 * │ Java堆: 45M   Native: 82M   PSS: 210M   │
 * │ CPU: 12%   ⏱帧: 2.1ms                  │
 * └─────────────────────────────────────────┘
 */
@Composable
fun PerfMonitorOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }

    // ── 数据缓冲区 ──
    val histSize = 120
    val frameMsBuf = remember { FloatArray(histSize) }
    var bufIdx by remember { mutableStateOf(0) }

    var fpsNow by remember { mutableStateOf(0f) }
    var fps1s by remember { mutableStateOf(0f) }
    var fps5s by remember { mutableStateOf(0f) }
    var lt16 by remember { mutableStateOf(0) }
    var lt32 by remember { mutableStateOf(0) }
    var gt32 by remember { mutableStateOf(0) }
    var javaMb by remember { mutableStateOf(0f) }
    var nativeMb by remember { mutableStateOf(0f) }
    var pssMb by remember { mutableStateOf(0f) }
    var cpuPct by remember { mutableStateOf(0) }
    var frameUs by remember { mutableStateOf(0L) }

    // ── CPU 基准 ──
    var prevCpuMs by remember { mutableStateOf(Process.getElapsedCpuTime()) }
    var prevCpuWall by remember { mutableStateOf(System.nanoTime()) }

    LaunchedEffect(Unit) {

        val last8 = LongArray(8); var l8i = 0
        var t1s = 0L; var n1s = 0; var t5s = 0L; var n5s = 0
        val dist = IntArray(240); var di = 0
        var memCtr = 0

        while (true) {
            val t0Ns = withFrameNanos { it }

            // 帧时间
            val dt = if (l8i > 0) {
                ((t0Ns - last8[(l8i - 1) % 8]) / 1000f).coerceIn(1f, 2000000f) // us
            } else 16670f // 默认 60fps

            last8[l8i % 8] = t0Ns; l8i++
            val dtMs = dt / 1000f
            frameMsBuf[bufIdx % histSize] = dtMs; bufIdx++
            frameUs = dt.roundToLong()

            // 瞬时 FPS
            if (l8i >= 8) {
                val e = t0Ns - last8[(l8i - 8) % 8]
                if (e > 0) fpsNow = 7e9f / e
            }

            // 1s / 5s 平均
            n1s++; n5s++
            if (t1s == 0L) t1s = t0Ns
            if (t0Ns - t1s >= 1_000_000_000L) { fps1s = n1s.toFloat(); n1s = 0; t1s = t0Ns }
            if (t5s == 0L) t5s = t0Ns
            if (t0Ns - t5s >= 5_000_000_000L) { fps5s = n5s / 5f; n5s = 0; t5s = t0Ns }

            // 分布（每 15 帧刷新）
            dist[di % 240] = dtMs.roundToInt(); di++
            if (bufIdx % 15 == 0) {
                var a = 0; var b = 0; var c = 0; val n = minOf(di, 240)
                for (i in 0 until n) {
                    when { dist[i] <= 16 -> a++; dist[i] <= 32 -> b++; else -> c++ }
                }
                if (n > 0) { lt16 = a * 100 / n; lt32 = b * 100 / n; gt32 = c * 100 / n }
            }

            // 内存（每 60 帧）
            if (++memCtr % 60 == 0) {
                val rt = Runtime.getRuntime()
                javaMb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f)
                nativeMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)
                try { val mi = am.getProcessMemoryInfo(intArrayOf(Process.myPid())); if (mi.isNotEmpty()) pssMb = mi[0].totalPss / 1024f } catch (_: Exception) {}
            }

            // CPU（每 30 帧：Process.getElapsedCpuTime 增量 / 墙钟增量）
            if (bufIdx % 30 == 0) {
                val nowCpu = Process.getElapsedCpuTime()
                val nowWall = System.nanoTime()
                val dCpu = nowCpu - prevCpuMs
                val dWall = (nowWall - prevCpuWall) / 1_000_000L // ms
                if (dWall > 0) cpuPct = (dCpu * 100 / dWall).toInt().coerceIn(0, 100)
                prevCpuMs = nowCpu; prevCpuWall = nowWall
            }
        }
    }

    // ── 渲染 ──
    // 直接读 frameMsBuf(主线程 LaunchedEffect 写、主线程 Composable 读, 无并发)。
    // 原先每帧 copyOf() 分配 120-float 数组, 高频重组 GC 压力大, 改为直接读稳定引用。
    val gd = frameMsBuf
    val gn = minOf(bufIdx, histSize)
    val s0 = maxOf(0, bufIdx - histSize)

    val fpsColor = when { fpsNow >= 55f -> Color(0xFF4CAF50); fpsNow >= 30f -> Color(0xFFFFC107); else -> Color(0xFFF44336) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xDD1A1A2E))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Row 1: FPS
        Row {
            Label("FPS", Color.White.copy(alpha = 0.6f)); Spacer(Modifier.width(4.dp))
            Value("${fpsNow.roundToInt()}", fpsColor)
            Spacer(Modifier.width(10.dp))
            Label("1s均", Color.White.copy(alpha = 0.4f)); Spacer(Modifier.width(2.dp))
            Value("${fps1s.roundToInt()}", Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(8.dp))
            Label("5s均", Color.White.copy(alpha = 0.4f)); Spacer(Modifier.width(2.dp))
            Value("${fps5s.roundToInt()}", Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(12.dp))
            Label("帧耗时", Color.White.copy(alpha = 0.55f)); Spacer(Modifier.width(2.dp))
            Value("${(frameUs / 1000f).roundToInt()}ms", if (frameUs <= 16670) Color(0xFF4CAF50) else Color(0xFFFFC107))
        }

        // Row 2: 帧分布
        Row {
            Label("<16ms", Color(0xFF4CAF50)); Spacer(Modifier.width(2.dp))
            Value("${lt16}%", Color(0xFF4CAF50))
            Spacer(Modifier.width(8.dp))
            Label("16-32", Color(0xFFFFC107)); Spacer(Modifier.width(2.dp))
            Value("${lt32}%", Color(0xFFFFC107))
            Spacer(Modifier.width(8.dp))
            Label(">32ms", Color(0xFFF44336)); Spacer(Modifier.width(2.dp))
            Value("${gt32}%", Color(0xFFF44336))
            Spacer(Modifier.width(12.dp))
            Label("CPU", Color.White.copy(alpha = 0.55f)); Spacer(Modifier.width(2.dp))
            Value("${cpuPct}%", Color.White.copy(alpha = 0.6f))
        }

        // Row 3: 波形图
        if (gn > 1) {
            Spacer(Modifier.height(2.dp))
            Canvas(modifier = Modifier.size(width = 160.dp, height = 28.dp)) {
                val step = size.width / (histSize - 1); val scale = size.height / 70f
                for (i in 1 until gn) {
                    val a = (s0 + i - 1) % histSize; val b = (s0 + i) % histSize
                    val y1 = (size.height - gd[a].coerceAtMost(70f) * scale).coerceAtLeast(0f)
                    val y2 = (size.height - gd[b].coerceAtMost(70f) * scale).coerceAtLeast(0f)
                    drawLine(
                        color = when { gd[b] <= 16f -> Color(0xFF4CAF50); gd[b] <= 32f -> Color(0xFFFFC107); else -> Color(0xFFF44336) },
                        start = Offset((i - 1) * step, y1), end = Offset(i * step, y2), strokeWidth = 2f, cap = StrokeCap.Round,
                    )
                }
                drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, size.height - 16f * scale), Offset(size.width, size.height - 16f * scale), 0.5f)
            }
        }

        // Row 4: 内存
        Row {
            Label("Java堆", Color.White.copy(alpha = 0.5f)); Spacer(Modifier.width(2.dp))
            Value("${javaMb.roundToInt()}M", Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.width(8.dp))
            Label("Native", Color.White.copy(alpha = 0.5f)); Spacer(Modifier.width(2.dp))
            Value("${nativeMb.roundToInt()}M", Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.width(8.dp))
            Label("PSS", Color.White.copy(alpha = 0.5f)); Spacer(Modifier.width(2.dp))
            Value("${pssMb.roundToInt()}M", Color.White.copy(alpha = 0.55f))
        }
    }
}

// ── 小工具 ──

@Composable
private fun Label(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)
}

@Composable
private fun Value(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}

