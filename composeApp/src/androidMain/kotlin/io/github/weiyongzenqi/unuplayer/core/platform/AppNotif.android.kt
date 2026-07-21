package io.github.weiyongzenqi.unuplayer.core.platform

import android.content.Context
import android.widget.Toast

/**
 * Android actual: Toast + SharedPreferences。
 *
 * 用进程级 Application context(避免持有 Activity 导致泄漏);
 * 由平台壳(MainActivity.onCreate)调 [init] 注入。
 */
actual object AppNotif {

    private const val PREFS_NAME = "unu_prefs"

    @Volatile
    private var appContext: Context? = null

    /** 注入 Application context。平台初始化时调(MainActivity.onCreate)。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual fun setLogger(logger: io.github.weiyongzenqi.unuplayer.platform.AppLogger?) {
        // Android Toast 是用户可见通知，不需要复制到应用日志；保留统一注入契约。
    }

    actual fun toast(message: String) {
        val ctx = appContext ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    actual fun isFlagSet(key: String): Boolean {
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, false)
    }

    actual fun setFlag(key: String) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, true).apply()
    }
}
