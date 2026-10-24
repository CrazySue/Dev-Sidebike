package dev.sidebike.core.config

import dev.sidebike.core.api.SidebikeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 配置 JSON 编解码与分层合并。
 *
 * 合并语义对齐上游 `core/src/merge.js` + `local-config-loader.js:234-259`：
 * - 对象：深合并；
 * - 数组：整体替换；
 * - `null` / `"[delete]"`：删除该键（上游 `deleteNullItems`；`"[delete]"` 只作为字符串值语义，
 *   对普通字符串配置不做特殊处理，避免误删合法值——本实现仅处理 JSON null）。
 *
 * 反序列化参数按契约要求：`ignoreUnknownKeys=true`、`isLenient=true`（`docs/architecture/02-core-contracts.md:26`）。
 */
object ConfigJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val prettyJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    // ---- 读取 ----

    /** 解析 JSON5/JSON 文本为 JsonObject，失败抛异常（由调用方决定回退策略）。 */
    fun parseObject(text: String): JsonObject = Json5.parse(text).jsonObject

    /** 文本 → SidebikeConfig（先 JSON5 预处理；缺失字段使用 api 默认值）。 */
    fun decode(text: String): SidebikeConfig = decodeElement(Json5.parse(text))

    fun decodeElement(element: JsonElement): SidebikeConfig =
        json.decodeFromJsonElement(SidebikeConfig.serializer(), element)

    // ---- 写出 ----

    fun encode(config: SidebikeConfig): String =
        json.encodeToString(SidebikeConfig.serializer(), config)

    fun encodePretty(config: SidebikeConfig): String =
        prettyJson.encodeToString(SidebikeConfig.serializer(), config)

    fun toJsonObject(config: SidebikeConfig): JsonObject =
        json.encodeToJsonElement(SidebikeConfig.serializer(), config).jsonObject

    // ---- 合并 ----

    fun merge(base: JsonElement, override: JsonElement): JsonElement =
        if (base is JsonObject && override is JsonObject) mergeObjects(base, override) else override

    fun mergeObjects(base: JsonObject, override: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in override) {
            if (value is JsonNull) {
                result.remove(key)
                continue
            }
            val old = result[key]
            result[key] = if (old is JsonObject && value is JsonObject) mergeObjects(old, value) else value
        }
        return JsonObject(result)
    }

    /** 把部分配置合并到完整配置上（返回合并后的对象）。 */
    fun mergeConfig(base: SidebikeConfig, partial: JsonObject): SidebikeConfig =
        decodeElement(mergeObjects(toJsonObject(base), partial))

    // ---- 版本迁移 ----

    /**
     * 配置版本迁移。
     *
     * v1 为首个正式版本；历史版本（version 缺失/0，例如从上游导入或早期 beta）只做版本号升级，
     * 字段缺失由反序列化默认值兜底。若版本高于当前（用户降级安装），保留配置并交由调用方告警，不销毁用户数据。
     */
    fun migrate(config: SidebikeConfig): SidebikeConfig =
        if (config.version < SidebikeConfig.CURRENT_VERSION) {
            config.copy(version = SidebikeConfig.CURRENT_VERSION)
        } else {
            config
        }
}
