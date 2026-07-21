package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository

/**
 * 字幕字体设置 slot(平台实现)。
 *
 * 字体来源依赖平台 API(系统字体枚举 / SAF 文件导入), 放 commonMain 接口,
 * androidMain 实现。commonMain 的 SettingsScreen 只调用此 slot, 不直接碰字体 API。
 *
 * 职责:
 * - 列出系统字体名 + 已导入字体名, 供用户选 sub-font
 * - SAF 选 .ttf/.otf 导入到私有目录(更新 subtitleFontDir)
 * - 清除自定义字体
 */
@Composable
expect fun SubtitleFontsSlot(repository: SettingsRepository)
