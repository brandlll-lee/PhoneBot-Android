package dev.phonebot.android.phone.adb

import android.annotation.SuppressLint
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** TLS 导出密钥(RFC 8446 §7.5)。JDK 没有公开接口,由平台提供:Android 上是 `SSLSockets.exportKeyingMaterial`。 */
fun interface KeyingMaterialExporter {
    fun export(socket: SSLSocket, label: String, length: Int): ByteArray
}

/**
 * adb 用的 TLS 1.3:双方出示自签名证书。
 * 配对时身份由 SPAKE2 绑定到导出密钥上;连接时由设备比对我们证书里的公钥。只连本机回环地址。
 */
class AdbTls(
    val identity: AdbIdentity,
    private val exporter: KeyingMaterialExporter,
) {
    val context: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(FixedKeyManager(identity.keyPair.private, identity.certificate)), arrayOf(AdbTrust), null)
        ctx
    }

    /** 新建 TCP 连接并完成 TLS 握手(客户端)。 */
    fun connect(host: String, port: Int, timeoutMs: Int, onSocket: (Socket) -> Unit = {}): SSLSocket {
        require(host == "127.0.0.1" || host == "::1") { "ADB transport requires loopback" }
        val plain = Socket()
        try {
            onSocket(plain)
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            plain.soTimeout = timeoutMs
            return wrap(plain, host, port)
        } catch (e: Exception) {
            plain.close()
            throw e
        }
    }

    fun wrap(plain: Socket, host: String, port: Int): SSLSocket {
        require(plain.inetAddress.isLoopbackAddress) { "ADB transport requires loopback" }
        val s = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        try {
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.useClientMode = true
            s.startHandshake()
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray = exporter.export(socket, label, length)

    private class FixedKeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(cert)
        override fun getPrivateKey(alias: String?) = key
    }

    // ADB uses self-signed certificates. SPAKE2 binds pairing to TLS; adbd checks the paired client key.
    // This client uses loopback. Helper attachment also checks shell UID and the launch nonce.
    @SuppressLint("CustomX509TrustManager")
    private object AdbTrust : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("client-only ADB transport") }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.singleOrNull() ?: throw CertificateException("ADB requires a self-signed certificate")
            try { cert.verify(cert.publicKey) }
            catch (error: GeneralSecurityException) { throw CertificateException("invalid ADB certificate", error) }
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkServerTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkServerTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ALIAS = "adb"
    }
}
