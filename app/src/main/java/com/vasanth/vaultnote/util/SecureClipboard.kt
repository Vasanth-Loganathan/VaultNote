package com.vasanth.vaultnote.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

object SecureClipboard {
    private const val LABEL = "vaultnote"
    private val handler = Handler(Looper.getMainLooper())
    private var clearTask: Runnable? = null

    fun copy(ctx: Context, text: String, autoClear: Boolean) {
        val cm = ctx.applicationContext.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(LABEL, text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)

        clearTask?.let { handler.removeCallbacks(it) }
        if (autoClear) {
            val task = Runnable { clearIfOurs(cm) }
            clearTask = task
            handler.postDelayed(task, 30_000)
        }
    }

    private fun clearIfOurs(cm: ClipboardManager) {
        try {
            if (cm.primaryClipDescription?.label == LABEL) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) { }
    }
}