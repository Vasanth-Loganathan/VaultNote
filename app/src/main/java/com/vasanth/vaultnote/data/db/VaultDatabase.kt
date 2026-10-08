package com.vasanth.vaultnote.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        NoteEntity::class, TagEntity::class, NoteTagCrossRef::class,
        NoteFts::class, LinkEntity::class, SyncStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun linkDao(): LinkDao
    abstract fun syncStateDao(): SyncStateDao
}