package dev.sidebike.app.data

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the POST_NOTIFICATIONS helper (QA-002): the VPN notification carries the
 * only in-shade "stop VPN" action, so Android 13+ needs the runtime permission requested by the UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationPermissionTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun deniedPermissionReportsFalse() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationPermission.isGranted(application))
    }

    @Test
    fun grantedPermissionReportsTrue() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationPermission.isGranted(application))
    }
}
