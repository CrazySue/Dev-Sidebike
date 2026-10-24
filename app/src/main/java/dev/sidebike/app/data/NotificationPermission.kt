package dev.sidebike.app.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS runtime-permission helper (Android 13+).
 *
 * The VPN runs as a foreground service whose notification carries the "stop" action; without the
 * notification permission on Android 13+ the user cannot see the foreground notification nor stop
 * the VPN from the shade (QA-002). The Compose UI (ui workstream) should call
 * [ensureRequested] from `MainActivity.onCreate` (or a `LaunchedEffect`) and optionally gate any
 * "notification disabled" hint on [isGranted].
 *
 * Below API 33 the permission does not exist and everything reports granted.
 */
object NotificationPermission {

    /** Request code used by [ensureRequested]; the UI may also use its own code if it prefers. */
    const val REQUEST_CODE: Int = 0x5DB2

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests the notification permission when missing (no-op when already granted or below
     * Android 13). Returns true when the permission is already granted so callers can decide whether
     * to show an explanatory hint.
     */
    fun ensureRequested(activity: Activity): Boolean {
        if (isGranted(activity)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE,
            )
        }
        return false
    }
}
