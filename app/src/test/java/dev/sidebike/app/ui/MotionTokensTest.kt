package dev.sidebike.app.ui

import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.M3TopAppBarSnapSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MD3 motion token 审计：所有 easing 必须是非线性 cubic-bezier，duration 必须落在官方 token 上。
 */
class MotionTokensTest {

    private val nonlinearTokens = listOf(
        "EmphasizedDecelerate" to M3Motion.EmphasizedDecelerate,
        "EmphasizedAccelerate" to M3Motion.EmphasizedAccelerate,
        "Standard" to M3Motion.Standard,
        "StandardDecelerate" to M3Motion.StandardDecelerate,
        "StandardAccelerate" to M3Motion.StandardAccelerate,
    )

    @Test
    fun `all easing tokens are nonlinear`() {
        nonlinearTokens.forEach { (name, easing) ->
            val mid = easing.transform(0.5f)
            assertNotEquals("$name 在 t=0.5 不能等于线性插值", 0.5f, mid, 0.001f)
            assertEquals("$name 起点应为 0", 0f, easing.transform(0f), 0.01f)
            assertEquals("$name 终点应为 1", 1f, easing.transform(1f), 0.01f)
        }
    }

    @Test
    fun `emphasized ease in and out are opposite halves`() {
        // 强调型减速：前半段走得快（>0.5）；强调型加速：前半段走得慢（<0.5）
        assertTrue(M3Motion.EmphasizedDecelerate.transform(0.5f) > 0.5f)
        assertTrue(M3Motion.EmphasizedAccelerate.transform(0.5f) < 0.5f)
    }

    @Test
    fun `official duration tokens match Material 3 spec`() {
        assertEquals(50, M3Motion.Short1)
        assertEquals(100, M3Motion.Short2)
        assertEquals(150, M3Motion.Short3)
        assertEquals(200, M3Motion.Short4)
        assertEquals(250, M3Motion.Medium1)
        assertEquals(300, M3Motion.Medium2)
        assertEquals(350, M3Motion.Medium3)
        assertEquals(400, M3Motion.Medium4)
        assertEquals(450, M3Motion.Long1)
        assertEquals(500, M3Motion.Long2)
        assertEquals(550, M3Motion.Long3)
        assertEquals(600, M3Motion.Long4)
    }

    @Test
    fun `duration scale is strictly increasing`() {
        val durations = listOf(
            M3Motion.Short1, M3Motion.Short2, M3Motion.Short3, M3Motion.Short4,
            M3Motion.Medium1, M3Motion.Medium2, M3Motion.Medium3, M3Motion.Medium4,
            M3Motion.Long1, M3Motion.Long2, M3Motion.Long3, M3Motion.Long4,
        )
        durations.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", a < b) }
    }

    @Test
    fun `fade-through constants follow Material 3 pattern`() {
        assertEquals(0.92f, M3Motion.FadeThroughIncomingScale)
        assertEquals(1.08f, M3Motion.FadeThroughOutgoingScale)
        assertEquals(M3Motion.Short2, M3Motion.FadeThroughOutDuration)
        assertEquals(M3Motion.Medium1, M3Motion.FadeThroughInDuration)
        assertTrue(M3TopAppBarSnapSpec.toString().isNotEmpty())
    }
}
