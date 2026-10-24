package dev.sidebike.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle phase reported by [SidebikeVpnService] to whoever coordinates the VPN
 * (`DefaultSidebikeController`, or a future foreground-only host).
 *
 * The bridge is an in-process [StateFlow] because the controller and the VpnService always live in
 * the same app process: this is the standard Android VPN architecture (the service owns the tun fd,
 * the controller owns engine + UI state). A [StateFlow] gives new collectors the current value, so
 * a controller that starts collecting late still observes the last phase without a race.
 *
 * Testing note: controller JVM tests drive `VpnServiceBridge` directly instead of instantiating a
 * real VpnService (which requires an Android instrumented environment).
 */
sealed interface VpnPhase {

    /** Service not started or already torn down. */
    data object Idle : VpnPhase

    /** Foreground state entered, tun/engine still being prepared. */
    data object Preparing : VpnPhase

    /** tun established, reader + resolver workers running. */
    data object Running : VpnPhase

    /** Clean stop (user, notification action or controller request). */
    data object Stopped : VpnPhase

    /** The system revoked the VPN (`onRevoke`), e.g. the user disabled it in Settings. */
    data object Revoked : VpnPhase

    /** Startup/runtime failure; the service already tore down its tun + foreground state. */
    data class Failed(val message: String) : VpnPhase
}

/** Process-wide service -> controller phase channel. */
object VpnServiceBridge {

    private val _phase = MutableStateFlow<VpnPhase>(VpnPhase.Idle)
    val phase: StateFlow<VpnPhase> = _phase.asStateFlow()

    fun publish(phase: VpnPhase) {
        _phase.value = phase
    }

    /** Resets to [VpnPhase.Idle] before a new start attempt (avoids observing a stale phase). */
    fun reset() {
        _phase.value = VpnPhase.Idle
    }
}
