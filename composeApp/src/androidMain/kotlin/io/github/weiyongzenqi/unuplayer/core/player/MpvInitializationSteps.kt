package io.github.weiyongzenqi.unuplayer.core.player

/** Android mpv 初始化事务的固定顺序；任一步抛错都会阻止后续 Surface/READY 发布。 */
internal fun runMpvInitializationSteps(
    applyOptions: () -> Unit,
    initializeNative: () -> Unit,
    registerObservers: () -> Unit,
    applyInitialSubtitleStyle: () -> Unit,
    attachSurfaceAndPublish: () -> Unit,
    checkActive: () -> Unit = {},
) {
    checkActive()
    applyOptions()
    checkActive()
    initializeNative()
    checkActive()
    registerObservers()
    checkActive()
    applyInitialSubtitleStyle()
    checkActive()
    attachSurfaceAndPublish()
}
