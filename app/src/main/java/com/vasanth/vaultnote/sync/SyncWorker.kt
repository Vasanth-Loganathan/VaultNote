package com.vasanth.vaultnote.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun syncManager(): SyncManager
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val manager = EntryPointAccessors
            .fromApplication(applicationContext, SyncEntryPoint::class.java).syncManager()
        // If the app is locked (no key in memory) syncNow() skips, and the next unlock syncs.
        return if (manager.syncNow() == SyncOutcome.RETRY) Result.retry() else Result.success()
    }
}