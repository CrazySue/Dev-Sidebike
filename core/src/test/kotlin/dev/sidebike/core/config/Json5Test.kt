package dev.sidebike.core.config

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Json5Test {

    @Test
    fun `parses comments single quotes unquoted keys and trailing commas`() {
        val text = """
            {
              // line comment
              app: {
                remoteConfig: {
                  url: 'https://example.com/remote_config.json5',
                  enabled: true, // trailing
                },
                list: [1, 2, 3,],
                name: "it's fine",
              },
              /* block comment */
              intercepts: {
                'github.com': { '.*': { sni: 'baidu.com' } },
              }
            }
        """.trimIndent()

        val root = Json5.parse(text).jsonObject
        val app = root["app"]!!.jsonObject
        val remote = app["remoteConfig"]!!.jsonObject
        assertEquals("https://example.com/remote_config.json5", remote["url"]!!.jsonPrimitive.content)
        assertTrue(remote["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(3, app["list"]!!.jsonArray.size)
        assertEquals("it's fine", app["name"]!!.jsonPrimitive.content)
        val action = root["intercepts"]!!.jsonObject["github.com"]!!.jsonObject[".*"]!!.jsonObject
        assertEquals("baidu.com", action["sni"]!!.jsonPrimitive.content)
    }

    @Test
    fun `string contents with comment markers and commas are preserved`() {
        val text = """{ a: 'x//y', b: "c/*d*/", c: 'comma, }' }"""
        val root = Json5.parse(text).jsonObject
        assertEquals("x//y", root["a"]!!.jsonPrimitive.content)
        assertEquals("c/*d*/", root["b"]!!.jsonPrimitive.content)
        assertEquals("comma, }", root["c"]!!.jsonPrimitive.content)
    }

    @Test
    fun `escaped quotes in single quoted strings`() {
        val root = Json5.parse("""{ a: 'it\'s \"ok\"', b: 'line\nnext' }""").jsonObject
        assertEquals("it's \"ok\"", root["a"]!!.jsonPrimitive.content)
        assertEquals("line\nnext", root["b"]!!.jsonPrimitive.content)
    }

    @Test
    fun `standard json still parses`() {
        val root = Json5.parse("""{"x":1,"y":[true,null]}""").jsonObject
        assertEquals(1, root["x"]!!.jsonPrimitive.int)
        assertEquals(2, root["y"]!!.jsonArray.size)
        assertNotNull(root["y"])
    }

    @Test(expected = Exception::class)
    fun `broken json throws`() {
        Json5.parse("{ a: ")
    }
}
