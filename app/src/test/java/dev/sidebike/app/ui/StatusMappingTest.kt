package dev.sidebike.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.R
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.components.RunControlPhase
import dev.sidebike.app.ui.components.StatusVisuals
import dev.sidebike.app.ui.components.runControlPhaseOf
import dev.sidebike.app.ui.screens.CaStatus
import dev.sidebike.app.ui.screens.caStatusOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 状态枚举 -> 可见文案/相位 的映射测试（对应验收要求：status enum maps to expected visible text）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusMappingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyRunStatusHasLabelAndDescription() {
        RunStatus.entries.forEach { status ->
            val label = context.getString(StatusVisuals.labelRes(status))
            val description = context.getString(StatusVisuals.descriptionRes(status))
            assertTrue("$status label 不应为空", label.isNotBlank())
            assertTrue("$status description 不应为空", description.isNotBlank())
        }
    }

    @Test
    fun runStatusLabelsMatchExpectedChineseCopy() {
        assertEquals("未运行", context.getString(StatusVisuals.labelRes(RunStatus.IDLE)))
        assertEquals("正在准备 VPN", context.getString(StatusVisuals.labelRes(RunStatus.PREPARING)))
        assertEquals("正在启动", context.getString(StatusVisuals.labelRes(RunStatus.STARTING)))
        assertEquals("运行中", context.getString(StatusVisuals.labelRes(RunStatus.RUNNING)))
        assertEquals("正在停止", context.getString(StatusVisuals.labelRes(RunStatus.STOPPING)))
        assertEquals("启动失败", context.getString(StatusVisuals.labelRes(RunStatus.ERROR)))
    }

    @Test
    fun everyRunStatusMapsToDistinctLabel() {
        val labels = RunStatus.entries.map { context.getString(StatusVisuals.labelRes(it)) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun runStatusMapsToRunControlPhase() {
        assertEquals(RunControlPhase.START, runControlPhaseOf(RunStatus.IDLE))
        assertEquals(RunControlPhase.STARTING, runControlPhaseOf(RunStatus.PREPARING))
        assertEquals(RunControlPhase.STARTING, runControlPhaseOf(RunStatus.STARTING))
        assertEquals(RunControlPhase.STOP, runControlPhaseOf(RunStatus.RUNNING))
        assertEquals(RunControlPhase.STOPPING, runControlPhaseOf(RunStatus.STOPPING))
        assertEquals(RunControlPhase.START, runControlPhaseOf(RunStatus.ERROR))
    }

    @Test
    fun caStatusMapping() {
        assertEquals(CaStatus.INSTALLED, caStatusOf(caInstalled = true, mitmEnabled = false))
        assertEquals(CaStatus.NOT_INSTALLED, caStatusOf(caInstalled = false, mitmEnabled = true))
        assertEquals(CaStatus.NOT_GENERATED, caStatusOf(caInstalled = false, mitmEnabled = false))
        assertNotEquals(CaStatus.INSTALLED, caStatusOf(caInstalled = false, mitmEnabled = false))
    }

    @Test
    fun runStatusStringsAreChinese() {
        RunStatus.entries.forEach { status ->
            val label = context.getString(StatusVisuals.labelRes(status))
            assertTrue("$status 应为中文文案: $label", label.any { it.code in 0x4E00..0x9FFF })
        }
        // 每个状态的标签必须唯一（避免 6 个状态共用同一文案）
        val labels = RunStatus.entries.map { context.getString(StatusVisuals.labelRes(it)) }
        assertEquals(RunStatus.entries.size, labels.toSet().size)
    }
}
