package net.kigawa.kivra.acceptance

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A java.time.Clock the test advances explicitly. No sleep anywhere in the acceptance harness: time only moves when a
 * scenario calls [advance].
 */
class VirtualClock(private var now: Instant = Instant.parse("2026-10-15T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now

    fun advance(by: Duration) {
        require(!by.isNegative) { "virtual time moves forward only" }
        now = now.plus(by)
    }
}
