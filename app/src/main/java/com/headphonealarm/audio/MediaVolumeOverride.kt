package com.headphonealarm.audio

/**
 * Only restore a media volume that this player demonstrably changed on the same output route.
 * A system clamp, a failed write, a user volume-key press or a route switch must not cause an
 * unconditional write of a stale volume to the currently active device.
 */
internal class MediaVolumeOverride {
    private var original: Int? = null
    private var applied: Int? = null
    private var routeId: Int? = null

    fun observe(current: Int, route: Int) {
        if (routeId != route || (applied != null && applied != current)) clear()
    }

    /** Call only after a successful OS write and a readback on the same output route. */
    fun recordIncrease(before: Int, after: Int, route: Int) {
        if (after <= before) return
        if (routeId != route) clear()
        if (original == null) original = before
        applied = after
        routeId = route
    }

    /** Returns the old volume only while the last applied level still owns this output. */
    fun takeRestoration(current: Int?, route: Int?): Int? {
        val restore = if (route != null && route == routeId && current == applied) original else null
        clear()
        return restore
    }

    private fun clear() {
        original = null
        applied = null
        routeId = null
    }
}
