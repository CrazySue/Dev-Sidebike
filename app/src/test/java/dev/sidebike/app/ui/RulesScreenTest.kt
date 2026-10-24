package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.RulesScreen
import dev.sidebike.core.api.SidebikeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesScreenTest : ComposeTestBase() {

    private var lastConfig: SidebikeConfig? = null

    private fun setRules(config: SidebikeConfig = PreviewData.config) {
        setScreen {
            RulesScreen(
                config = config,
                contentPadding = PaddingValues(0.dp),
                onConfigChange = { lastConfig = it },
            )
        }
    }

    private fun scrollRulesTo(tag: String) {
        composeRule.onNodeWithTag("rules-list").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun rendersDomainsAndSummary() {
        setRules()

        composeRule.onNodeWithText("github.com").assertExists()
        composeRule.onNodeWithText("共 4 个域名", substring = true).assertExists()
        composeRule.onNodeWithText("共 4 个域名 · 5 条路径规则 · 1 个直连放行").assertExists()
    }

    @Test
    fun searchFiltersDomainList() {
        setRules()

        composeRule.onNodeWithTag("rules-search").performTextInput("gstatic")
        settle()

        composeRule.onNodeWithText("www.gstatic.com").assertExists()
        composeRule.onNodeWithText("api.github.com").assertDoesNotExist()
    }

    @Test
    fun searchShowsNoResultState() {
        setRules()

        composeRule.onNodeWithTag("rules-search").performTextInput("not-a-domain-zzz")
        settle()

        composeRule.onNodeWithText("没有匹配的规则").assertExists()
    }

    @Test
    fun emptyConfigShowsEmptyState() {
        setRules(PreviewData.emptyConfig)

        composeRule.onNodeWithText("暂无拦截规则").assertExists()
    }

    @Test
    fun directOnlySwitchPersistsViaWhiteList() {
        setRules()

        scrollRulesTo("rule-switch:github.com")
        composeRule.onNodeWithTag("rule-switch:github.com").performClick()
        settle()

        val config = lastConfig
        assertTrue("onConfigChange 应被调用", config != null)
        assertEquals(true, config?.whiteList?.get("github.com"))
        // 只读的 intercepts 规则不应被删除
        assertTrue(config?.intercepts?.containsKey("github.com") == true)
    }

    @Test
    fun disableDirectOnlyRemovesWhiteListEntry() {
        setRules()

        scrollRulesTo("rule-switch:raw.githubusercontent.com")
        composeRule.onNodeWithTag("rule-switch:raw.githubusercontent.com").performClick()
        settle()

        val config = lastConfig
        assertTrue(config != null)
        assertFalse(config!!.whiteList.containsKey("raw.githubusercontent.com"))
    }

    @Test
    fun expandedDomainShowsActionDetails() {
        setRules()

        composeRule.onNodeWithText("github.com").performClick()
        settle()

        composeRule.onAllNodesWithText("镜像代理").onFirst().assertExists()
        composeRule.onNodeWithText("github.moeyy.xyz:443").assertExists()
        composeRule.onAllNodesWithText("本地合成响应").onFirst().assertExists()
    }

    @Test
    fun mitmFeatureSwitchWritesBothContractFlags() {
        setRules()

        scrollRulesTo("feature-switch:mitm")
        composeRule.onNodeWithTag("feature-switch:mitm").performClick()
        settle()

        val config = lastConfig
        assertTrue(config != null)
        assertEquals(false, config!!.proxy.mitmEnabled)
        assertEquals(false, config.mitm.enabled)
    }

    @Test
    fun proxyFeatureSwitchPersists() {
        setRules()

        scrollRulesTo("feature-switch:proxy")
        composeRule.onNodeWithTag("feature-switch:proxy").performClick()
        settle()

        assertEquals(false, lastConfig?.proxy?.enabled)
    }
}
