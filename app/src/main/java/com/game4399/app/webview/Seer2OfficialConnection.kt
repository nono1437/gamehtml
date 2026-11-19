package com.game4399.app.webview

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Checks public official endpoints without issuing a game login request. */
internal object Seer2OfficialConnection {
    private const val TIMEOUT_MILLIS = 4000
    private const val LOGIN_PORT = 1863

    fun check(legacyHostnameVerification: Boolean): String = buildString {
        for (host in Seer2Assets.discoveryHosts) {
            for (scheme in listOf("http", "https")) {
                val address = URL("$scheme://$host.61.com/ip.txt")
                appendLine("$address | ${probeDiscovery(address)}")
            }
        }
        // The telecom and netcom hosts are checked even when discovery fails.
        for (line in listOf("ctsr2login", "cncsr2login")) {
            val host = "$line.61.com"
            val results = probeSocket(host, LOGIN_PORT, TIMEOUT_MILLIS, legacyHostnameVerification)
            appendLine("$host:$LOGIN_PORT | ${results.joinToString(" | ")}")
        }
    }.trimEnd()

    private fun probeDiscovery(address: URL): String {
        val scheme = address.protocol.uppercase(java.util.Locale.ROOT)
        try {
            val connection = address.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = Seer2Assets.DISCOVERY_TIMEOUT_MILLIS
                connection.readTimeout = Seer2Assets.DISCOVERY_TIMEOUT_MILLIS
                connection.useCaches = false
                val status = connection.responseCode
                if (status != 200) return "HTTP $status"

                val buffer = ByteArray(Seer2Assets.DISCOVERY_MAX_BYTES + 1)
                val size = connection.inputStream.use { stream ->
                    var offset = 0
                    while (offset < buffer.size) {
                        val read = stream.read(buffer, offset, buffer.size - offset)
                        if (read == -1) break
                        offset += read
                    }
                    offset
                }
                val valid = Seer2Assets.validPayload("/ip.txt", buffer.copyOf(size))
                return if (valid) "HTTP 200，服务器地址有效" else "HTTP 200，服务器地址格式无效"
            } finally {
                connection.disconnect()
            }
        } catch (_: UnknownHostException) {
            return "$scheme 域名解析失败"
        } catch (_: SocketTimeoutException) {
            return "$scheme 查询超时"
        } catch (_: SSLException) {
            return "$scheme 握手或证书校验失败"
        } catch (_: IOException) {
            return "$scheme 连接失败"
        }
    }

    internal fun probeSocket(
        host: String,
        port: Int,
        timeoutMillis: Int,
        legacyHostnameVerification: Boolean = false
    ): List<String> {
        return Socket().use { tcp ->
            try {
                tcp.connect(InetSocketAddress(host, port), timeoutMillis)
            } catch (_: UnknownHostException) {
                return listOf("TCP 域名解析失败")
            } catch (_: SocketTimeoutException) {
                return listOf("TCP 连接超时")
            } catch (_: ConnectException) {
                return listOf("TCP 连接被拒绝或不可达")
            } catch (_: IOException) {
                return listOf("TCP 连接失败")
            }

            val tlsResult = try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(tcp, host, port, false) as SSLSocket).use { tls ->
                    tls.soTimeout = timeoutMillis
                    if (!legacyHostnameVerification) {
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    }
                    tls.startHandshake()
                    if (legacyHostnameVerification &&
                        !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                        throw SSLPeerUnverifiedException("Hostname verification failed")
                    }
                    "TLS 握手及证书校验通过"
                }
            } catch (_: SocketTimeoutException) {
                "TLS 握手超时"
            } catch (_: IOException) {
                "TLS 握手或证书校验失败"
            }
            listOf("TCP 已连通", tlsResult)
        }
    }
}
