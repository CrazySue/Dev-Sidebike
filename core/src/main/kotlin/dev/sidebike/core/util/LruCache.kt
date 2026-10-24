package dev.sidebike.core.util

/**
 * Small thread-safe LRU cache (access-order [LinkedHashMap]).
 *
 * Ported from the upstream behaviour of `lru-cache` used by `CertAndKeyContainer`/`FakeServersCenter`
 * (reference/dev-sidecar/vendor/ds-core/mitmproxy/src/lib/proxy/tls — cert server cache): a bounded,
 * eviction-on-insert cache whose capacity is configured (`mitm.maxLeafCache`, default 256).
 */
class LruCache<K : Any, V : Any>(private val maxEntries: Int) : Iterable<Pair<K, V>> {

    init {
        require(maxEntries > 0) { "maxEntries must be > 0, got $maxEntries" }
    }

    private val map = object : LinkedHashMap<K, V>(maxEntries.coerceAtMost(64), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    /** Returns the cached value or creates, stores and returns a new one (atomic per key). */
    @Synchronized
    fun getOrPut(key: K, factory: (K) -> V): V {
        map[key]?.let { return it }
        val created = factory(key)
        map[key] = created
        return created
    }

    @Synchronized
    fun put(key: K, value: V): V? = map.put(key, value)

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    @Synchronized
    override fun iterator(): Iterator<Pair<K, V>> = map.entries.map { it.key to it.value }.iterator()
}
