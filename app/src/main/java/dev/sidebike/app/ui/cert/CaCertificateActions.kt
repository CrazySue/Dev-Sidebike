package dev.sidebike.app.ui.cert

import android.content.Intent
import android.security.KeyChain
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * CA 安装/导出的系统 Intent 构造（UI 层职责，不涉及数据层实现）。
 *
 * 证书内容来自 `SidebikeController.caCertificatePem()`；这里只做 PEM -> DER 解码与 Intent 组装：
 * - 安装：[KeyChain.createInstallIntent] + `EXTRA_CERTIFICATE`（系统证书安装器）
 * - 导出：[Intent.ACTION_SEND] 分享 PEM 文本（无分享目标时由调用方回退到剪贴板）
 *
 * 说明：android-service workstream 另有 `data/CaInstaller`；UI 侧保持自包含，避免跨 workstream
 * 的编译耦合（见 docs/design/01-ui-spec.md「CA 安装/导出」小节）。
 */
object CaCertificateActions {

    private const val CA_ALIAS = "Dev-Sidebike CA"

    /** 去掉 PEM armor 并 Base64 解码为 DER；非法输入返回 null。 */
    fun parseDer(pem: String): ByteArray? {
        val body = pem.lineSequence()
            .filterNot { line -> line.trim().startsWith("-----") }
            .joinToString(separator = "")
            .filterNot { it.isWhitespace() }
        if (body.isEmpty()) return null
        return runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
    }

    /** 解析 PEM 为 X509 证书；非法输入返回 null。 */
    fun parseCertificate(pem: String): X509Certificate? {
        val der = parseDer(pem) ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
        }.getOrNull()
    }

    /** 系统「安装证书」Intent；PEM 非法时返回 null。 */
    fun buildInstallIntent(pem: String): Intent? {
        val certificate = parseCertificate(pem) ?: return null
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_NAME, CA_ALIAS)
            putExtra(KeyChain.EXTRA_CERTIFICATE, certificate.encoded)
        }
    }

    /** 分享/导出 PEM 文本的 Intent（调用方负责 `Intent.createChooser`）。 */
    fun buildExportIntent(pem: String, subject: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-pem-file"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, pem)
    }
}
