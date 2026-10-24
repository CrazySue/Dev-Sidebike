package dev.sidebike.core.mitm

import dev.sidebike.core.util.Clock
import dev.sidebike.core.util.HttpHeaders
import dev.sidebike.core.util.LruCache

/**
 * In-memory response cache backing the `cacheDays` interceptor.
 *
 * Upstream relies on the browser HTTP cache (it only rewrites `Cache-Control`/`Last-Modified`/`Expires`
 * and answers conditional requests with `304`, `cacheResponse.js:115-122`). On Android we additionally keep
 * the body in memory so repeated GETs can be served without touching the network - this is the explicit
 * deliverable "cacheDays = memory cache for GET responses" of task T5.
 */
class ResponseCache(private val clock: Clock, maxEntries: Int = 512) {

    data class Entry(
        val key: String,
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val storedAtMs: Long,
        val maxAgeSec: Long,
    ) {
        fun isFresh(nowMs: Long): Boolean = maxAgeSec > 0 && nowMs - storedAtMs <= maxAgeSec * 1000L
    }

    private val cache = LruCache<String, Entry>(if (maxEntries > 0) maxEntries else 512)

    fun get(key: String): Entry? {
        val entry = cache.get(key) ?: return null
        if (!entry.isFresh(clock.currentTimeMillis())) {
            cache.remove(key)
            return null
        }
        return entry
    }

    fun put(key: String, status: Int, headers: HttpHeaders, body: ByteArray, maxAgeSec: Long): Entry {
        val entry = Entry(
            key = key,
            status = status,
            headers = headers.entries(),
            body = body,
            storedAtMs = clock.currentTimeMillis(),
            maxAgeSec = maxAgeSec,
        )
        cache.put(key, entry)
        return entry
    }

    fun size(): Int = cache.size()

    fun clear() = cache.clear()

    companion object {
        fun key(method: String, scheme: String, host: String, port: Int, pathAndQuery: String): String =
            "${method.uppercase()} $scheme://$host:$port$pathAndQuery"
    }
}
