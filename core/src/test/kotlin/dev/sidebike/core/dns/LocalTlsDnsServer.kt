package dev.sidebike.core.dns

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.x500.X500Name
import java.io.BufferedInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIMatcher
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * 本地 TLS DNS 服务器（DoT / DoH-over-TLS 测试用，回环随机端口）。
 *
 * - 自签名证书（CN=dns.test），无需外部 CA；
 * - 可选 [expectedSni]：服务端只接受该 SNI（用于验证 "诱饵 SNI 握手失败 → 回退真实 SNI"）；
 * - 处理器与 [LocalTcpDnsServer] 相同：2 字节长度前缀的 DNS 报文。
 */
class LocalTlsDnsServer(
    private val expectedSni: String? = null,
    private val handler: (ByteArray) -> ByteArray?,
) : AutoCloseable {

    private val serverSocket: SSLServerSocket
    private val worker: Thread

    init {
        val context = newServerSslContext()
        val socket = context.serverSocketFactory.createServerSocket(
            0,
            8,
            InetAddress.getLoopbackAddress(),
        ) as SSLServerSocket
        if (expectedSni != null) {
            val params = socket.sslParameters
            params.sniMatchers = listOf(object : SNIMatcher(0) {
                override fun matches(serverName: SNIServerName): Boolean =
                    serverName is SNIHostName && serverName.asciiName == expectedSni
            })
            socket.sslParameters = params
        }
        serverSocket = socket
        worker = thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { raw -> handle(raw as SSLSocket) }
                } catch (_: Exception) {
                    // 单连接失败（例如诱饵 SNI 被拒）不能终止 accept 循环，否则无法测试回退重试
                    if (serverSocket.isClosed) break
                }
            }
        }
    }

    val port: Int get() = serverSocket.localPort

    private fun handle(socket: SSLSocket) {
        socket.soTimeout = 5_000
        socket.startHandshake()
        val input = BufferedInputStream(socket.inputStream)
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return
        val length = (high shl 8) or low
        val query = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(query, read, length - read)
            if (n < 0) return
            read += n
        }
        val response = handler(query) ?: return
        val out = socket.outputStream
        out.write((response.size ushr 8) and 0xFF)
        out.write(response.size and 0xFF)
        out.write(response)
        out.flush()
    }

    override fun close() {
        runCatching { serverSocket.close() }
        runCatching { worker.join(500) }
    }

    companion object {

        /** 生成自签名服务器证书并构造 SSLContext（仅测试用途）。 */
        fun newServerSslContext(): SSLContext {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=dns.test")
            val certBuilder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - 60_000),
                Date(now + 24L * 3600 * 1000),
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry("server", keyPair.private, charArrayOf(), arrayOf(certificate))
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, charArrayOf())
            return SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, null, SecureRandom())
            }
        }
    }
}
