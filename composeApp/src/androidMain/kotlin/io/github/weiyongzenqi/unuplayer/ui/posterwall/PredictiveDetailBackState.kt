package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 详情覆盖层的预测性返回绘制状态。
 *
 * 提交手势后必须把进度保持在终点，直到覆盖层被移除；若立即归零，
 * `AnimatedVisibility` 仍可能保留一帧内容并把它画回原位，形成返回后的闪烁。
 */
internal class PredictiveDetailBackState {
    var progress by mutableFloatStateOf(0f)
        private set

    var skipAnimatedExit by mutableStateOf(false)
        private set

    fun prepareForOpen() {
        progress = 0f
        skipAnimatedExit = false
    }

    fun update(value: Float) {
        progress = value.coerceIn(0f, 1f)
    }

    fun cancel() {
        progress = 0f
    }

    fun commit() {
        progress = 1f
        skipAnimatedExit = true
    }

    fun prepareForAnimatedDismiss() {
        progress = 0f
        skipAnimatedExit = false
    }
}
