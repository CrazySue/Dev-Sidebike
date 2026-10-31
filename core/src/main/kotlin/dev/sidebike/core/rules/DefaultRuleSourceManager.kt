package dev.sidebike.core.rules

import dev.sidebike.core.CoreVersion
import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.RuleSourceManager
import dev.sidebike.core.api.RuleSourceManagerFactory
import dev.sidebike.core.api.RuleSourceRefreshResult
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger
import dev.sidebike.core.util.Clock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default remote rule-source manager (pure Kotlin/JVM, Android-compatible: `HttpURLConnection` only).
 *
 * Responsibilities (frozen contract: `api/RuleSources.kt`):
 *  - fetch http(s) rule sources with connect=10s / read=20s timeouts, 5 MiB body cap, redirects, and a
 *    `Dev-Sidebike/<version>` user agent;
 *  - conditional requests: `If-None-Match` (ETag) / `If-Modified-Since` (Last-Modified) unless `force`,
 *    mapping HTTP 304 to [RuleSourceRefreshResult.notModified] (the caller keeps the previously imported
 *    rules on 304 and on any failure - this class never mutates persisted config);
 *  - parse the three supported formats (see [RuleSourceParsers]) and return [ImportedRuleSet];
 *  - never throw: network/parse failures come back as [RuleSourceRefreshResult.error] + source.lastError.
 *
 * The merged result (intercepts / whiteList / domesticAllowList / preSetIpList) is applied by the App
 * Controller using [RuleImportMerge] (kept idempotent: importing the same source twice yields the same config).
 *
 * `install()` registers the factory (same pattern as `SidebikeEngineFactory`); the app bootstrap calls
 * `DefaultRuleSourceManager.install()` before `RuleSourceManagerFactory.create()`.
 *
 * Runtime fetch only: the rule files of `Johnshall/Shadowrocket-ADBlock-Rules-Forever` are CC BY-SA 4.0 and
 * must NOT be bundled into the APK (About page attribution lives in the app).
 */
class DefaultRuleSourceManager(
    private val clock: Clock = Clock.SYSTEM,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : RuleSourceManager {

    override suspend fun refresh(source: RuleSource, force: Boolean): RuleSourceRefreshResult {
        if (!source.enabled) {
            logger.debug(TAG, "skip disabled rule source: ${source.name} (${source.url})")
            return RuleSourceRefreshResult(source = source, imported = null, notModified = false, error = null)
        }
        if (source.url.isBlank()) {
            return failure(source, "规则源 URL 为空")
        }
        return withContext(Dispatchers.IO) {
            try {
                fetchAndParse(source, force)
            } catch (e: Exception) {
                // Contract: never throw. Network stacks can throw unchecked exceptions too.
                logger.warn(TAG, "refresh failed: ${source.name}: ${e.message}", e)
                failure(source, "刷新失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun refreshAll(sources: List<RuleSource>, force: Boolean): List<RuleSourceRefreshResult> {
        // Sequential on purpose: deterministic order, bounded resource use on mobile, and the UI shows
        // per-source progress. Each source is independent and failures are isolated.
        val results = ArrayList<RuleSourceRefreshResult>(sources.size)
        for (source in sources) {
            results.add(refresh(source, force))
        }
        return results
    }

    override fun parse(content: String, format: RuleSourceFormat, name: String, url: String): ImportedRuleSet {
        return try {
            RuleSourceParsers.parse(content, format, name, url)
        } catch (e: Exception) {
            ImportedRuleSet(warnings = listOf("解析失败: ${e.message}"))
        }
    }

    // ------------------------------------------------------------------------------------------------
    // fetching
    // ------------------------------------------------------------------------------------------------

    private fun fetchAndParse(source: RuleSource, force: Boolean): RuleSourceRefreshResult {
        val url = try {
            URL(source.url.trim())
        } catch (e: Exception) {
            return failure(source, "无效的规则源 URL: ${source.url}")
        }
        if (url.protocol != "http" && url.protocol != "https") {
            return failure(source, "仅支持 http(s) 规则源: ${url.protocol}")
        }

        val response = openFollowingRedirects(url, source, force)
            ?: return failure(source, "无法连接规则源（重定向次数过多）")

        try {
            val now = clock.currentTimeMillis()
            if (response.code == HTTP_NOT_MODIFIED) {
                logger.info(TAG, "rule source not modified (304): ${source.name}")
                return RuleSourceRefreshResult(
                    source = source.copy(lastError = null, lastUpdatedMs = now),
                    imported = null,
                    notModified = true,
                    error = null,
                )
            }
            if (response.code !in 200..299) {
                val detail = errorSnippet(response.connection)
                val message = "HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}"
                logger.warn(TAG, "rule source fetch failed: ${source.name}: $message")
                return failure(source, message)
            }

            val declaredLength = response.connection.contentLengthLong
            if (declaredLength > maxBodyBytes) {
                return failure(source, "规则文件过大（${declaredLength / 1024}KB，上限 ${maxBodyBytes / 1024}KB）")
            }

            val bodyBytes = try {
                response.connection.inputStream.use { readLimited(it, maxBodyBytes) }
            } catch (e: BodyTooLargeException) {
                return failure(source, "规则文件超过 ${maxBodyBytes / 1024 / 1024}MB 上限")
            } catch (e: IOException) {
                return failure(source, "读取响应失败: ${e.message}")
            }
            val content = decodeBody(bodyBytes, response.connection.contentType)
            if (content.isBlank()) {
                return failure(source, "规则源内容为空")
            }

            val format = if (source.format == RuleSourceFormat.AUTO) {
                RuleSourceParsers.detectFormat(source.url, content)
            } else {
                source.format
            }
            val imported = parse(content, format, source.name, source.url)
            val importedCount = imported.ruleCount
            logger.info(
                TAG,
                "rule source refreshed: ${source.name}, format=$format, imported=$importedCount, " +
                    "warnings=${imported.warnings.size}",
            )
            val etag = response.connection.getHeaderField("ETag")
            val lastModified = response.connection.getHeaderField("Last-Modified")
            return RuleSourceRefreshResult(
                source = source.copy(
                    lastUpdatedMs = now,
                    lastError = null,
                    etag = etag ?: source.etag,
                    lastModified = lastModified ?: source.lastModified,
                    lastImported = importedCount,
                ),
                imported = imported,
                notModified = false,
                error = null,
            )
        } finally {
            try {
                response.connection.disconnect()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private class FetchedResponse(val connection: HttpURLConnection, val code: Int)

    private fun errorSnippet(connection: HttpURLConnection): String {
        val stream = try {
            connection.errorStream
        } catch (_: Throwable) {
            null
        } ?: return ""
        return try {
            stream.use { input -> readLimited(input, 4 * 1024).toString(Charsets.UTF_8).trim().take(200) }
        } catch (_: Throwable) {
            ""
        }
    }

    /** Opens [start] following same- or cross-protocol redirects manually (max [MAX_REDIRECTS]). */
    private fun openFollowingRedirects(start: URL, source: RuleSource, force: Boolean): FetchedResponse? {
        var current = start
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false // handled manually to allow http<->https upgrades
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                if (!force) {
                    source.etag?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-None-Match", it) }
                    source.lastModified?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Modified-Since", it) }
                }
            }
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                try {
                    connection.disconnect()
                } catch (_: Throwable) {
                    // ignore
                }
                throw IOException("连接规则源失败: ${e.message}", e)
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                try {
                    connection.disconnect()
                } catch (_: Throwable) {
                    // ignore
                }
                if (location.isNullOrBlank()) return FetchedResponse(connection, code)
                current = try {
                    URL(current, location)
                } catch (_: Exception) {
                    return FetchedResponse(connection, code)
                }
                hops++
                continue
            }
            return FetchedResponse(connection, code)
        }
        return null
    }

    private fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val charset = try {
            val match = contentType?.let { Regex("charset\\s*=\\s*\"?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE).find(it) }
            match?.let { java.nio.charset.Charset.forName(it.groupValues[1]) } ?: Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        return bytes.toString(charset).trimStart('\uFEFF')
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) throw BodyTooLargeException(limit)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun failure(source: RuleSource, message: String): RuleSourceRefreshResult {
        logger.warn(TAG, "rule source error: ${source.name}: $message")
        return RuleSourceRefreshResult(
            source = source.copy(lastError = message),
            imported = null,
            notModified = false,
            error = message,
        )
    }

    private class BodyTooLargeException(limit: Int) : IOException("body exceeds $limit bytes")

    companion object {
        private const val TAG = "RuleSource"
        private const val HTTP_NOT_MODIFIED = 304
        private const val MAX_REDIRECTS = 5
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 10_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 20_000
        const val DEFAULT_MAX_BODY_BYTES: Int = 5 * 1024 * 1024
        val DEFAULT_USER_AGENT: String = "Dev-Sidebike/${CoreVersion.VERSION}"

        init {
            registerFactory()
        }

        /**
         * Registers the default factory in [RuleSourceManagerFactory] (idempotent; app bootstrap calls this
         * once before the first `RuleSourceManagerFactory.create()`).
         *
         * `@JvmStatic` so Android bootstrap code can also reach it through plain Java reflection
         * (`Class.getMethod("install")`) as well as Kotlin `DefaultRuleSourceManager.install()`.
         */
        @JvmStatic
        fun install() {
            registerFactory()
        }

        /** Allows the app/tests to install a custom manager factory (e.g. with a specific logger). */
        @JvmStatic
        fun install(factory: () -> RuleSourceManager) {
            RuleSourceManagerFactory.install(factory)
        }

        private fun registerFactory() {
            RuleSourceManagerFactory.install { DefaultRuleSourceManager() }
        }
    }
}
