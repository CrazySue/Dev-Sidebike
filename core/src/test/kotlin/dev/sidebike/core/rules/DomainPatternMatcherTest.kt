package dev.sidebike.core.rules

import dev.sidebike.core.log.NoopSidebikeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPatternMatcherTest {

    private fun matcher(vararg pairs: Pair<String, Int>): DomainPatternMatcher<Int> =
        DomainPatternMatcher(linkedMapOf(*pairs), NoopSidebikeLogger)

    @Test
    fun `exact patterns match only themselves`() {
        val m = matcher("github.com" to 1)
        assertEquals(1, m.match("github.com"))
        assertNull(m.match("api.github.com"))
        assertNull(m.match("github.com.cn"))
    }

    @Test
    fun `single star prefix wildcard matches subdomains and apex fast path`() {
        val m = matcher("*.icloud.com" to 2)
        assertEquals(2, m.match("a.icloud.com"))
        assertEquals(2, m.match("a.b.icloud.com"))
        // 上游 matchHostname 的 origin["*."+hostname] 快查：apex 也会命中
        assertEquals(2, m.match("icloud.com"))
        assertNull(m.match("icloud.com.cn"))
    }

    @Test
    fun `regex patterns starting with caret are used verbatim`() {
        val m = matcher("^(.+\\.)?x\\.com$" to 3)
        assertEquals(3, m.match("x.com"))
        assertEquals(3, m.match("www.x.com"))
        assertNull(m.match("x.com.cn"))
    }

    @Test
    fun `star patterns are converted with dot escaping`() {
        val m = matcher("x*.com" to 4)
        assertEquals(4, m.match("xabc.com"))
        assertFalse(m.match("xabc.com.cn") != null)
    }

    @Test
    fun `dot prefix normalized to star prefix`() {
        val dotOnly = matcher(".example.com" to 5)
        assertEquals(5, dotOnly.match("a.example.com"))
        // 同时存在 *.example.com 时忽略 .example.com（值来自 * 形式）
        val both = matcher(".example.com" to 5, "*.example.com" to 6)
        assertEquals(6, both.match("a.example.com"))
    }

    @Test
    fun `universal patterns match anything`() {
        assertEquals(10, matcher("*" to 10).match("anything.example"))
        assertEquals(11, matcher("true" to 11).match("anything.example"))
    }

    @Test
    fun `match priority exact over star dot over star over regex`() {
        val m = matcher(
            "github.com" to 1,
            "*.github.com" to 2,
            "*github.com" to 3,
        )
        assertEquals(1, m.match("github.com"))
        val sub = matcher("*api.github.com" to 3, "*.github.com" to 2)
        // matchHostname 前缀查找顺序：*.host 先于 *host
        assertEquals(2, sub.match("api.github.com"))
    }

    @Test
    fun `matchAll returns ascending priority regex then star host then exact`() {
        val m = matcher(
            "github.com" to 1,
            "*github.com" to 2,
        )
        val all = m.matchAll("github.com")
        val priorities = all.map { it.priority }
        assertEquals(priorities.sorted(), priorities)
        assertTrue(all.any { it.value == 1 })
        assertTrue(all.any { it.value == 2 })
    }

    @Test
    fun `null values are skipped`() {
        val m = DomainPatternMatcher<Int>(linkedMapOf("a.com" to null, "b.com" to 1), NoopSidebikeLogger)
        assertNull(m.match("a.com"))
        assertEquals(1, m.match("b.com"))
        assertEquals(1, m.size())
    }

    @Test
    fun `single pattern matcher helper`() {
        assertTrue(DomainPatternMatcher.single("*.x.com").match("a.x.com") == true)
        assertTrue(DomainPatternMatcher.single("*.x.com").match("b.com") == null)
    }
}
