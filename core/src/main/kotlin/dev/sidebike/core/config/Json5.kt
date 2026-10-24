package dev.sidebike.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 极简 JSON5 预处理器。
 *
 * 上游配置（`~/.dev-sidecar/remote_config.json5`、历史 `config.json5`）使用 JSON5 语法，
 * 而 kotlinx.serialization 只接受严格 JSON（配置要求见 `docs/architecture/02-core-contracts.md:26`）。
 * 这里把 JSON5 的 4 个高频特性降级成严格 JSON，其余（如 `NaN`、多行字符串）不做支持：
 *
 * 1. `//` 行注释、块注释（斜杠星号包裹）；
 * 2. 单引号字符串 → 双引号字符串（会正确转义 `"`、`\`）；
 * 3. 未加引号的对象 key（`app:`、`remoteConfig:`）→ `"app":`；
 * 4. 尾随逗号（`[1,2,]`、`{"a":1,}`）→ 删除。
 *
 * 注意：实现是逐字符状态机，字符串内部的 `//`、斜杠星号注释符、`,`、`}` 不会被误处理。
 * 解析失败时由 [parse] 抛 [IllegalArgumentException]（带原始文本偏移信息由 kotlinx 提供）。
 */
object Json5 {

    private const val IDENT_START = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$"
    private const val IDENT_PART = IDENT_START + "0123456789.-"

    /** 严格解析（先预处理）。[text] 可以是 JSON5 或标准 JSON。 */
    fun parse(text: String): JsonElement = Json.parseToJsonElement(preprocess(text))

    /** 宽松解析（预处理后仍用 lenient 模式，容错尾逗号/注释漏网等边界）。 */
    fun parseLenient(text: String): JsonElement {
        val lenient = Json { isLenient = true; ignoreUnknownKeys = true }
        return lenient.parseToJsonElement(preprocess(text))
    }

    fun preprocess(raw: String): String {
        val text = raw.removePrefix("\uFEFF")
        val out = StringBuilder(text.length + 32)
        var i = 0
        val brackets = ArrayDeque<Char>() // '{' or '['，用于判断当前是否在对象内

        fun skipWhitespaceFrom(pos: Int): Int {
            var p = pos
            while (p < text.length && text[p].isWhitespace()) p++
            return p
        }

        /** 向前找下一个"有效字符"（跳过空白与注释），找不到返回 '\u0000'。 */
        fun nextSignificant(pos: Int): Char {
            var p = pos
            while (p < text.length) {
                val c = text[p]
                if (c.isWhitespace()) {
                    p++
                    continue
                }
                if (c == '/' && p + 1 < text.length) {
                    when (text[p + 1]) {
                        '/' -> {
                            p += 2
                            while (p < text.length && text[p] != '\n') p++
                            continue
                        }

                        '*' -> {
                            p += 2
                            while (p + 1 < text.length && !(text[p] == '*' && text[p + 1] == '/')) p++
                            p = minOf(text.length, p + 2)
                            continue
                        }
                    }
                }
                return c
            }
            return '\u0000'
        }

        /** 在对象内部、当前处于"可写 key"位置时，尝试把裸标识符 key 加上双引号。 */
        fun tryConsumeBareKey(): Boolean {
            val p0 = skipWhitespaceFrom(i)
            if (p0 >= text.length) return false
            val first = text[p0]
            if (!IDENT_START.contains(first)) return false
            var p = p0 + 1
            while (p < text.length && IDENT_PART.contains(text[p])) p++
            val p1 = skipWhitespaceFrom(p)
            if (p1 >= text.length || text[p1] != ':') return false
            val ident = text.substring(p0, p)
            out.append('"').append(ident).append('"').append(':')
            i = p1 + 1
            return true
        }

        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                    if (i < text.length) {
                        out.append('\n')
                        i++
                    }
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = minOf(text.length, i + 2)
                    out.append(' ')
                }

                c == '"' -> { // 双引号字符串：原样复制（保留转义）
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val ch = text[i]
                        out.append(ch)
                        i++
                        if (ch == '\\' && i < text.length) {
                            out.append(text[i])
                            i++
                        } else if (ch == '"') {
                            break
                        }
                    }
                }

                c == '\'' -> { // 单引号字符串 → 双引号 + 必要转义
                    out.append('"')
                    i++
                    while (i < text.length) {
                        val ch = text[i]
                        if (ch == '\\' && i + 1 < text.length) {
                            val esc = text[i + 1]
                            when (esc) {
                                '\'' -> out.append('\'')
                                '"' -> out.append("\\\"")
                                '\\' -> out.append("\\\\")
                                'n' -> out.append("\\n")
                                'r' -> out.append("\\r")
                                't' -> out.append("\\t")
                                'b' -> out.append("\\b")
                                'f' -> out.append("\\f")
                                'u' -> {
                                    out.append("\\u")
                                    out.append(text, i + 2, minOf(text.length, i + 6))
                                }

                                '\n' -> Unit // 行连接续：JSON5 允许反斜杠+换行，输出里直接省略
                                '\r' -> {
                                    if (i + 2 < text.length && text[i + 2] == '\n') i++
                                }

                                'x' -> {
                                    val hex = text.substring(i + 2, minOf(text.length, i + 4))
                                    val code = hex.toIntOrNull(16) ?: 0xFF
                                    out.append("\\u").append(code.toString(16).padStart(4, '0'))
                                    i += 2
                                }

                                else -> out.append(esc)
                            }
                            i += 2
                        } else if (ch == '\'') {
                            out.append('"')
                            i++
                            break
                        } else if (ch == '"') {
                            out.append("\\\"")
                            i++
                        } else {
                            out.append(ch)
                            i++
                        }
                    }
                }

                c == '{' || c == '[' -> {
                    out.append(c)
                    brackets.addLast(c)
                    i++
                    if (c == '{') tryConsumeBareKey()
                }

                c == '}' || c == ']' -> {
                    out.append(c)
                    if (brackets.isNotEmpty()) brackets.removeLast()
                    i++
                }

                c == ',' -> {
                    val next = nextSignificant(i + 1)
                    if (next == '}' || next == ']') {
                        i++ // 尾随逗号：直接删除
                    } else {
                        out.append(',')
                        i++
                        if (brackets.lastOrNull() == '{') tryConsumeBareKey()
                    }
                }

                c == ':' -> {
                    out.append(c)
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
