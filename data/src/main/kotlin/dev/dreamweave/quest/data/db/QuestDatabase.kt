// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2: the `pushed` flag for sync. Existing events start unpushed, so a first pairing uploads them. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN pushed INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * The event log. Schema history lives in data/schemas (committed); every version bump
 * ships with a migration and a migration test. Destructive migration is never enabled.
 */
@Database(entities = [EventEntity::class], version = QuestDatabase.VERSION, exportSchema = true)
abstract class QuestDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        const val VERSION = 2
        const val NAME = "quest-events.db"

        /** Every migration, in order. Tests open every historical schema through these. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun open(context: Context): QuestDatabase =
            Room.databaseBuilder(context.applicationContext, QuestDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
