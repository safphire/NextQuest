package com.nextquest.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One test per schema version (CLAUDE.md): every historical schema must open
 * green before release. When version 2 lands, this file gains
 * `migrate1To2()` — never a destructive fallback.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NextQuestDb::class.java,
    )

    @Test
    fun schemaV1_createsCleanly() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO quests (id, title, notes, status, tracked, orderIndex, createdAt, touchedAt) " +
                    "VALUES ('q1', 'Diarytale', '', 'OPEN', 1, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO event_log (id, questId, type, at) VALUES ('e1', 'q1', 'CREATED', 1)"
            )
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
