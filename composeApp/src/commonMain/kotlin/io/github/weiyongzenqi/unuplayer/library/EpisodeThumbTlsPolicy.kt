package io.github.weiyongzenqi.unuplayer.library

/** 集照生成使用的 TLS 策略；默认验证失败时绝不静默降级。 */
internal sealed interface EpisodeThumbTlsPolicy {
    data object NotHttps : EpisodeThumbTlsPolicy
    data class Verify(val caFile: String?) : EpisodeThumbTlsPolicy
    data object Insecure : EpisodeThumbTlsPolicy
    data object Reject : EpisodeThumbTlsPolicy
}

/**
 * 将用户授权、平台 CA 能力和 URL 协议收敛为唯一决策。
 *
 * Android libmpv 需要显式 CA bundle；生成失败且用户未授权降级时直接拒绝。桌面 libmpv
 * 可使用系统 CA，因此 [requiresExplicitCaFile] 为 false 时不要求 [caFile]。
 */
internal fun resolveEpisodeThumbTlsPolicy(
    isHttps: Boolean,
    allowTlsInsecure: Boolean,
    requiresExplicitCaFile: Boolean,
    caFile: String?,
): EpisodeThumbTlsPolicy = when {
    !isHttps -> EpisodeThumbTlsPolicy.NotHttps
    allowTlsInsecure -> EpisodeThumbTlsPolicy.Insecure
    requiresExplicitCaFile && caFile == null -> EpisodeThumbTlsPolicy.Reject
    else -> EpisodeThumbTlsPolicy.Verify(caFile)
}
