package com.vasanth.vaultnote.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPrefs @Inject constructor(@ApplicationContext ctx: Context) {
    private val p = ctx.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) { p.edit().putBoolean("enabled", v).apply() }

    var email: String?
        get() = p.getString("email", null)
        set(v) { p.edit().putString("email", v).apply() }

    var lastSyncAt: Long
        get() = p.getLong("last_sync", 0L)
        set(v) { p.edit().putLong("last_sync", v).apply() }

    var driveFull: Boolean
        get() = p.getBoolean("drive_full", false)
        set(v) { p.edit().putBoolean("drive_full", v).apply() }
}