package io.github.weiyongzenqi.unuplayer.domain

import kotlinx.coroutines.flow.StateFlow
import io.github.weiyongzenqi.unuplayer.core.player.HdrMode
import io.github.weiyongzenqi.unuplayer.library.PosterWallSort

/**
 * 设置状态。持久化到 Storage, UI 双向绑定。
 *
 * recognizeAnime(番剧识别开关, P1-7)是 UnU-Player 新增设计:
 * - 开: 视频加载后尝试匹配番剧(P2 接 Bangumi/弹弹play)
 * - 关: 纯播放器模式, 不发任何番剧相关网络请求
 * 是弹幕/评论/元数据模块的上游总闸(见 DESIGN.md §11.7)。
 */

/** 默认音轨/字幕匹配正则(自动选轨)。空 pattern = 不自动选; 匹配轨道的 title+lang。 */
internal const val DEFAULT_AUDIO_TRACK_PATTERN = "ja-Jpan-JP|Japanese|日本語|日语|日文|\\bja\\b|\\bjpn\\b"
internal const val DEFAULT_SUBTITLE_TRACK_PATTERN =
    "zh-Hans-cn|zh-Hans|Simplified Chinese|简体|简中|中文字幕|\\bchs\\b|\\bsc\\b|zh_CN|zho|chi"
const val DESKTOP_GPU_RENDERING_KEY = "desktopGpuRendering"

/** 桌面端主导航布局(仅桌面端生效; Android 固定底部导航)。 */
enum class DesktopLayout { SIDEBAR, TOP_TABS }

/** 应用完成启动后的默认内容首页。 */
enum class StartupHome { WEBDAV, ANIME, LOCAL }

data class SettingsState(
    // === 番剧识别 ===
    val recognizeAnime: Boolean = true,

    // === 播放 ===
    val hwdec: String = defaultHwdec(),
    val audioOutput: String = defaultAudioOutput(),
    val hdrMode: HdrMode = HdrMode.AUTO,
    val cacheSize: Int = 32,            // MiB(默认 32, 内存-only, 不写盘)
    val cacheSecs: Int = 20,

    // === 倍速 ===
    val speedPresets: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f),
    val longPressSpeed: Float = 2f,     // 长按临时倍速

    // === 字幕(默认值, 播放时也可临时调) ===
    val subtitleFont: String = "",              // sub-font, 空=mpv 默认字体
    val subtitleFontDir: String? = null,        // sub-fonts-dir(SAF 导入字体目录绝对路径); null=未导入
    val subtitleScale: Float = 1.0f,            // sub-scale, 字幕缩放
    val subtitleColor: String = "#FFFFFFFF",    // sub-color(ARGB hex)
    val subtitleBorderSize: Float = 2.0f,       // sub-border-size, 描边粗细
    val subtitleBold: Boolean = false,          // sub-bold
    val subtitleStyleOverride: String = "force",// sub-ass-override: no/yes/force/scale
    val autoLoadSiblingSubtitle: Boolean = true,    // 无内封字幕时自动加载同目录同名外挂字幕(.ass/.srt 等)
    val subtitleLanguagePreference: String = "sc",  // 同目录字幕语言偏好: sc=简中优先 / tc=繁中优先 / none=不限(严格同名)
    val defaultSubtitleTrackPattern: String = DEFAULT_SUBTITLE_TRACK_PATTERN,  // 默认字幕轨匹配正则, 自动选轨用; 空=不自动选
    val defaultAudioTrackPattern: String = DEFAULT_AUDIO_TRACK_PATTERN,        // 默认音轨匹配正则, 自动选轨用; 空=不自动选

    // === 界面 ===
    val predictiveBack: Boolean = true, // 播放器预测性返回跟手缩放动画(Android 14+)
    val dynamicColor: Boolean = true,   // Android 12+ 动态取色
    val darkTheme: Boolean = true,
    val desktopLayout: DesktopLayout = DesktopLayout.SIDEBAR,  // 桌面端导航布局(仅桌面生效): 侧边栏/顶部 tab
    val startupHome: StartupHome = StartupHome.WEBDAV,
    val desktopRunInBackground: Boolean = false, // Windows: 关闭主窗口时最小化，继续播放/扫描
    val desktopClosePrompt: Boolean = true,      // Windows: 关闭主窗口前询问，可在设置中重新开启
    val desktopGpuRendering: Boolean = false,    // 旧 WGL 开关兼容存储；生产路径不再消费

    // === 日志(默认关闭; 开启后输出到用户选定目录, 防默认开启产生大量日志文件) ===
    val enableLogs: Boolean = false,    // 总开关
    val logLevel: String = "info",      // mpv log-level: error/warn/info/v/debug/trace
    val appLogLevel: String = "info",   // 程序日志级别(控制 unu-app 文件过滤): error/warn/info/v/debug/trace
    val logDirUri: String? = null,      // SAF tree URI(用户选定输出目录); null=未选

    // === 安全 ===
    // 允许 TLS 降级(HTTPS 证书不验证)。默认关: 系统 CA 不可用时宁可播放失败也不偷偷不验证。
    // 仅当遇到自签证书/特殊 WebDAV 服务器无法正常播放时才打开, 后果是 HTTPS 连接不再验证服务端
    // 身份, 中间人攻击可窃听/篡改流量(账号密码、视频内容)。需重新进播放器生效。
    val allowTlsInsecure: Boolean = false,

    // === WebDAV 浏览 ===
    val webdavDefaultConnectionId: String? = null,   // 默认连接 id(空=不自动选)
    val webdavDefaultDirectory: String = "/",        // 默认目录
    val webdavSortPreset: WebDavSortPreset = WebDavSortPreset.DEFAULT,
    val webdavShowBreadcrumb: Boolean = true,
    val webdavAutoEnterSeasonFolder: Boolean = false,
    val webdavSeasonFolderPattern: String = "Season*",

    // === 弹幕(弹弹play) ===
    // 直连凭证(可选, 用户手动填写, 见 DESIGN.md §12.1.2): 关闭代理模式时走 appId/secret 直连弹弹。
    // 明文存 Storage(对齐 WebDAV 密码现状, Keystore 加密后续与 WebDAV 密码一起做)。空 = 未配置直连。
    val dandanplayAppId: String = "",
    val dandanplayAppSecret: String = "",
    // 代理缓存模式: 开启后弹幕请求走自建代理(端点 + API Key 内置于 DandanplayProxyConfig, 不暴露明文),
    // 弹弹签名下沉服务端。默认开(官方部署的公共代理); 关闭则用上面 appId/secret 直连。
    val dandanplayUseProxy: Boolean = true,
    val danmakuHashFallback: Boolean = true,  // 快速匹配(tmdbId)失败时回落哈希匹配(match 接口)
    val danmakuEnabled: Boolean = true,        // 弹幕渲染总开关
    val danmakuEngine: String = "ATLAS",      // 渲染内核: ATLAS(atlas 批渲染, 默认) / COMPOSE(Canvas drawText) / BITMAP(位图缓存)
    val danmakuShowMatchToast: Boolean = false,// 每次匹配到弹幕时弹 2s 气泡提示匹配方式(tmdb/哈希/文件名)
    val danmakuAutoManualMatch: Boolean = true,  // 基础匹配失败时自动拉起手动匹配弹窗; 关后需手动点按钮
    val danmakuOpacity: Float = 1.0f,          // 不透明度 0..1
    val danmakuFontSize: Float = 0f,           // 字号 px; 0=平台默认
    val danmakuDisplayArea: Float = 1.0f,      // 显示区域 0..1(屏幕高度利用率)
    val danmakuSpeedMultiplier: Float = 1.0f,  // 滚动速度倍率
    val danmakuMaxOnScreen: Int = 150,         // 同屏弹幕上限(0=自动使用 5000 条硬上限)

    // === 番剧识别(预留, 后端 P2 未实现; 仅保存设置不消费) ===
    val bgmIdQuickMatch: Boolean = false,
    val bgmIdMatchPattern: String = "bgm(id)?[=-](\\d+)",
    val tmdbIdQuickMatch: Boolean = false,
    val tmdbIdMatchPattern: String = "tmdb(id)?[=-](\\d+)",
    val episodeOffsetEnabled: Boolean = false,

    // === WebDAV 搜索 ===
    val webdavEnableSearch: Boolean = true,
    val webdavSearchScope: WebDavSearchScope = WebDavSearchScope.CURRENT_WITH_DEPTH,
    val webdavSearchDepthLimit: Int = 3,
    val webdavSearchTargets: Set<WebDavSearchTarget> = WebDavSearchTarget.DEFAULT,
    val webdavSearchTimeout: WebDavSearchTimeout = WebDavSearchTimeout.SECONDS_30,
    val webdavSearchRequestInterval: Int = 100,
    val webdavSearchMaxResults: Int = 500,

    // === 海报墙 ===
    val posterWallEnabled: Boolean = true,
    val posterWallDefaultLibraryId: Long? = null,        // 默认刮削库 id(进 tab 直达); null=不自动选
    val posterWallScanRequestIntervalMs: Int = 100,      // 扫描请求间隔(ms), 限流; 0=不限
    val posterWallScanConcurrency: Int = 2,              // 扫描并发线程数(1-8)
    val posterWallScanDepth: Int = 6,                    // 最大递归深度
    val posterWallScanTimeoutSeconds: Int = 600,
    val posterWallPosterColumnsPortrait: Int = 3,        // 竖屏海报列数
    val posterWallPosterColumnsLandscape: Int = 5,
    val posterWallGroupByQuarter: Boolean = true,
    val posterWallSortBy: PosterWallSort = PosterWallSort.QUARTER,
    val posterWallShowEpisodeThumb: Boolean = true,
    val posterWallDetailUseSeasonPoster: Boolean = false,   // 详情页头部海报改用当前季 seasonXX-poster.jpg
    val posterWallBadgeShowSeason1: Boolean = true,    // 季徽章是否显示第1季(false=仅第2季起显示, 减少干扰)
    val posterWallImageCacheSizeMb: Int = 200,
    val posterWallWalAutoCheckpoint: Boolean = true,

    // === 首次启动免责声明 ===
    // 首次启动强制阅读 3 秒并同意后置 true, 之后不再弹出。默认 false(首次启动必弹)。
    // 闸门: App() 仅在 SettingsLoadState.Loaded 后据此判断, 避免回访用户看到声明一闪。
    val disclaimerAccepted: Boolean = false,
)

/** 设置首次读取及显式重试的状态。 */
sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState
    data object Loaded : SettingsLoadState
    data class Failed(val message: String) : SettingsLoadState
}

/** 设置仓库抽象, 持久化 + 响应式。实现在 platformMain。 */
interface SettingsRepository {
    val state: StateFlow<SettingsState>
    val loadState: StateFlow<SettingsLoadState>
    suspend fun update(transform: (SettingsState) -> SettingsState)
    suspend fun retryLoad()
    suspend fun useDefaultsAfterLoadFailure()

    /**
     * 等待首次从 Storage 的读取尝试结束。
     *
     * 首次尝试无论成功或普通读取失败都会结束。调用方等待后仍必须检查 [loadState]：
     * [SettingsLoadState.Failed] 时 [state] 只是用于错误页的默认值，不能直接用它执行
     * init-only 决策（如 PlayerEngine 的 hdrMode），否则会按默认 AUTO 初始化且无法撤回。
     */
    suspend fun awaitLoaded()
}
