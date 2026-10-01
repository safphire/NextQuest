// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dreamweave.quest.data.db.QuestDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
}
