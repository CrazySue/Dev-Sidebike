package dev.sidebike.core.util

/**
 * Injectable clock so certificate validity, cache expiry and idle guards are deterministic in tests.
 * Only the plain-JVM `Clock` (not `java.time.Clock`) is used on purpose: `java.time` is available on
 * Android 8+ (API 26) but the project keeps the core to the smallest common API surface.
 */
interface Clock {
    fun currentTimeMillis(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun currentTimeMillis(): Long = System.currentTimeMillis()
        }
    }
}

/** Mutable fake clock for hermetic tests. */
class FakeClock(private var nowMs: Long = 1_700_000_000_000L) : Clock {
    override fun currentTimeMillis(): Long = synchronized(this) { nowMs }

    fun advanceByMillis(deltaMs: Long) {
        synchronized(this) { nowMs += deltaMs }
    }
}
