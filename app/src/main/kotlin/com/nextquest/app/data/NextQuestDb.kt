package com.nextquest.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QuestEntity::class, EventLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NextQuestDb : RoomDatabase() {
    abstract fun questDao(): QuestDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        const val NAME = "nextquest.db"

        // No fallbackToDestructiveMigration, ever — it silently wipes user data
        // (CLAUDE.md). Future schema changes ship real migrations + tests.
        fun build(context: Context): NextQuestDb =
            Room.databaseBuilder(context.applicationContext, NextQuestDb::class.java, NAME)
                .build()
    }
}
