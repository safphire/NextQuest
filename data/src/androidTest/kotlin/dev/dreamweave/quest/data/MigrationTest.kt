// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dreamweave.quest.data.db.QuestDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens every historical schema (data/schemas, committed) and migrates it to the
 * current version, validating the result against the exported schema. Runs under
 * Robolectric in CI on every push; the same checks run on a device in androidTest.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), QuestDatabase::class.java)

    @Test
    fun everySchemaVersionMigratesToCurrent() {
        for (from in 1..QuestDatabase.VERSION) {
            val name = "migration-$from.db"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, QuestDatabase.VERSION, true, *QuestDatabase.MIGRATIONS).close()
        }
    }

    /** v1 → v2 adds the sync flag: existing events survive unchanged and start unpushed. */
    @Test
    fun v1EventsSurviveAndAreQueuedForSync() {
        val name = "migration-data.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO events (id, device_id, seq, ts, actor, type, payload) VALUES " +
                    "('0190a000-0000-7000-8000-000000000001', '0190a000-0000-7000-8000-0000000000aa', 1, 1720000000000, 'user', 'quest.created', '{\"questId\":\"q\",\"title\":\"Kept\"}')",
            )
        }
        helper.runMigrationsAndValidate(name, 2, true, *QuestDatabase.MIGRATIONS).use { db ->
            db.query("SELECT seq, ts, payload, pushed FROM events").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(1_720_000_000_000L, c.getLong(1))
                assertTrue(c.getString(2).contains("Kept"))
                assertEquals(0, c.getInt(3))
                assertEquals(1, c.count)
            }
        }
    }
}
