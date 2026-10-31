package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

import androidx.compose.ui.unit.dp
import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.RuleImportSummary
import dev.sidebike.app.controller.RuleSourceRefreshSummary
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.RulesScreen
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.1-alpha 规则管理页测试：4 个 tab、规则编辑/删除、直连/国内/规则源动作。 */
class RulesScreenTest : ComposeTestBase() {

    private var lastConfig: SidebikeConfig? = null
    private var savedRule: RuleDraft? = null
    private var deletedRule: Pair<String, String>? = null
    private var directPassDomains: List<String>? = null
    private var domesticDomains: List<String>? = null
    private var importedFormat: RuleSourceFormat? = null
    private var importedText: String? = null
    private var refreshCalls = 0
    private var reloadCalls = 0
    private val messages = mutableListOf<String>()

    private fun actions() = SidebikeActions(
        updateConfig = { lastConfig = it },
        reloadConfig = { reloadCalls++ },
        saveRule = { savedRule = it },
        deleteRule = { domain, path -> deletedRule = domain to path },
        setDirectPassDomains = { directPassDomains = it },
        setDomesticAllowList = { domesticDomains = it },
        importRulesFromText = { text, format, _ ->
            importedText = text
            importedFormat = format
            RuleImportSummary(addedIntercepts = 1)
        },
        refreshRuleSources = {
            refreshCalls++
            RuleSourceRefreshSummary(updatedSources = listOf("johnshall-cnip"), importedRules = 2)
        },
    )

    private fun setRules(
        state: dev.sidebike.app.state.SidebikeUiState = PreviewData.state(),
        initialTab: Int = 0,
    ) {
        setScreen {
            RulesScreen(
                state = state,
                contentPadding = PaddingValues(0.dp),
                actions = actions(),
                onShowMessage = { messages += it },
                initialTab = initialTab,
            )
        }
    }

    private fun assertShowMessageContains(fragment: String) {
        assertTrue("未收到包含「$fragment」的提示，实际=$messages", messages.any { it.contains(fragment) })
    }

    // ------------------------------------------------------------------ intercepts

    @Test
    fun rendersFourTabsAndInterceptCards() {
        setRules()

        composeRule.onNodeWithTag("rules-tab:0").assertExists()
        composeRule.onNodeWithTag("rules-tab:1").assertExists()
        composeRule.onNodeWithTag("rules-tab:2").assertExists()
        composeRule.onNodeWithTag("rules-tab:3").assertExists()
        composeRule.onAllNodesWithText("github.com").onFirst().assertExists()
        composeRule.onNodeWithTag("rules-add-rule").assertExists()
    }

    @Test
    fun searchFiltersRulesByDomain() {
        setRules()

        setText("rules-search", "gstatic")
        settle()
        composeRule.onAllNodesWithText("www.gstatic.com").onFirst().assertExists()
        composeRule.onAllNodesWithText("api.github.com").assertCountEquals(0)
    }

    @Test
    fun expandRuleShowsActionDetailsAndEditDelete() {
        setRules()

        composeRule.onNodeWithTag("rule-expand:github.com").performClick()
        settle()
        composeRule.onAllNodesWithText("github.moeyy.xyz:443").onFirst().assertExists()
        composeRule.onNodeWithTag("rule-edit:github.com:/").assertExists()

        composeRule.onNodeWithTag("rule-delete:github.com:/api/*").performClick()
        settle()
        composeRule.onNodeWithText("删除该规则？").assertExists()
        composeRule.onNodeWithTag("confirm-positive").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { deletedRule != null }
        assertEquals("github.com" to "/api/*", deletedRule)
    }

    @Test
    fun addRuleDialogOpensAndValidates() {
        setRules()

        composeRule.onNodeWithTag("rules-add-rule").performClick()
        settle()
        composeRule.onNodeWithTag("rule-editor-dialog").assertExists()
        composeRule.onNodeWithTag("rule-save").assertIsNotEnabled()

        setText("rule-domain-input", "example.org")
        setText("rule-sni-input", "baidu.com")
        settle()
        composeRule.onNodeWithTag("rule-save").assertIsEnabled()
        composeRule.onNodeWithTag("rule-save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { savedRule != null }
        assertEquals("example.org", savedRule?.domainPattern)
        assertEquals("baidu.com", savedRule?.sni)
    }

    // ------------------------------------------------------------------ direct pass

    @Test
    fun directPassTabListsAndRemovesDomains() {
        setRules(initialTab = 1)

        composeRule.onNodeWithTag("direct-pass-list").assertExists()
        composeRule.onAllNodesWithText("raw.githubusercontent.com").onFirst().assertExists()

        composeRule.onNodeWithTag("direct-remove:raw.githubusercontent.com").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { directPassDomains != null }
        assertTrue(directPassDomains?.contains("raw.githubusercontent.com") == false)
    }

    @Test
    fun directPassAddDialogCallsController() {
        setRules(initialTab = 1)

        composeRule.onNodeWithTag("direct-pass-add").performClick()
        settle()
        composeRule.onNodeWithTag("direct-add-dialog").assertExists()
        setText("single-input", "new.example.com")
        settle()
        composeRule.onNodeWithTag("single-input-save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { directPassDomains != null }
        assertTrue(directPassDomains?.contains("new.example.com") == true)
    }

    // ------------------------------------------------------------------ domestic

    @Test
    fun domesticTabListsAndRemovesDomains() {
        setRules(initialTab = 2)

        composeRule.onNodeWithTag("domestic-list").assertExists()
        composeRule.onAllNodesWithText("example.cn").onFirst().assertExists()

        composeRule.onNodeWithTag("domestic-remove:example.cn").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { domesticDomains != null }
        assertTrue(domesticDomains?.contains("example.cn") == false)
    }

    @Test
    fun domesticResetCallsReloadConfig() {
        setRules(initialTab = 2)

        composeRule.onNodeWithTag("domestic-reset").performClick()
        settle()
        composeRule.onNodeWithTag("confirm-positive").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { reloadCalls == 1 }
        assertEquals(1, reloadCalls)
    }

    // ------------------------------------------------------------------ sources

    @Test
    fun sourcesTabShowsSourcesAndPresetAddPersists() {
        setRules(initialTab = 3)

        composeRule.onNodeWithTag("sources-list").assertExists()
        composeRule.onNodeWithTag("source-card:johnshall-cnip").assertExists()
        composeRule.onNodeWithTag("sources-add-preset").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { lastConfig != null }
        assertTrue(lastConfig?.ruleSources?.any { it.id == "johnshall-cnip" } == true)
    }

    @Test
    fun sourcesRefreshCallsControllerAndShowsSummary() {
        setRules(initialTab = 3)

        composeRule.onNodeWithTag("sources-refresh-all").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { refreshCalls == 1 }
        assertEquals(1, refreshCalls)
        assertShowMessageContains("更新 1 个源")
    }

    @Test
    fun sourcesImportDialogOpens() {
        setRules(initialTab = 3)

        composeRule.onNodeWithTag("sources-import").performClick()
        settle()
        composeRule.onNodeWithTag("rule-import-dialog").assertExists()
        setText("import-text-input", "DOMAIN-SUFFIX,ads.example.com,REJECT")
        settle()
        composeRule.onNodeWithTag("import-save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { importedText != null }
        assertTrue(importedText!!.contains("ads.example.com"))
    }

    @Test
    fun sourceTogglePersistsViaConfig() {
        setRules(initialTab = 3)

        composeRule.onNodeWithTag("source-switch:custom-example").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { lastConfig != null }
        val source = lastConfig?.ruleSources?.firstOrNull { it.id == "custom-example" }
        assertEquals(true, source?.enabled)
    }

    @Test
    fun sourcesScrollToLastCard() {
        setRules(initialTab = 3)

        composeRule.onNodeWithTag("sources-list")
            .performScrollToNode(hasTestTag("source-refresh:custom-example"))
        composeRule.onNodeWithTag("source-refresh:custom-example").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { refreshCalls == 1 }
    }

    private fun scrollToText(text: String) {
        composeRule.onNodeWithTag("rules-list").performScrollToNode(hasText(text))
    }
}
