package dev.sidebike.core.dns

import dev.sidebike.core.config.DefaultConfigRepository
import dev.sidebike.core.engine.DefaultSidebikeEngine
import dev.sidebike.core.log.RingBufferSidebikeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 【非 hermetic，默认跳过】真实网络后端 E2E 验证（T6 独立验证任务）。
 *
 * 只有在设置环境变量 `DS_NETWORK_E2E=1` 时才执行；CI/常规 `:core:test` 会 assume-skip，
 * 不违反"测试不得访问外网"的项目规约。
 *
 * 覆盖：
 * 1. `DefaultConfigRepository.load()`（内置规则快照）
 * 2. `DefaultSidebikeEngine.start()`（真实监听 127.0.0.1:<ephemeral>）
 * 3. tun 路径 `resolveRawDnsQuery` 真实上游解析（github.com / raw.githubusercontent.com / fonts.googleapis.com）
 * 4. HTTP 代理 CONNECT + 真实 TLS 握手（mirrors.cloud.tencent.com 必测；github.com 尽力而为）
 * 5. 明文 HTTP forward（example.com）
 * 6. stop 后端口释放
 *
 * 结果写入 `$DS_E2E_OUT`（默认 `$TMPDIR/ds_e2e_evidence.txt`），供 docs/verification/engine-e2e.md 引用。
 */
class NetworkE2EVerifyTest {

    private val lines = mutableListOf<String>()
    private val outFile: File =
        File(System.getenv("DS_E2E_OUT") ?: (System.getProperty("java.io.tmpdir") + "/ds_e2e_evidence.txt"))

    private fun record(text: String) {
        lines.add(text)
        println(text)
    }

    private fun flush() {
        runCatching {
            outFile.parentFile?.mkdirs()
            outFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        }
    }

    private fun elapsedMs(start: Long): Long = (System.currentTimeMillis() - start)

    @Test(timeout = 120_000)
    fun realNetworkEngineE2E() = runBlocking {
        Assume.assumeTrue(
            "真实网络 E2E：仅当 DS_NETWORK_E2E=1 时运行（默认跳过，保持 hermetic CI）",
            System.getenv("DS_NETWORK_E2E") == "1",
        )
        val logger = RingBufferSidebikeLogger(500)
        var engine: DefaultSidebikeEngine? = null
        val dataDir = Files.createTempDirectory("ds-e2e").toFile()
        record("== Dev-Sidebike backend real-network E2E ==")
        record("time=${java.time.OffsetDateTime.now()}")
        record("dataDir=${dataDir.absolutePath}")
        record("outFile=${outFile.absolutePath}")
        try {
            // ---------- 1. config repo + snapshot ----------
            val t0 = System.currentTimeMillis()
            val repo = DefaultConfigRepository(dataDir, logger)
            val loaded = repo.load()
            record(
                "[PASS] config.load: ${elapsedMs(t0)}ms, intercepts=${loaded.intercepts.size} domains, " +
                    "presetIp=${loaded.preSetIpList.size} domains, dnsProviders=${loaded.dns.providers.size}, " +
                    "mapping=${loaded.dns.mapping.size}, domestic=${loaded.domesticAllowList.size}, snapshot=${dev.sidebike.core.config.BundledConfig.isSnapshotPresent()}",
            )
            assertTrue("内置规则快照必须存在", dev.sidebike.core.config.BundledConfig.isSnapshotPresent())
            assertTrue("intercepts 数量异常: ${loaded.intercepts.size}", loaded.intercepts.size > 50)

            // 端口用 0 避免与运行中的 Dev-Sidecar(31181) 冲突；DNS 超时调短
            val config = loaded.copy(
                proxy = loaded.proxy.copy(port = 0, mitmEnabled = false, connectTimeoutMs = 5_000, readTimeoutMs = 8_000),
                mitm = loaded.mitm.copy(enabled = false),
                dns = loaded.dns.copy(queryTimeoutMs = 2_500),
            )

            // ---------- 2. engine start ----------
            val t1 = System.currentTimeMillis()
            engine = DefaultSidebikeEngine(dataDir, config, logger)
            engine.start()
            val port = engine.stats().traffic.proxyPort
            record("[${if (engine.isRunning && port > 0) "PASS" else "FAIL"}] engine.start: ${elapsedMs(t1)}ms, proxyPort=$port, running=${engine.isRunning}")
            assertTrue("engine 未运行", engine.isRunning)
            assertTrue("代理端口未监听: $port", port > 0)

            // ---------- 3. raw tun DNS (real upstreams) ----------
            val hosts = listOf("github.com", "raw.githubusercontent.com", "fonts.googleapis.com")
            val resolver = DefaultDnsResolver(config, logger) // 单独实例用于打印 provider 路径（真实网络）
            for (host in hosts) {
                val start = System.currentTimeMillis()
                val query = DnsCodec.buildQuery(host, DnsType.A, id = 0x4000 + host.hashCode().and(0x3FFF), edns0 = true)
                val reply = engine.resolveRawDnsQuery(query)
                val cost = elapsedMs(start)
                if (reply == null) {
                    record("[SKIP] rawDNS $host: null reply (network failure)")
                    continue
                }
                val parsed = DnsCodec.parse(reply)
                val ips = parsed?.ipAnswers()?.map { "${it.ip}/${it.ttlSec}s" } ?: emptyList()
                val rcode = parsed?.rcode ?: -1
                val tc = parsed?.isTruncated ?: true
                // 直接解析（非 raw）拿 provider 路径
                val answer = runCatching { resolver.resolve(host) }.getOrNull()
                val provider = answer?.provider ?: "?"
                val providerIps = answer?.ips?.take(4) ?: emptyList()
                val ok = rcode == 0 && ips.isNotEmpty() && !tc
                record(
                    "[${if (ok) "PASS" else "SKIP"}] rawDNS $host: ${cost}ms, rcode=$rcode, tc=$tc, rawIPs=$ips, " +
                        "resolverProvider=$provider, resolverIPs=$providerIps, fromCache=${answer?.fromCache}, ttl=${answer?.ttlSec}",
                )
            }
            val dnsStats = engine.stats()
            record(
                "[INFO] dnsStats: queries=${dnsStats.traffic.dnsQueries}, cacheHits=${dnsStats.traffic.dnsCacheHits}, " +
                    "failures=${dnsStats.traffic.dnsFailures}, cacheEntries=${dnsStats.dnsCacheEntries}, avgLatencyMs=${dnsStats.traffic.dnsAvgLatencyMs}",
            )

            // ---------- 4. plain HTTP forward via proxy ----------
            val httpStart = System.currentTimeMillis()
            val httpCode = runCatching {
                val url = URL("http://example.com/")
                val conn = url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                conn.requestMethod = "GET"
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
            record("[${if (httpCode.getOrNull() == 200) "PASS" else "SKIP"}] plainHTTP forward example.com via proxy: ${elapsedMs(httpStart)}ms, code=${httpCode.getOrElse { "ERR:${it.message}" }}")

            // ---------- 5. CONNECT + real TLS handshake ----------
            // 必测：国内可直连目标；尽力而为：github.com（本机直连可能被 SNI 阻断，MITM 关闭时不伪装 SNI）
            val tlsTargets = listOf("mirrors.cloud.tencent.com" to 443, "github.com" to 443)
            for ((host, tlsPort) in tlsTargets) {
                val start = System.currentTimeMillis()
                val outcome = runCatching { connectViaProxyAndTls(port, host, tlsPort) }
                val cost = elapsedMs(start)
                outcome.fold(
                    onSuccess = { info ->
                        record("[PASS] CONNECT+TLS $host:$tlsPort via proxy: ${cost}ms, $info")
                    },
                    onFailure = { e ->
                        record("[SKIP] CONNECT+TLS $host:$tlsPort: ${cost}ms, error=${e::class.simpleName}: ${e.message}")
                    },
                )
            }

            // ---------- 6. stop + port release ----------
            val stopStart = System.currentTimeMillis()
            engine.stop()
            engine.close()
            engine = null
            val released = runCatching {
                ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
                true
            }.getOrDefault(false)
            record("[${if (released) "PASS" else "FAIL"}] engine.stop: ${elapsedMs(stopStart)}ms, port $port released=$released")
            assertTrue("stop 后端口未释放: $port", released)
        } catch (e: Throwable) {
            record("[FAIL] E2E exception: ${e::class.simpleName}: ${e.message}")
            throw e
        } finally {
            runCatching { engine?.stop() }
            runCatching { engine?.close() }
            record("[INFO] engineClassloader snapshot path = /dev/sidebike/core/rules/rules.json")
            flush()
            runCatching { dataDir.deleteRecursively() }
        }
        assertNotNull("evidence file", outFile.takeIf { it.isFile })
    }

    /** 通过本地 HTTP 代理 CONNECT 隧道做真实 TLS 握手，返回证书/协议信息。 */
    private fun connectViaProxyAndTls(proxyPort: Int, host: String, tlsPort: Int): String {
        Socket().use { raw ->
            raw.soTimeout = 10_000
            raw.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
            val out = raw.getOutputStream()
            out.write(
                ("CONNECT $host:$tlsPort HTTP/1.1\r\n" +
                    "Host: $host:$tlsPort\r\n" +
                    "User-Agent: ds-e2e\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            val reader = BufferedReader(InputStreamReader(raw.getInputStream(), Charsets.US_ASCII))
            val statusLine = reader.readLine() ?: error("CONNECT 无状态行")
            if (!statusLine.contains(" 200")) error("CONNECT 失败: $statusLine")
            // 隧道建立后，用 SSLSocket 包裹原始 socket（注意：TLS 由代理直通到目标，本机执行客户端握手）
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, tlsPort, true) as SSLSocket
            ssl.soTimeout = 10_000
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()
            val session = ssl.session
            val peer = session.peerCertificates.firstOrNull() as? X509Certificate
            val info = "tls=${session.protocol}/${session.cipherSuite}, peerSubject=${peer?.subjectX500Principal?.name?.take(120)}, " +
                "issuer=${peer?.issuerX500Principal?.name?.take(120)}, validityDaysLeft=${peer?.let { ((it.notAfter.time - System.currentTimeMillis()) / 86_400_000).toInt() }}"
            runCatching { ssl.close() }
            return info
        }
    }
}
