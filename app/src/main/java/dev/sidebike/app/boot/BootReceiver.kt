package dev.sidebike.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the VPN after reboot when the user opted into auto-start. Implemented later. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
