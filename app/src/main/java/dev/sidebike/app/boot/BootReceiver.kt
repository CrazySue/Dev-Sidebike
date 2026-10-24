package dev.sidebike.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import dev.sidebike.app.controller.SidebikeController
import dev.sidebike.app.data.SidebikeConfigStore

/**
 * Re-arms the VPN after reboot when the user opted into "auto start on boot".
 *
 * Constraints handled here:
 *  - The receiver cannot show the VPN consent dialog; when `VpnService.prepare()` says consent is
 *    missing we skip the start (the system's always-on VPN feature covers the consent-less case).
 *  - Android 12+ background foreground-service start restrictions do not apply to BOOT_COMPLETED
 *    receivers, but we still wrap everything defensively because OEM ROMs (MIUI/HyperOS etc.) may
 *    deny it.
 *  - The actual start goes through the controller so the UI state machine stays consistent; the
 *    controller itself re-checks consent.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        handleBoot(
            appContext = context.applicationContext,
            action = intent.action,
            isConsentPending = { ctx -> runCatching { VpnService.prepare(ctx) != null }.getOrDefault(true) },
        )
    }

    /** Testable body: [isConsentPending] is the seam for `VpnService.prepare()` (Robolectric/JVM). */
    internal fun handleBoot(
        appContext: Context,
        action: String?,
        isConsentPending: (Context) -> Boolean,
    ) {
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val config = SidebikeConfigStore(appContext).load()
            if (!config.app.autoStartOnBoot) {
                Log.i(TAG, "Auto start on boot disabled; nothing to do")
                return
            }
            if (isConsentPending(appContext)) {
                Log.w(TAG, "VPN consent missing after boot; cannot auto start without UI")
                return
            }
            SidebikeController.get(appContext).start()
            Log.i(TAG, "Auto start on boot triggered")
        } catch (error: Throwable) {
            // Never crash a broadcast receiver: a boot-time exception would show an ANR/crash dialog.
            Log.e(TAG, "Auto start on boot failed", error)
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
