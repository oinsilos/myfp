package com.fongmi.android.tv.ui.common

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局主题设置（三板块共用，与任一板块私有存储解耦）：
 * - dark 深色（默认）/ sepia 护眼（暖色，作用于小说正文阅读）/ night 夜间（更纯黑）
 * - 统一设置入口：底部「设置」tab、音乐/小说板块内弹窗均读写这里
 * - 产品决策：视频播放/音乐页面为影音场景恒定深色，护眼暖色只影响小说正文
 * - 变化即时广播：小说板块订阅 [addListener] 无需重启即换肤
 */
class ThemeStore private constructor() {

    companion object {
        private const val PREFS = "app_shared"
        private const val KEY_THEME = "theme"
        private const val DEFAULT_THEME = "dark"
        private val INSTANCE = ThemeStore()

        @JvmStatic
        fun get(): ThemeStore = INSTANCE
    }

    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 当前主题 key：dark / sepia / night，写时即持久化并广播。 */
    var theme: String = DEFAULT_THEME
        set(value) {
            if (value.isEmpty()) return
            field = value
            prefs?.edit()?.putString(KEY_THEME, value)?.apply()
            listeners.forEach { it(value) }
        }

    /** 订阅主题变化（回调在主线程的调用方线程；读者侧用于即时换肤）。 */
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** 幂等初始化（任一处板块入口先调用即可）。 */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = prefs?.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
    }
}