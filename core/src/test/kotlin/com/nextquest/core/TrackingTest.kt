package com.nextquest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingTest {

    @Test
    fun `default cap is five`() {
        assertTrue(Tracking.canTrack(4))
        assertFalse(Tracking.canTrack(5))
    }

    @Test
    fun `cap is configurable within three to five`() {
        assertTrue(Tracking.canTrack(2, cap = 3))
        assertFalse(Tracking.canTrack(3, cap = 3))
    }

    @Test
    fun `out-of-range caps clamp to the allowed range`() {
        assertEquals(3, Tracking.clampCap(1))
        assertEquals(5, Tracking.clampCap(99))
        assertFalse(Tracking.canTrack(5, cap = 99))
        assertTrue(Tracking.canTrack(2, cap = 0))
    }
}
