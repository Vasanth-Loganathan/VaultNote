package com.vasanth.vaultnote

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.vasanth.vaultnote.reminder.Notifier
import com.vasanth.vaultnote.security.AppLockManager
import com.vasanth.vaultnote.util.ThemeHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VaultNoteApp : Application() {

    @Inject lateinit var appLock: AppLockManager

    override fun onCreate() {
        super.onCreate()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ThemeHelper.apply(prefs.getString("theme", "system"))
        if (prefs.getBoolean("dynamic_color", true)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        Notifier.createChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLock)
    }
}