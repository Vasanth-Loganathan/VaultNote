package com.vasanth.vaultnote.util

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.preference.PreferenceManager

object ScreenGuard {
    fun set(window: Window, enabled: Boolean) {
        if (enabled) window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE
        ) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun apply(activity: Activity) {
        val on = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("secure_screen", true)
        set(activity.window, on)
    }
}