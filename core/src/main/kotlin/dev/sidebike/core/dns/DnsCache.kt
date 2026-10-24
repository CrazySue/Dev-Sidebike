package dev.sidebike.core.dns

/**
 * TTL 感知的 DNS 结果 LRU 缓存（容量来自 `DnsSettings.cacheSize`）。
 *
 * 与上游 Dev-Sidecar 的差异（有意为之）：
 * - 上游 `dns/base.js:82-87` 的 IP 缓存**没有 TTL**（LRU 淘汰 + 失败切换）；
 * - Android 版按 DNS 应答 TTL 过期（`minTtlSec`/`maxTtlSec` 夹取），更符合 DNS 语义，
 *   并避免长期使用过期/被污染的解析结果。失败切换仍由 resolver 的 provider 失败计数承担。
 *
 * 线程安全：所有方法 synchronized（缓存操作极短，不在锁内做网络 IO）。
 */
class DnsCache(private val maxEntries: Int = 2048) {

    data class Entry(
        val ips: List<IpAnswer>,
        val provider: String,
        val ttlSec: Int,
        val expiresAtMs: Long,
    ) {
        fun isAlive(nowMs: Long): Boolean = nowMs < expiresAtMs
    }

    private val entries = object : LinkedHashMap<String, Entry>(maxEntries.coerceAtLeast(16), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries.coerceAtLeast(16)
    }

    @Synchronized
    fun get(key: String, nowMs: Long): Entry? {
        val entry = entries[key] ?: return null
        if (!entry.isAlive(nowMs)) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, entry: Entry) {
        entries[key] = entry
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun evictExpired(nowMs: Long) {
        entries.entries.removeIf { !it.value.isAlive(nowMs) }
    }
}
