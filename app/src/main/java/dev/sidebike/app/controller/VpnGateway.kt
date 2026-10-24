package dev.sidebike.app.controller

import android.content.Context
import android.net.VpnService
import dev.sidebike.app.vpn.SidebikeVpnService

/**
 * Thin seam over the Android VPN service so the controller state machine can be unit tested on the
 * JVM with a fake gateway (a real `VpnService` needs an instrumented device/Robolectric shadow).
 */
internal interface VpnGateway {
    fun start(context: Context)
    fun stop(context: Context)
}

/** Production gateway: routes to [SidebikeVpnService] start/stop entry points. */
internal object AndroidVpnGateway : VpnGateway {
    override fun start(context: Context) {
        SidebikeVpnService.start(context)
    }

    override fun stop(context: Context) {
        SidebikeVpnService.stop(context)
    }
}

/**
 * Seam over `VpnService.prepare()`.
 *
 * Returns true when the user still has to confirm the VPN consent dialog (or the app is not
 * configured as always-on VPN). The UI is responsible for launching the consent dialog; the
 * controller only refuses to start until consent exists so it never lands in a zombie state.
 */
internal fun interface VpnConsentChecker {
    fun requiresUserConsent(context: Context): Boolean
}

/** Production consent check backed by the real [VpnService.prepare]. */
internal object AndroidVpnConsentChecker : VpnConsentChecker {
    override fun requiresUserConsent(context: Context): Boolean = VpnService.prepare(context) != null
}
