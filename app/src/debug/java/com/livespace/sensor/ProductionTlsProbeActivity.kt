package com.livespace.sensor

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.livespace.sensor.network.ProductionTls
import okhttp3.*
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Debug-only, no credentials, no telemetry, no saved configuration changes. */
class ProductionTlsProbeActivity: Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Thread {
            val base=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
            try {
                val store=KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val anchors=store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
                Log.i(TAG,"ANCHORS count=${anchors.size} ISRG=${anchors.count { it.subjectX500Principal.name.contains("ISRG") }}")
                val done=CountDownLatch(1)
                val socket=base.newWebSocket(Request.Builder().url(ProductionTls.ENDPOINT).build(),object:WebSocketListener() {
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) {
                        var cause:Throwable?=t
                        while(cause!=null) {
                            Log.i(TAG,"BASELINE_FAILURE ${cause.javaClass.name}: ${cause.message}")
                            cause=cause.cause
                        };done.countDown()
                    }
                    override fun onOpen(ws:WebSocket,response:Response) { Log.e(TAG,"BASELINE_UNEXPECTED_OPEN");ws.cancel();done.countDown() }
                })
                check(done.await(20,TimeUnit.SECONDS));socket.cancel()
                val compat=ProductionTls.client(this,ProductionTls.ENDPOINT,base)
                check(ProductionTls.client(this,"wss://example.org/ws/sensors",base)===base)
                val host="livespace.temuulenbayarjargal.workers.dev"
                (compat.sslSocketFactory.createSocket(host,443) as SSLSocket).use { tls ->
                    tls.startHandshake()
                    check(compat.hostnameVerifier.verify(host,tls.session))
                    check(!compat.hostnameVerifier.verify("wrong.invalid",tls.session))
                    Log.i(TAG,"TLS_OK ${tls.session.protocol} HOSTNAME_OK WRONG_HOST_REJECTED")
                    val chain=tls.session.peerCertificates.map { it as X509Certificate }.toTypedArray()
                    chain.forEach { Log.i(TAG,"CHAIN ${it.subjectX500Principal.name} -> ${it.issuerX500Principal.name}") }
                    val damaged=chain[0].encoded.apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
                    chain[0]=CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(damaged)) as X509Certificate
                    val trust=resources.openRawResource(R.raw.isrg_root_x1).use { ProductionTls.trustManager(it) }
                    try { trust.checkServerTrusted(chain,"ECDHE_ECDSA");error("Invalid signature accepted") }
                    catch (_:CertificateException) { Log.i(TAG,"INVALID_SIGNATURE_REJECTED") }
                }
                compat.newCall(Request.Builder().url("https://$host/api/v1/health").build()).execute().use {
                    check(it.isSuccessful);Log.i(TAG,"OKHTTP_COMPAT_HEALTH ${it.code}")
                }
                Log.i(TAG,"PASS")
            } catch(t:Throwable) { Log.e(TAG,"FAIL ${t.javaClass.name}: ${t.message}",t) }
            finally { base.connectionPool.evictAll();base.dispatcher.executorService.shutdown();runOnUiThread { finish() } }
        }.start()
    }
    companion object { const val TAG="LiveSpaceTlsProbe" }
}
