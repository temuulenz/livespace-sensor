package com.livespace.sensor.network

import android.content.Context
import android.os.Build
import com.livespace.sensor.R
import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** App-local compatibility for the API 23 production connection only. Never changes global TLS. */
object ProductionTls {
    const val ENDPOINT = "wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors"
    const val ROOT_SHA256 = "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"

    fun applies(endpoint: String, sdk: Int) = sdk == 23 && endpoint == ENDPOINT

    fun client(context: Context, endpoint: String, base: OkHttpClient): OkHttpClient {
        if (!applies(endpoint, Build.VERSION.SDK_INT)) return base
        val trust = context.resources.openRawResource(R.raw.isrg_root_x1).use { trustManager(it) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        // Keep OkHttp's hostname verifier, TLS versions, cipher policy, redirect policy and pings.
        return base.newBuilder().sslSocketFactory(tls.socketFactory, trust).build()
    }

    fun trustManager(rootPem: InputStream): X509TrustManager {
        val root = CertificateFactory.getInstance("X.509").generateCertificate(rootPem) as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(fingerprint == ROOT_SHA256 && root.basicConstraints >= 0) { "Unexpected bundled CA" }
        root.verify(root.publicKey)
        val additional = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("isrg-root-x1", root)
        }
        fun manager(store: KeyStore?): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        return AdditionalRootTrustManager(manager(null), manager(additional))
    }
}

/** Each delegate performs full platform certificate-path validation; neither accepts arbitrary chains. */
internal class AdditionalRootTrustManager(
    private val system: X509TrustManager,
    private val additional: X509TrustManager
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + additional.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try { system.checkServerTrusted(chain, authType); return }
        catch (systemFailure: CertificateException) {
            try { additional.checkServerTrusted(chain, authType) }
            catch (additionalFailure: CertificateException) {
                additionalFailure.addSuppressed(systemFailure)
                throw additionalFailure
            }
        }
    }
}
