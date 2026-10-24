package dev.sidebike.core.proxy

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.RuleEngine
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.mitm.BodyTransforms
import dev.sidebike.core.mitm.ContentCodec
import dev.sidebike.core.mitm.InterceptorActions
import dev.sidebike.core.mitm.ResponseCache
import dev.sidebike.core.mitm.ScriptInjector
import dev.sidebike.core.rules.ProxyTargetParser
import dev.sidebike.core.util.Clock
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.HttpHeaders
import dev.sidebike.core.util.HttpRequestHead
import dev.sidebike.core.util.HttpResponseHead
import dev.sidebike.core.util.IoSupport
import dev.sidebike.core.util.MalformedHttpException
import dev.sidebike.core.util.NetUtils
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The HTTP forwarding engine shared by the plain-HTTP proxy path and the MITM path.
 *
 * Responsibilities / ported upstream behaviour:
 *  - request header replacement (`requestReplace`, requestReplace.js),
 *  - `cacheDays`: memory cache lookup + `Cache-Control/Last-Modified/Expires` rewriting
 *    (cacheRequest.js / cacheResponse.js) and conditional `304` answers,
 *  - synthetic responses (`success`, success.js) and rule rejection (`abort`, abort.js -> 403),
 *  - upstream request emission with hop-by-hop header stripping (createRequestHandler.js:709-737),
 *  - response header replacement (`responseReplace`, responseReplace.js) and body replacement,
 *  - HTML script injection (script.js + InsertScriptMiddleware.js),
 *  - `status` override,
 *  - streaming pass-through for bodies that must not/cannot be buffered (downloads and > 8 MiB bodies).
 *
 * The forwarder never closes the client connection: it always emits `Connection: close` and lets the
 * connection owner (proxy/MITM) close both sides. HTTP/1.1 request pipelining/keep-alive is intentionally
 * simplified per the task ("may be simplified (Connection: close) but must be correct").
 */
class HttpForwarder(
    private val configProvider: () -> SidebikeConfig,
    private val ruleEngine: RuleEngine,
    private val logger: SidebikeLogger,
    private val clock: Clock,
    private val connector: UpstreamConnector,
    private val cache: ResponseCache,
) {

    data class ForwardOutcome(
        val error: String? = null,
        val upstreamStatus: Int? = null,
        val fromCache: Boolean = false,
        val synthetic: Boolean = false,
        val rejected: Boolean = false,
    )

    @Suppress("UNUSED_PARAMETER")
    fun forward(
        request: HttpRequestHead,
        scheme: String,
        targetHost: String,
        targetPort: Int,
        plan: RoutePlan,
        clientIn: InputStream,
        clientOut: OutputStream,
    ): ForwardOutcome {
        val action = plan.action
        val method = request.method
        var path = NetUtils.normalizePathAndQuery(request.target)

        if (plan.kind == RouteKind.REJECT) {
            val reason = plan.reason.ifBlank { "请求被拦截规则阻止 (abort)" }
            HttpResponses.writeError(clientOut, request, 403, reason, "$scheme://$targetHost:$targetPort$path")
            return ForwardOutcome(error = "rejected by rule", upstreamStatus = 403, rejected = true)
        }

        val synthetic = InterceptorActions.buildSynthetic(action, request.headers)
        if (synthetic != null) {
            HttpResponses.writeSynthetic(clientOut, request, synthetic)
            return ForwardOutcome(upstreamStatus = synthetic.status, synthetic = true)
        }

        val fwdHeaders = request.headers.copy()
        val notes = ArrayList<String>(4)
        // Mirror/proxy action: the connect target already comes from RoutePlan, but the upstream path must be
        // rewritten with the configured mirror path prefix (upstream proxy.js:buildTargetUrl, handled in the
        // rules layer by ProxyTargetParser so the proxy and RuleEngine agree on one implementation).
        if (plan.kind == RouteKind.MIRROR_PROXY) {
            val mirror = ProxyTargetParser.parse(action?.proxy, targetPort)
            if (mirror != null) {
                path = mirror.buildPath(path)
                notes.add("proxy:${mirror.host}")
            }
        }
        if (InterceptorActions.applyRequestReplace(fwdHeaders, action).isNotEmpty()) notes.add("requestReplace")
        if (InterceptorActions.wantsDownload(action)) {
            val (stripped, hit) = InterceptorActions.stripDownloadMarker(path)
            if (hit) {
                path = stripped
                notes.add("download")
            }
        }

        val maxAgeSec = InterceptorActions.cacheMaxAgeSeconds(action)
        val cacheKey = ResponseCache.key(method, scheme, targetHost, targetPort, path)
        if (maxAgeSec > 0 && method == "GET" && !isCacheBypassed(request.headers)) {
            val entry = cache.get(cacheKey)
            if (entry != null) {
                HttpResponses.writeCached(clientOut, request, entry, clock.currentTimeMillis(), logger)
                return ForwardOutcome(upstreamStatus = entry.status, fromCache = true)
            }
        }

        val tls = scheme == "https"
        val upstream = try {
            connector.connect(plan, tls, if (tls) plan.sni else null)
        } catch (e: SocketTimeoutException) {
            val msg = "连接上游超时: ${plan.connectHost}:${plan.connectPort} (${e.message})"
            logger.warn(TAG, msg)
            HttpResponses.writeError(clientOut, request, 504, msg, "$scheme://$targetHost:$targetPort$path")
            return ForwardOutcome(error = msg, upstreamStatus = 504)
        } catch (e: IOException) {
            val msg = "连接上游失败: ${plan.connectHost}:${plan.connectPort} (${e.javaClass.simpleName}: ${e.message})"
            logger.warn(TAG, msg)
            HttpResponses.writeError(clientOut, request, 502, msg, "$scheme://$targetHost:$targetPort$path")
            return ForwardOutcome(error = msg, upstreamStatus = 502)
        }

        upstream.use { up ->
            try {
                sendRequest(request, method, path, targetHost, targetPort, fwdHeaders, notes, upstream, clientIn, clientOut)
            } catch (e: SocketTimeoutException) {
                val msg = "向上游发送请求超时: $targetHost:$targetPort$path"
                logger.warn(TAG, msg)
                HttpResponses.writeError(clientOut, request, 504, msg, "$scheme://$targetHost:$targetPort$path")
                return ForwardOutcome(error = msg, upstreamStatus = 504)
            } catch (e: IOException) {
                val msg = "向上游发送请求失败: $targetHost:$targetPort$path (${e.message})"
                logger.warn(TAG, msg)
                HttpResponses.writeError(clientOut, request, 502, msg, "$scheme://$targetHost:$targetPort$path")
                return ForwardOutcome(error = msg, upstreamStatus = 502)
            }

            val response: HttpResponseHead = try {
                HttpCodec.readResponseHead(upstream.input)
            } catch (e: SocketTimeoutException) {
                val msg = "等待上游响应超时: $targetHost:$targetPort$path"
                logger.warn(TAG, msg)
                HttpResponses.writeError(clientOut, request, 504, msg, "$scheme://$targetHost:$targetPort$path")
                return ForwardOutcome(error = msg, upstreamStatus = 504)
            } catch (e: IOException) {
                val msg = "读取上游响应失败: $targetHost:$targetPort$path (${e.message})"
                logger.warn(TAG, msg)
                HttpResponses.writeError(clientOut, request, 502, msg, "$scheme://$targetHost:$targetPort$path")
                return ForwardOutcome(error = msg, upstreamStatus = 502)
            }

            return relayResponse(
                request = request,
                method = method,
                scheme = scheme,
                targetHost = targetHost,
                targetPort = targetPort,
                path = path,
                plan = plan,
                action = action,
                notes = notes,
                response = response,
                upstream = upstream,
                clientOut = clientOut,
                cacheKey = cacheKey,
                maxAgeSec = maxAgeSec,
            )
        }
    }

    // ------------------------------------------------------------------------------------------------
    // request emission
    // ------------------------------------------------------------------------------------------------

    private fun sendRequest(
        request: HttpRequestHead,
        method: String,
        path: String,
        targetHost: String,
        targetPort: Int,
        fwdHeaders: HttpHeaders,
        notes: List<String>,
        upstream: UpstreamConnection,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        val config = configProvider()
        val chunkedRequest = HttpCodec.isChunked(request.headers)
        val requestLength = HttpCodec.contentLength(request.headers)
        val expectContinue = request.headers.get("Expect")?.contains("100-continue", ignoreCase = true) == true

        val headers = fwdHeaders.copy()
        val bodyStream = HttpCodec.requestBodyStream(request, clientIn)
        HttpCodec.stripHopByHop(headers)
        headers.remove("Expect")
        if (!headers.contains("Host")) {
            headers.set("Host", if (targetPort == 80 || targetPort == 443) targetHost else "$targetHost:$targetPort")
        }
        if (chunkedRequest) {
            headers.set("Transfer-Encoding", "chunked")
            headers.remove("Content-Length")
        } else if (requestLength != null && requestLength > 0) {
            headers.set("Content-Length", requestLength.toString())
        }
        headers.set("Connection", "close")
        if (notes.isNotEmpty()) headers.set("DS-Interceptor", notes.joinToString(", "))
        if (config.proxy.mitmEnabled || config.mitm.enabled) {
            headers.set("DS-Proxy-Request", "http://$targetHost:$targetPort$path")
        }

        val out = upstream.output
        HttpCodec.writeRequestHead(out, method, path, headers)
        if (expectContinue) {
            // We consume the body ourselves: acknowledge the expectation locally instead of relaying the
            // upstream 100-continue interim response.
            clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
        }
        if (chunkedRequest) {
            HttpCodec.writeChunkedBody(bodyStream, out)
        } else if (requestLength != null && requestLength > 0) {
            IoSupport.copy(bodyStream, out)
        }
        out.flush()
    }

    // ------------------------------------------------------------------------------------------------
    // response relay / interception
    // ------------------------------------------------------------------------------------------------

    private fun relayResponse(
        request: HttpRequestHead,
        method: String,
        scheme: String,
        targetHost: String,
        targetPort: Int,
        path: String,
        plan: RoutePlan,
        action: InterceptAction?,
        notes: MutableList<String>,
        response: HttpResponseHead,
        upstream: UpstreamConnection,
        clientOut: OutputStream,
        cacheKey: String,
        maxAgeSec: Long,
    ): ForwardOutcome {
        val upstreamStatus = response.status
        val headers = response.headers.copy()
        val upstreamContentType = headers.get("Content-Type")
        val upstreamEncoding = headers.get("Content-Encoding")
        val bodyStream = HttpCodec.responseBodyStream(method, response, upstream.input)
        var status = InterceptorActions.statusOverride(action, upstreamStatus)

        // upstream responseReplace only applies to 200 responses (responseReplace.js:75-77).
        val replaceHeaders = InterceptorActions.responseReplaceHeaders(action)
        if (replaceHeaders.isNotEmpty() && upstreamStatus == 200) {
            for ((name, value) in replaceHeaders) {
                if (value == InterceptorActions.REMOVE) headers.remove(name) else headers.set(name, value)
            }
            notes.add("responseReplace")
        }
        val bodyReplacement = InterceptorActions.responseReplaceBody(action)
        val scriptSpec = InterceptorActions.scriptSpec(action)
        val cacheWanted = maxAgeSec > 0 && method == "GET" && upstreamStatus in 200..303 && bodyStream != null
        val wantsBodyRewrite = bodyReplacement != null || (scriptSpec != null && ScriptInjector.isHtml(upstreamContentType))
        val canBuffer = bodyStream != null &&
            ContentCodec.isSupportedEncoding(upstreamEncoding) &&
            (cacheWanted || wantsBodyRewrite)

        if (notes.isNotEmpty()) headers.set("DS-Interceptor", notes.joinToString(", "))
        if (action != null) {
            headers.set("DS-Proxy-Request", "$scheme://$targetHost:$targetPort$path")
        }

        if (canBuffer && bodyStream != null) {
            val buffered = BodyTransforms.bufferForModification(bodyStream)
            if (buffered is BodyTransforms.BufferResult.Buffered) {
                var bodyBytes = buffered.raw
                if (wantsBodyRewrite) {
                    val rewritten = BodyTransforms.rewriteBody(bodyBytes, upstreamEncoding, upstreamContentType, action)
                    if (rewritten != null) {
                        bodyBytes = rewritten
                        val replacementType = InterceptorActions.responseReplaceContentType(action)
                        if (bodyReplacement != null && replacementType != null) headers.set("Content-Type", replacementType)
                        if (scriptSpec != null && ScriptInjector.isHtml(upstreamContentType)) notes.add("script")
                    }
                }
                status = InterceptorActions.statusOverride(action, status)
                if (cacheWanted) {
                    applyCacheHeaders(headers, maxAgeSec)
                    cache.put(cacheKey, status, headers, bodyBytes, maxAgeSec)
                }
                writeBufferedResponse(clientOut, request, method, status, headers, bodyBytes, notes)
                return ForwardOutcome(upstreamStatus = status)
            } else if (buffered is BodyTransforms.BufferResult.TooLarge) {
                logger.debug(TAG, "body too large to rewrite, streaming through: $scheme://$targetHost:$targetPort$path")
                writeStreamingResponse(clientOut, method, status, headers, bodyStream = null, prefix = buffered.prefix, rest = buffered.rest, notes = notes)
                return ForwardOutcome(upstreamStatus = status)
            }
        }

        writeStreamingResponse(clientOut, method, status, headers, bodyStream = bodyStream, prefix = null, rest = null, notes = notes)
        return ForwardOutcome(upstreamStatus = status)
    }

    private fun writeBufferedResponse(
        out: OutputStream,
        request: HttpRequestHead,
        method: String,
        status: Int,
        headers: HttpHeaders,
        body: ByteArray,
        notes: List<String>,
    ) {
        val outHeaders = clientResponseHeaders(headers)
        outHeaders.set("Content-Length", body.size.toString())
        HttpCodec.writeResponseHead(out, status, "", outHeaders)
        if (!method.equals("HEAD", ignoreCase = true) && status != 204 && status != 304) {
            out.write(body)
        }
        out.flush()
    }

    private fun writeStreamingResponse(
        out: OutputStream,
        method: String,
        status: Int,
        headers: HttpHeaders,
        bodyStream: InputStream?,
        prefix: ByteArray?,
        rest: InputStream?,
        notes: List<String>,
    ) {
        val outHeaders = clientResponseHeaders(headers)
        HttpCodec.writeResponseHead(out, status, "", outHeaders)
        if (!method.equals("HEAD", ignoreCase = true) && status != 204 && status != 304) {
            try {
                if (prefix != null) out.write(prefix)
                val source = rest ?: bodyStream
                if (source != null) {
                    // If the caller gave a fresh stream instead of prefix/rest, drain it through the prefix slot.
                    IoSupport.copy(source, out)
                }
            } catch (e: IOException) {
                // Client went away mid-stream: nothing to repair, the connection owner closes both sides.
                logger.debug(TAG, "client aborted while streaming response: ${e.message}")
            }
        }
        out.flush()
    }

    private fun clientResponseHeaders(headers: HttpHeaders): HttpHeaders {
        val out = headers.copy()
        HttpCodec.stripHopByHop(out)
        out.remove("Trailer")
        out.set("Connection", "close")
        return out
    }

    private fun applyCacheHeaders(headers: HttpHeaders, maxAgeSec: Long) {
        val now = clock.currentTimeMillis()
        headers.set("Cache-Control", "public, max-age=${maxAgeSec + 1}, immutable")
        headers.set("Last-Modified", HttpDates.format(now))
        headers.set("Expires", HttpDates.format(now + maxAgeSec * 1000L))
    }

    private fun isCacheBypassed(requestHeaders: HttpHeaders): Boolean {
        val cacheControl = requestHeaders.get("Cache-Control")?.lowercase() ?: ""
        if (cacheControl.contains("no-cache") || cacheControl.contains("no-store")) return true
        val pragma = requestHeaders.get("Pragma")?.lowercase() ?: ""
        return pragma.contains("no-cache") || pragma.contains("no-store")
    }

    companion object {
        private const val TAG = "proxy.forward"
    }
}

/** Thread-safe RFC 1123 date rendering (HTTP `Date`/`Last-Modified`/`Expires` format). */
object HttpDates {
    private val LOCK = Any()

    fun format(epochMs: Long): String = synchronized(LOCK) {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        fmt.format(Date(epochMs))
    }

    fun parse(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            synchronized(LOCK) {
                val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                fmt.parse(value)?.time
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Locally generated HTTP responses (errors, synthetic `success`, cached entries). Kept in one place so the
 * MITM loop and the plain proxy produce identical wire formats.
 */
object HttpResponses {

    fun writeSynthetic(out: OutputStream, request: HttpRequestHead, synthetic: InterceptorActions.SyntheticResponse) {
        val headers = synthetic.headers.copy()
        headers.set("Connection", "close")
        headers.remove("Transfer-Encoding")
        val noBody = synthetic.status == 204 || synthetic.status == 304
        if (noBody) {
            // 204/304 responses must not carry a body nor a positive Content-Length.
            headers.remove("Content-Length")
        } else {
            headers.set("Content-Length", synthetic.body.size.toString())
        }
        HttpCodec.writeResponseHead(out, synthetic.status, "", headers)
        if (!request.method.equals("HEAD", ignoreCase = true) && !noBody) {
            out.write(synthetic.body)
        }
        out.flush()
    }

    fun writeError(
        out: OutputStream,
        request: HttpRequestHead,
        status: Int,
        message: String,
        target: String,
    ) {
        val accept = request.headers.get("Accept") ?: ""
        val wantsHtml = accept.contains("text/html", ignoreCase = true)
        val body: ByteArray
        val headers = HttpHeaders()
        headers.add("DS-Error", status.toString())
        headers.add("Connection", "close")
        if (wantsHtml) {
            val safeMessage = escapeHtml(message)
            val safeTarget = escapeHtml(target)
            body = (
                "<!doctype html><meta charset=\"utf-8\"><style>" +
                    "body{margin:0;background:#000;color:#fff;font-family:monospace}" +
                    "p{margin:10px 0}</style><p>DevSidecar Error:</p><p>$safeMessage</p><p>$safeTarget</p>"
                ).toByteArray(Charsets.UTF_8)
            headers.add("Content-Type", "text/html; charset=utf-8")
        } else {
            body = "DevSidecar Error:\n$message\n$target\n".toByteArray(Charsets.UTF_8)
            headers.add("Content-Type", "text/plain; charset=utf-8")
        }
        headers.set("Content-Length", body.size.toString())
        HttpCodec.writeResponseHead(out, status, "", headers)
        if (!request.method.equals("HEAD", ignoreCase = true)) out.write(body)
        out.flush()
    }

    /** Serve a `cacheDays` memory-cache entry, including the upstream `304` conditional semantics. */
    fun writeCached(out: OutputStream, request: HttpRequestHead, entry: ResponseCache.Entry, nowMs: Long, logger: SidebikeLogger) {
        val headers = HttpHeaders()
        for ((name, value) in entry.headers) headers.add(name, value)
        headers.remove("Transfer-Encoding")
        headers.set("Connection", "close")
        headers.set("Age", ((nowMs - entry.storedAtMs) / 1000).toString())
        headers.add("DS-Cache", "HIT")

        val ifModifiedSince = HttpDates.parse(request.headers.get("If-Modified-Since"))
        val freshForClient = ifModifiedSince != null && nowMs - ifModifiedSince <= entry.maxAgeSec * 1000L
        if (freshForClient) {
            headers.remove("Content-Length")
            HttpCodec.writeResponseHead(out, 304, "", headers)
            out.flush()
            logger.debug("proxy.forward", "cache 304 hit: ${entry.key}")
            return
        }
        headers.set("Content-Length", entry.body.size.toString())
        HttpCodec.writeResponseHead(out, entry.status, "", headers)
        if (!request.method.equals("HEAD", ignoreCase = true) && entry.status != 204 && entry.status != 304) {
            out.write(entry.body)
        }
        out.flush()
        logger.debug("proxy.forward", "cache hit: ${entry.key} (age=${(nowMs - entry.storedAtMs) / 1000}s)")
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
