package io.github.weiyongzenqi.unuplayer.core.network

/**
 * 全应用唯一 User-Agent 版本值。
 *
 * 发版清单必含本常量: Android versionName/versionCode、桌面 packageVersion、
 * Inno AppVersion、msiPackageVersion 同步 +1 时, 本值一并更新
 * (此前 8 个文件各拷贝一份字面量, 版本漂移至 0.1/0.1.6/0.1.7 混杂)。
 *
 * 尾部项目主页不可省: Bangumi 官方要求 UA 带应用名+版本+开源项目主页,
 * 且网关侧按前缀 "UnU-Player/" 做客户端准入(见 UnU-Gateway AuthService)。
 */
const val APP_USER_AGENT = "UnU-Player/0.2.2 (github.com/weiyongzenqi/UnU-Player)"
