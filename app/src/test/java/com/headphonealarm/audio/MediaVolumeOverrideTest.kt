package com.headphonealarm.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaVolumeOverrideTest {
    @Test fun successfulIncreaseRestoresOriginalOnlyOnSameRoute() {
        val override = MediaVolumeOverride()
        override.recordIncrease(before = 4, after = 10, route = 2)
        assertEquals(4, override.takeRestoration(current = 10, route = 2))
        assertNull(override.takeRestoration(current = 10, route = 2))
    }

    @Test fun failedOrClampedIncreaseDoesNotOverwriteVolume() {
        val override = MediaVolumeOverride()
        override.recordIncrease(before = 4, after = 4, route = 2)
        assertNull(override.takeRestoration(current = 4, route = 2))
        override.recordIncrease(before = 4, after = 7, route = 2)
        assertEquals(4, override.takeRestoration(current = 7, route = 2))
    }

    @Test fun manualVolumeChangeCancelsRestoration() {
        val override = MediaVolumeOverride()
        override.recordIncrease(before = 4, after = 10, route = 2)
        assertNull(override.takeRestoration(current = 6, route = 2))
    }

    @Test fun volumeBoostAfterManualChangeTracksNewOriginal() {
        val override = MediaVolumeOverride()
        override.recordIncrease(before = 4, after = 10, route = 2)
        override.observe(current = 6, route = 2)
        override.recordIncrease(before = 6, after = 10, route = 2)
        assertEquals(6, override.takeRestoration(current = 10, route = 2))
    }

    @Test fun repeatedBoostRetainsFirstOriginalAndRouteSwitchCancelsRestoration() {
        val override = MediaVolumeOverride()
        override.recordIncrease(before = 4, after = 7, route = 2)
        override.observe(current = 7, route = 2)
        override.recordIncrease(before = 7, after = 10, route = 2)
        assertNull(override.takeRestoration(current = 10, route = 3))
        override.recordIncrease(before = 3, after = 10, route = 3)
        assertEquals(3, override.takeRestoration(current = 10, route = 3))
    }
}
