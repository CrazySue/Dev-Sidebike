package dev.sidebike.app.ui

import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SidebikeViewModelTest {

    private lateinit var fake: FakeSidebikeController
    private lateinit var viewModel: SidebikeViewModel

    @Before
    fun setUp() {
        fake = FakeSidebikeController()
        installFakeController(fake)
        viewModel = SidebikeViewModel(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun exposesControllerStateAndLogsFlows() {
        assertEquals(RunStatus.RUNNING, viewModel.state.value.status)
        assertEquals(PreviewData.logs.size, viewModel.logs.value.size)

        fake.setState(PreviewData.state(status = RunStatus.ERROR))
        fake.setLogs(emptyList())

        assertEquals(RunStatus.ERROR, viewModel.state.value.status)
        assertTrue(viewModel.logs.value.isEmpty())
    }

    @Test
    fun startStopAndAbortForwardToController() {
        viewModel.start()
        viewModel.start()
        assertEquals(2, fake.startCalls)

        viewModel.stop()
        assertEquals(1, fake.stopCalls)

        viewModel.onStartAborted("用户拒绝")
        assertEquals("用户拒绝", fake.abortedReason)
    }

    @Test
    fun configAndLogActionsForwardToController() = runBlocking {
        val updated = PreviewData.config.copy(
            proxy = PreviewData.config.proxy.copy(port = 31200),
        )
        viewModel.updateConfig(updated)
        assertEquals(1, fake.updateConfigCalls)
        assertEquals(31200, fake.lastConfig?.proxy?.port)

        viewModel.clearLogs()
        assertEquals(1, fake.clearLogsCalls)

        viewModel.reloadConfig()
        assertEquals(1, fake.reloadConfigCalls)
    }

    @Test
    fun caAndDiagnosticsSuspendFunctionsForward() = runBlocking {
        fake.caPem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        assertEquals(fake.caPem, viewModel.caCertificatePem())
        assertEquals(1, fake.caPemRequests)

        val diagnostics = viewModel.exportDiagnostics()
        assertTrue(diagnostics.isNotBlank())
        assertEquals(1, fake.exportCalls)

        viewModel.refreshCaInstalled()
        assertEquals(1, fake.refreshCaCalls)
    }
}
