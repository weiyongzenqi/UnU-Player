package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 首次启动免责声明(跨平台)。
 *
 * 强制阅读: "同意"按钮带 3 秒倒计时, 倒计时期间禁用, 到 0 才可点。
 * 同意后由调用方持久化 disclaimerAccepted=true, 之后不再弹出。
 * 不同意则由 [onDisagree] 退出应用(平台侧注入: Android finishAffinity / 桌面 exitApplication)。
 *
 * 全屏渲染(App() 在显示本页时不渲染主页), 无外部dismiss途径--只能同意或退出。
 */
@Composable
fun DisclaimerScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    // 倒计时秒数。3 -> 2 -> 1 -> 0(可同意)
    var remaining by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000L)
            remaining--
        }
    }
    val canAgree = remaining == 0

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题区
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("免责声明", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "首次使用前请务必阅读以下条款（3 秒后方可同意）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 正文(可滚动)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                DisclaimerBody()
                Spacer(Modifier.height(16.dp))
            }

            // 底部按钮: 不同意(退出) + 同意(倒计时门控)
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDisagree) { Text("不同意") }
                Button(
                    onClick = onAgree,
                    enabled = canAgree,
                    modifier = Modifier.width(200.dp),
                ) {
                    Text(if (canAgree) "我已阅读并同意" else "请阅读 ${remaining}s")
                }
            }
        }
    }
}

@Composable
private fun DisclaimerBody() {
    val body = MaterialTheme.typography.bodyMedium
    Text("UnU-Player 是一项个人学习与技术研究项目，本质上是一个本地视频播放器。", style = body)
    Spacer(Modifier.height(12.dp))

    DisclaimerPoint("本应用本身不提供、不内置、不聚合任何视频、音频或其他媒体资源，所有播放内容均来自用户自行配置的存储（如个人 WebDAV 服务器、本地文件等）。")
    DisclaimerPoint("本应用不针对任何内容来源的安全性、合法性、可用性或稳定性作任何明示或暗示的保证。")
    DisclaimerPoint("本项目仅供学习与交流使用，作者不承诺对项目进行持续的后续维护与更新。")
    DisclaimerPoint("本项目为基于 GNU General Public License v3（GPLv3）许可证的开源项目，源代码公开（见「设置 → 关于」页面），按「现状」提供，不含任何明示或暗示的担保。")
    DisclaimerPoint("启用「识别视频为番剧」后，弹幕匹配在请求后台服务时会记录您的 IP 地址，仅用于接口限流与防滥用，不作他用；若您不同意，可在设置中关闭「识别视频为番剧」，关闭后不进行弹幕匹配，亦不收集该信息。")
    DisclaimerPoint("使用本应用所产生的一切后果（包括但不限于数据丢失、隐私泄露、账号风险及法律纠纷等）均由使用者自行承担。")
    DisclaimerPoint("请确保您在使用本应用时遵守所在地区的法律法规，不得用于任何违法用途。")

    Spacer(Modifier.height(12.dp))
    Text(
        "点击「我已阅读并同意」即表示您已阅读、理解并接受上述全部条款。",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 单条免责要点: "•" + 正文。 */
@Composable
private fun DisclaimerPoint(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
