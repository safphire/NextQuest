// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The event log. Schema history lives in data/schemas (committed); every version bump
 * ships with a migration and a migration test. Destructive migration is never enabled.
 */
@Database(entities = [EventEntity::class], version = QuestDatabase.VERSION, exportSchema = true)
abstract class QuestDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        const val VERSION = 1
        const val NAME = "quest-events.db"

        /** Every migration, in order. Tests open every historical schema through these. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()

        fun open(context: Context): QuestDatabase =
            Room.databaseBuilder(context.applicationContext, QuestDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
