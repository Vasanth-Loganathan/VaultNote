package com.vasanth.vaultnote.di

import android.content.Context
import androidx.room.Room
import com.vasanth.vaultnote.crypto.DbKeyProvider
import com.vasanth.vaultnote.data.db.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(
        @ApplicationContext ctx: Context,
        keys: DbKeyProvider
    ): VaultDatabase {
        System.loadLibrary("sqlcipher")
        ctx.deleteDatabase("vaultnote.db")   // old unencrypted DB from Steps 1-3
        val factory = SupportOpenHelperFactory(keys.getOrCreateDbKey())
        return Room.databaseBuilder(ctx, VaultDatabase::class.java, DbKeyProvider.DB_NAME)
            .openHelperFactory(factory)
            .build()
    }

    @Provides fun provideNoteDao(db: VaultDatabase): NoteDao = db.noteDao()
    @Provides fun provideTagDao(db: VaultDatabase): TagDao = db.tagDao()
    @Provides fun provideLinkDao(db: VaultDatabase): LinkDao = db.linkDao()
    @Provides fun provideSyncStateDao(db: VaultDatabase): SyncStateDao = db.syncStateDao()
}