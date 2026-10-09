package com.vasanth.vaultnote.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NoteEntity::class, TagEntity::class, NoteTagCrossRef::class,
        NoteFts::class, LinkEntity::class, SyncStateEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun linkDao(): LinkDao
    abstract fun syncStateDao(): SyncStateDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN boardId TEXT")
        db.execSQL("ALTER TABLE notes ADD COLUMN columnId TEXT")
        db.execSQL("ALTER TABLE notes ADD COLUMN boardPos INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_boardId ON notes(boardId)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
    }
}