package io.github.weiyongzenqi.unuplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepositoryProvider
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.local.AndroidLocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.ui.App
import io.github.weiyongzenqi.unuplayer.ui.AppDependencies
import io.github.weiyongzenqi.unuplayer.ui.theme.UnUTheme
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepositoryProvider
import io.github.weiyongzenqi.unuplayer.webdav.setSharedHttpClientTlsInsecure

/**
 * Android 壳入口。
 *
 * 构造平台依赖(Storage/Repository)注入 commonMain 的 App()。
 * 处理外部 Intent 拉起(P1-6, 见 DESIGN.md §11.6):
 * - ACTION_VIEW / ACTION_SEND + video 通配 或扩展名
 * - content:// 原样传给播放器, 由 Android 引擎在每次 load 时转 fdclose://
 * - file:// / http(s):// 直接用 uri
 */
class MainActivity : ComponentActivity() {

    private lateinit var appScope: CoroutineScope

    companion object {
        // 进程级扫描协调器: Activity 重建不丢进行中的扫描 job。coordinator 内部 scope 进程级,
        // 切 tab/进详情/Activity 不重建都不取消扫描。首次构造后复用同实例。
        @Volatile private var scanCoordinator: PosterWallScanCoordinator? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppNotif.init(applicationContext)

        val storage = AndroidStorage(applicationContext)
        appScope = MainScope()
        val appLogger = AndroidAppLogger.get(applicationContext)
        // 进程级单例(P1/B10 修复): 与 PlayerActivity 共用同一设置/WebDAV 仓库实例。
        // 设置仓库 update() 从本实例 state 变换后全量写穿存储, 多实例会互相还原更新;
        // WebDAV 仓库读路径含密文迁移回写, 多实例的实例锁挡不住并发丢更新。
        val settingsRepo = SettingsRepositoryProvider.get(applicationContext)
        val webDavRepo = WebDavConnectionRepositoryProvider.get(applicationContext)
        val scrapedRepo = io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepositoryImpl.get(applicationContext)
        val mediaSourceFactory = io.github.weiyongzenqi.unuplayer.library.AndroidMediaSourceFactory(applicationContext, webDavRepo)
        val deps = AppDependencies(
            webDavRepository = webDavRepo,
            settingsRepository = settingsRepo,
            localDirectoryRepository = AndroidLocalDirectoryRepository(storage, applicationContext),
            appLogger = appLogger,
            playbackRepository = PlaybackRecordRepositoryImpl.get(applicationContext),
            scrapedRepository = scrapedRepo,
            mediaSourceFactory = mediaSourceFactory,
            posterWallScanCoordinator = scanCoordinator
                ?: PosterWallScanCoordinator(scrapedRepo, mediaSourceFactory).also { scanCoordinator = it },
        )

        // 设置驱动日志目录: 开启且选了目录 → 写; 否则不写。随设置变化更新。
        appScope.launch {
            settingsRepo.state.collect { s ->
                appLogger.setDirectory(if (s.enableLogs) s.logDirUri else null)
                appLogger.setAppLogLevel(runCatching { LogLevel.valueOf(s.appLogLevel.uppercase()) }.getOrDefault(LogLevel.INFO))
                // B12: TLS 降级开关同步到进程级共享 HTTP 客户端(WebDAV 列目录/弹弹play 匹配/字幕下载)。
                setSharedHttpClientTlsInsecure(s.allowTlsInsecure)
            }
        }

        setContent {
            val settings by settingsRepo.state.collectAsState()
            UnUTheme(
                dynamicColor = settings.dynamicColor,
                darkTheme = settings.darkTheme,
            ) {
                App(
                    dependencies = deps,
                    onPlay = { playable ->
                        // 拉起独立 PlayerActivity：只传媒体定位信息；WebDAV 凭据由播放器按 mediaKey 重载。
                        // 标题用于本地弹幕文件名匹配; contentUri 用于本地 content:// 弹幕哈希匹配;
                        // mediaKey 用于播放记录(导航位置 key, source 层 fill)。
                        appLogger.appEvent("app", "应用内播放 ${playable.title}", LogLevel.INFO)
                        startActivity(PlayerActivity.newIntent(this, playable.url, playable.title, playable.contentUri, playable.mediaKey))
                    },
                    onExitApp = { finishAffinity() },
                )
            }
        }

        // 外部拉起(ACTION_VIEW/SEND): 直接开 PlayerActivity 播放, 不进首页导航。
        // 首页 MainActivity 始终竖屏, 不被播放器方向影响。
        val initialIntent = intent
        appScope.launch {
            val initialPlay = withContext(Dispatchers.IO) {
                initialIntent?.let { resolvePlayFromIntent(it) }
            }
            if (initialPlay != null) {
                appLogger.appEvent("app", "外部拉起 ${initialPlay.second}", LogLevel.INFO)
                startActivity(
                    PlayerActivity.newIntent(
                        this@MainActivity,
                        initialPlay.first,
                        initialPlay.second,
                        initialPlay.third,
                    ),
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消本 Activity 的协程(设置收集 job), 防泄漏。AppLogger 是进程单例, 不在此关闭。
        if (::appScope.isInitialized) appScope.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: 已有实例时收到新外部 Intent, 直接开 PlayerActivity 播放。
        val newIntent = intent
        appScope.launch {
            val resolved = withContext(Dispatchers.IO) { resolvePlayFromIntent(newIntent) }
            resolved?.let { (url, title, contentUri) ->
                startActivity(PlayerActivity.newIntent(this@MainActivity, url, title, contentUri))
            }
        }
    }

    /**
     * 从 Intent 解析播放 URL + 标题。
     *
     * - ACTION_VIEW: intent.data
     * - ACTION_SEND: EXTRA_STREAM
     * - content://: 原样传给播放器, 由 Android 引擎在每次 load 时转 fdclose://; 标题查 DISPLAY_NAME
     * - file:// / http(s)://: 直接用 uri 字符串; 标题取 uri 末段
     *
     * @return (url, title, contentUri?); title 为文件名; contentUri 为原始 content://(透传给播放器算弹幕哈希), 非 content 为 null
     */
    private fun resolvePlayFromIntent(intent: Intent): Triple<String, String, String?>? {
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data ?: return null
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
            }
            else -> return null
        }
        val target = resolveExternalPlaybackTarget(
            parts = ExternalPlaybackUriParts(
                scheme = uri.scheme,
                rawUri = uri.toString(),
                path = uri.path,
                lastPathSegment = uri.lastPathSegment,
                encodedUserInfo = uri.encodedUserInfo,
            ),
            displayName = resolveDisplayName(uri),
        ) ?: return null
        return Triple(target.url, target.title, target.contentUri)
    }

    /** 查 content URI 的显示名(文件名), 用于本地弹幕文件名匹配。仅允许从 IO 线程调用。 */
    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme?.lowercase() != "content") return ""
        return runCatching {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else "" } ?: ""
        }.getOrDefault("")
    }

}
