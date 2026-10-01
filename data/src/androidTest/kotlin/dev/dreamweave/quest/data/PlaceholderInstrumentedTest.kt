// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class PlaceholderInstrumentedTest { @Test fun works() = assertEquals(1, Placeholder.V) }
