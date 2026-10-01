// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class PlaceholderTest {
    @Test fun robolectricBoots() {
        assertNotNull(ApplicationProvider.getApplicationContext<Context>())
    }
}
