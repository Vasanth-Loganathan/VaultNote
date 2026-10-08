package com.vasanth.vaultnote.di

import android.content.Context
import androidx.room.Room
import com.vasanth.vaultnote.data.db.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): VaultDatabase =
        Room.databaseBuilder(ctx, VaultDatabase::class.java, "vaultnote.db").build()

    @Provides fun provideNoteDao(db: VaultDatabase): NoteDao = db.noteDao()
    @Provides fun provideTagDao(db: VaultDatabase): TagDao = db.tagDao()
    @Provides fun provideLinkDao(db: VaultDatabase): LinkDao = db.linkDao()
    @Provides fun provideSyncStateDao(db: VaultDatabase): SyncStateDao = db.syncStateDao()
}