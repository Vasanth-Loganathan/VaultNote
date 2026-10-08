package com.vasanth.vaultnote.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: SyncPrefs
) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Debounced upload: every call restarts a 3 second timer. */
    fun schedulePush() {
        if (!prefs.enabled) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("vn_push", ExistingWorkPolicy.REPLACE, req)
    }

    fun startPeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("vn_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelAll() {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("vn_push")
        wm.cancelUniqueWork("vn_periodic")
    }
}