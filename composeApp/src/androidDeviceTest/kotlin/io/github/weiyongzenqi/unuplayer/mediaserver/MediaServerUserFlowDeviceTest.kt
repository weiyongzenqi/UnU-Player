package io.github.weiyongzenqi.unuplayer.mediaserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 真机黑盒流程驱动(UiAutomation): 正式包上完成媒体服务器 添加连接 → 浏览 → 播放 →
 * 暂停/恢复/seek → 退出 → 历史续播 的用户路径。
 *
 * 设计约束:
 * - MIUI 禁止 `adb shell input` 向其他应用注入事件, 真机 UI 验收只能经 instrumentation 的
 *   UiAutomation 通道; 本测试不 import 任何生产类, 纯黑盒, 不受 release R8 混淆影响。
 * - 服务器地址/用户名/密码一律经 `am instrument -e` 参数注入, 禁止写进源码/日志/断言消息。
 * - 每个 @Test 是流程中的一小步, 由宿主按顺序逐个调用, 步与步之间宿主核对服务端会话状态;
 *   方法自身只对"当前屏幕应出现什么"做断言。
 */
@RunWith(AndroidJUnit4::class)
class MediaServerUserFlowDeviceTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun arg(name: String): String =
        InstrumentationRegistry.getArguments().getString(name)
            ?: fail("缺少 instrumentation 参数 $name")

    /** 中文等非 ASCII 参数经 adb shell 传递可能被改写，统一 base64(UTF-8)注入。 */
    private fun argBase64(name: String): String =
        String(android.util.Base64.decode(arg(name), android.util.Base64.DEFAULT), Charsets.UTF_8)

    private fun launchApp() {
        device.executeShellCommand("am start -n $APP_PACKAGE/.app.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), LAUNCH_TIMEOUT_MS) == true, "应用未进入前台")
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    /**
     * 步骤方法必须幂等: 上一步失败可能把应用留在任意中间页面(SaveableStateHolder 还会记住
     * tab 内导航栈), 以"源列表首页"为前提的步骤先冷启动归位(不清数据, 只重置导航)。
     */
    private fun relaunchAppFresh() {
        device.executeShellCommand("am force-stop $APP_PACKAGE")
        Thread.sleep(1_000)
        launchApp()
    }

    private fun waitObject(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long, what: String): UiObject2 {
        val found = device.wait(Until.findObject(selector), timeoutMs)
        return assertNotNull(found, "未找到: $what; 当前可见: ${visibleTexts()}")
    }

    /** 断言失败时附带现场, 避免每次失败都要另行取证。 */
    private fun visibleTexts(): String = buildString {
        device.findObjects(By.textContains("")).mapNotNull { it.text }.filter { it.isNotBlank() }
            .take(20).forEach { append('[').append(it).append(']') }
        device.findObjects(By.descContains("")).mapNotNull { it.contentDescription }.filter { it.isNotBlank() }
            .take(10).forEach { append("{").append(it).append('}') }
    }

    /** 步骤 1: 影视源页添加 Jellyfin 连接(名称/地址/用户名/密码经参数注入)。 */
    @Test
    fun addConnection() {
        val name = arg("msName")
        relaunchAppFresh()
        // 空态中央按钮(text)或右上角图标(content-desc)均叫"添加源"; 点击后须弹出"添加影视源"。
        // AlertDialog 弹出有动画, 单击后立即找子项可能超时, 故重试点击直到标题出现。
        openAddSourceDialog()
        waitObject(By.text("Jellyfin"), FIND_TIMEOUT_MS, "Jellyfin 入口").click()
        waitObject(By.text("连接"), FIND_TIMEOUT_MS, "添加对话框")

        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), FIND_TIMEOUT_MS)
        assertTrue(fields != null && fields.size >= 4, "添加对话框输入框不足 4 个")
        // 字段顺序与 AddMediaServerConnectionDialog 一致: 名称/服务器地址/用户名/密码。
        fields[0].text = name
        fields[1].text = arg("msUrl")
        fields[2].text = arg("msUser")
        fields[3].text = arg("msPass")
        device.waitForIdle(IDLE_TIMEOUT_MS)

        waitObject(By.text("连接"), FIND_TIMEOUT_MS, "连接按钮").click()
        // 认证 + 落库成功后对话框关闭, 源列表出现该连接行。
        waitObject(By.text(name), CONNECT_TIMEOUT_MS, "新连接行 $name")
    }

    /** 步骤 2: 点连接 → 媒体库 → 系列 → 集条目, 直到播放器出现并处于播放中。 */
    @Test
    fun openEpisode() {
        val name = arg("msName")
        val library = argBase64("msLibraryB64")
        val seriesKeyword = arg("msSeriesKeyword")
        val episodeKeyword = arg("msEpisodeKeyword")
        relaunchAppFresh()
        waitObject(By.text(name), FIND_TIMEOUT_MS, "连接行 $name").click()
        waitObject(By.text(library), CONNECT_TIMEOUT_MS, "媒体库 $library").click()
        waitObject(By.textContains(seriesKeyword), CONNECT_TIMEOUT_MS, "系列 $seriesKeyword").click()
        waitObject(By.textContains(episodeKeyword), CONNECT_TIMEOUT_MS, "集条目 $episodeKeyword").click()
        // 播放器 Activity 全屏; 等控制层可召出即认为进入播放器。
        device.waitForIdle(IDLE_TIMEOUT_MS)
        assertTrue(
            device.wait(Until.hasObject(By.desc("播放/暂停")), PLAYER_TIMEOUT_MS) == true ||
                revealControls() != null,
            "未进入播放器(找不到播放控制层)",
        )
    }

    /** 步骤 3: 召出控制层点击 播放/暂停。恢复播放亦复用本方法。 */
    @Test
    fun togglePlayPause() {
        val control = revealControls() ?: fail("无法召出播放控制层")
        control.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    /** 步骤 4: 在进度条上向右滑动完成一次 seek(触发 onSeekFinished 上报)。 */
    @Test
    fun seekForward() {
        revealControls() ?: fail("无法召出播放控制层")
        val slider = device.wait(Until.findObject(By.clazz("android.widget.SeekBar")), FIND_TIMEOUT_MS)
            ?: fail("找不到进度条")
        val bounds = slider.visibleBounds
        val y = bounds.centerY()
        val fromX = bounds.left + (bounds.width() * 0.15).toInt()
        val toX = bounds.left + (bounds.width() * 0.7).toInt()
        device.swipe(fromX, y, toX, y, 20)
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    /** 步骤 5: 返回退出播放器(触发本地最终写 + 远端 Stopped)。 */
    @Test
    fun exitPlayer() {
        device.pressBack()
        // 播放器退出后回到应用内浏览/首页; 控制层消失。
        assertTrue(
            device.wait(Until.gone(By.desc("播放/暂停")), FIND_TIMEOUT_MS) == true,
            "返回后播放控制层仍在(播放器未退出)",
        )
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    /** 步骤 6: 设置 → 播放记录 → 点击最新记录, 验证媒体服务器历史可重播。 */
    @Test
    fun replayFromHistory() {
        val episodeKeyword = arg("msEpisodeKeyword")
        relaunchAppFresh()
        waitObject(By.text("设置"), FIND_TIMEOUT_MS, "设置 tab").click()
        waitObject(By.text("播放记录"), FIND_TIMEOUT_MS, "播放记录分区").click()
        waitObject(By.textContains(episodeKeyword), CONNECT_TIMEOUT_MS, "历史记录 $episodeKeyword").click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        assertTrue(
            device.wait(Until.hasObject(By.desc("播放/暂停")), PLAYER_TIMEOUT_MS) == true ||
                revealControls() != null,
            "历史重播未进入播放器",
        )
    }

    /**
     * 点"添加源"入口, 重试直到"添加影视源"对话框出现。
     * Compose 语义树里可点节点可能是文本的父容器, 直接 click 文本节点未必命中,
     * 故取节点可见边界中心用坐标点击; 空态大按钮与右上角图标任一可用。
     */
    private fun openAddSourceDialog() {
        repeat(5) {
            val entry = device.findObject(By.text("添加源")) ?: device.findObject(By.desc("添加源"))
            if (entry != null) {
                val b = entry.visibleBounds
                device.click(b.centerX(), b.centerY())
            }
            if (device.wait(Until.hasObject(By.text("添加影视源")), 3_000) == true) return
        }
        fail("点击添加源后未弹出对话框")
    }

    /** 点屏幕中心召出控制层并返回 播放/暂停 按钮; 已显示则直接返回。 */
    private fun revealControls(): UiObject2? {
        repeat(3) {
            device.findObject(By.desc("播放/暂停"))?.let { return it }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            device.wait(Until.findObject(By.desc("播放/暂停")), 2_000)?.let { return it }
        }
        return device.findObject(By.desc("播放/暂停"))
    }

    private companion object {
        const val APP_PACKAGE = "io.github.weiyongzenqi.unuplayer"
        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val IDLE_TIMEOUT_MS = 3_000L
        const val FIND_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val PLAYER_TIMEOUT_MS = 25_000L
    }
}
