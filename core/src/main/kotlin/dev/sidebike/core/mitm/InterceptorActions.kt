package dev.sidebike.core.mitm

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.HttpHeaders
import dev.sidebike.core.util.MalformedHttpException
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Interpreter for the frozen [InterceptAction] contract.
 *
 * Mirrors the upstream interceptor implementations
 * (`reference/dev-sidecar/vendor/ds-core/mitmproxy/src/lib/interceptor/impl`):
 *  - `success` -> synthetic response (success.js)
 *  - `requestReplace.headers` -> mutate request headers, `"[remove]"` deletes (requestReplace.js:6-23)
 *  - `responseReplace.headers` -> mutate response headers, `"[remove]"` deletes (responseReplace.js:8-66)
 *  - `script` -> inject `<script>` into HTML responses (script.js + InsertScriptMiddleware.js:49-87)
 *  - `cacheDays` -> response caching (cacheRequest.js / cacheResponse.js)
 *  - `status` -> response status override (frozen config carries it at the action level, e.g.
 *    `collector.github.com` `{ success: true, status: 204 }` in core/src/config/index.js:762-768)
 *
 * Everything here is pure: no I/O, no clock. That keeps the semantics unit-testable in isolation.
 */
object InterceptorActions {

    const val REMOVE = "[remove]"

    data class SyntheticResponse(val status: Int, val headers: HttpHeaders, val body: ByteArray)

    data class ScriptSpec(val src: String?, val inline: String?) {
        val isInline: Boolean get() = inline != null
    }

    // ------------------------------------------------------------------------------------------------
    // success / abort action
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a synthetic response for `success` (and the legacy `abort` shape) actions.
     * Returns null when the action is absent or explicitly disabled (`success: false`).
     *
     * Parity: success.js:6-89 - `success: true` returns a 200 plain-text hint; object form supports
     * `status`, `headers` and one of `html/json/script/css/text/body`, deriving `Content-Type` from the
     * chosen body field and appending `charset=utf-8`; `Access-Control-Allow-*` are reflected from the
     * request `Origin` to keep cross-origin XHR working.
     */
    fun buildSynthetic(action: InterceptAction?, requestHeaders: HttpHeaders): SyntheticResponse? {
        if (action == null) return null
        val success = action.success ?: return null
        if (success is JsonPrimitive && !success.isString && success.booleanOrNull == false) return null
        if (success is JsonNull) return null

        val trueForm = success is JsonPrimitive && (success.booleanOrNull == true ||
            success.contentOrNull?.equals("true", ignoreCase = true) == true)
        val origin = requestHeaders.get("Origin")

        if (trueForm) {
            val status = action.status ?: 200
            val headers = HttpHeaders()
            headers.add("Content-Type", HttpCodec.contentTypeText())
            headers.add("DS-Interceptor", "success")
            if (origin != null) applyCors(headers, origin)
            val body = "DevSidecar $status: Request success.\n\n" +
                "  This request is matched by success intercept.\n\n" +
                "  因配置success拦截器，本请求直接返回${status}成功。"
            return SyntheticResponse(status, headers, body.toByteArray(StandardCharsets.UTF_8))
        }

        val response = success as? JsonObject ?: return null
        val status = response.intField("status") ?: action.status ?: 200
        val bodyField = firstBodyField(response)
        val headers = HttpHeaders()
        // Success-object headers are applied verbatim (header names preserved for readability).
        val configuredHeaders = response["headers"] as? JsonObject
        configuredHeaders?.let { h ->
            for ((name, value) in h) {
                val str = jsonScalar(value) ?: continue
                if (str == REMOVE) continue
                headers.add(name, str)
            }
        }
        headers.add("DS-Interceptor", "success")
        if (status != 204) {
            if (!headers.contains("Content-Type")) {
                headers.add("Content-Type", contentTypeForBodyField(bodyField?.first))
            }
            val ct = headers.get("Content-Type")
            if (ct != null && !ct.contains("charset", ignoreCase = true)) {
                headers.set("Content-Type", "$ct; charset=utf-8")
            }
        }
        if (origin != null && !headers.contains("Access-Control-Allow-Origin")) applyCors(headers, origin)
        val body = bodyField?.second ?: "DevSidecar $status: Request success.\n\n" +
            "  This request is matched by success intercept.\n\n" +
            "  因配置success拦截器，本请求直接返回${status}成功。"
        val bytes = if (status == 204) ByteArray(0) else body.toByteArray(StandardCharsets.UTF_8)
        return SyntheticResponse(status, headers, bytes)
    }

    fun isSynthetic(action: InterceptAction?): Boolean = buildSynthetic(action, HttpHeaders()) != null

    private fun applyCors(headers: HttpHeaders, origin: String) {
        headers.add("Access-Control-Allow-Origin", origin)
        headers.add("Access-Control-Allow-Credentials", "true")
        headers.add("Vary", "Origin")
    }

    private fun contentTypeForBodyField(field: String?): String = when (field) {
        "html" -> "text/html"
        "json" -> "application/json"
        "script" -> "application/javascript"
        "css" -> "text/css"
        "xml" -> "application/xml"
        else -> "text/plain"
    }

    /** Returns (fieldName, text) for the first recognised body field, matching success.js:42. */
    private fun firstBodyField(response: JsonObject): Pair<String, String>? {
        for (name in listOf("html", "json", "script", "css", "text", "body")) {
            val value = response[name] ?: continue
            val text = jsonScalar(value) ?: continue
            return name to text
        }
        return null
    }

    private fun jsonScalar(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonNull -> null
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }

    private fun JsonObject.intField(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    // ------------------------------------------------------------------------------------------------
    // requestReplace
    // ------------------------------------------------------------------------------------------------

    /** Applies `requestReplace.headers`; returns human-readable action descriptions for the DS- header. */
    fun applyRequestReplace(headers: HttpHeaders, action: InterceptAction?): List<String> {
        val cfg = action?.requestReplace as? JsonObject ?: return emptyList()
        val map = cfg["headers"] as? JsonObject ?: return emptyList()
        val applied = ArrayList<String>(map.size)
        for ((name, element) in map) {
            if (name.startsWith("://") || name.startsWith(":")) continue
            val value = jsonScalar(element) ?: continue
            if (value == REMOVE) {
                headers.remove(name)
                applied.add("$name=[remove]")
            } else {
                headers.set(name, value)
                applied.add("$name=$value")
            }
        }
        return applied
    }

    /** `requestReplace.doDownload`: strip the `?DS_DOWNLOAD` marker (requestReplace.js:41-47). */
    fun stripDownloadMarker(pathAndQuery: String): Pair<String, Boolean> {
        if (!pathAndQuery.contains("DS_DOWNLOAD", ignoreCase = true)) return pathAndQuery to false
        val stripped = pathAndQuery
            .replace(Regex("[?&/]?DS_DOWNLOAD(=[^?&/]+)?$", RegexOption.IGNORE_CASE), "")
        return (if (stripped.isEmpty()) "/" else stripped) to true
    }

    fun wantsDownload(action: InterceptAction?): Boolean {
        val req = action?.requestReplace as? JsonObject ?: return false
        val doDownload = req["doDownload"] as? JsonPrimitive
        return doDownload?.booleanOrNull == true
    }

    // ------------------------------------------------------------------------------------------------
    // responseReplace
    // ------------------------------------------------------------------------------------------------

    /** `responseReplace.headers`: header name (case-insensitive) -> value or [REMOVE]. */
    fun responseReplaceHeaders(action: InterceptAction?): Map<String, String> {
        val cfg = action?.responseReplace as? JsonObject ?: return emptyMap()
        val map = cfg["headers"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>(map.size)
        for ((name, element) in map) {
            val value = jsonScalar(element) ?: continue
            out[name] = value
        }
        return out
    }

    /** `responseReplace.{html,json,script,css,text,body}` -> full replacement body (extension over upstream). */
    fun responseReplaceBody(action: InterceptAction?): ByteArray? {
        val cfg = action?.responseReplace as? JsonObject ?: return null
        val field = firstBodyField(cfg) ?: return null
        return field.second.toByteArray(StandardCharsets.UTF_8)
    }

    fun responseReplaceContentType(action: InterceptAction?): String? {
        val cfg = action?.responseReplace as? JsonObject ?: return null
        val field = firstBodyField(cfg)?.first ?: return null
        return contentTypeForBodyField(field)
    }

    // ------------------------------------------------------------------------------------------------
    // script
    // ------------------------------------------------------------------------------------------------

    /**
     * Interprets the frozen `script` string. Upstream `script` values are either built-in monkey-script
     * keys (desktop-only, not portable) or URLs/paths injected as `<script src=...>` tags
     * (script.js:64-78). We support:
     *  - `https://...` / `http://...` / `/...` -> external `<script src>`;
     *  - anything else -> inline `<script>...</script>` (raw JavaScript), which is the portable subset of
     *    the upstream "built-in script" feature and matches the beta scope (no monkey script engine).
     */
    fun scriptSpec(action: InterceptAction?): ScriptSpec? {
        if (action == null) return null
        val raw = action.script?.trim() ?: return null
        if (raw.isEmpty()) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("/")) {
            ScriptSpec(src = raw, inline = null)
        } else {
            ScriptSpec(src = null, inline = raw)
        }
    }

    /** Renders the `<script>` tag(s) injected into an HTML document (InsertScriptMiddleware.js:9-15). */
    fun renderScriptTags(spec: ScriptSpec, nonce: String? = null): String {
        val nonceAttr = if (nonce.isNullOrEmpty()) "" else " nonce=\"$nonce\""
        return if (spec.src != null) {
            "<script crossorigin=\"anonymous\" defer=\"defer\" type=\"application/javascript\" src=\"${spec.src}\"$nonceAttr></script>"
        } else {
            "<script type=\"application/javascript\"$nonceAttr>${spec.inline}</script>"
        }
    }

    // ------------------------------------------------------------------------------------------------
    // cacheDays / status
    // ------------------------------------------------------------------------------------------------

    /** `cacheDays` -> cache max-age in seconds (0 when not configured). */
    fun cacheMaxAgeSeconds(action: InterceptAction?): Long {
        val days = action?.cacheDays ?: return 0
        if (days <= 0) return 0
        return days.toLong() * 86400L
    }

    /** Action-level `status` override applied to the final response, when present. */
    fun statusOverride(action: InterceptAction?, current: Int): Int {
        val override = action?.status ?: return current
        return if (override in 100..999) override else current
    }
}
