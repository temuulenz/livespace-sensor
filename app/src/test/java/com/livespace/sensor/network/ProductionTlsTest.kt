package com.livespace.sensor.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.cert.CertificateException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

class ProductionTlsTest {
    @Test fun compatibilityScopeIsExactAndOnlyApi23() {
        assertTrue(ProductionTls.applies(ProductionTls.ENDPOINT, 23))
        for (api in listOf(22,24,25,35,37)) assertFalse(ProductionTls.applies(ProductionTls.ENDPOINT,api))
        for (url in listOf(ProductionTls.ENDPOINT+"/", ProductionTls.ENDPOINT.replace("wss:","ws:"),
            ProductionTls.ENDPOINT.replace("/ws/sensors","/other"),ProductionTls.ENDPOINT.replace(".dev/",".dev.evil/")))
            assertFalse(ProductionTls.applies(url,23))
    }
    @Test fun bundledOfficialRootMatchesFingerprintAndRejectsUnrelatedLeaf() {
        val trust=File("src/main/res/raw/isrg_root_x1.pem").inputStream().use { ProductionTls.trustManager(it) }
        assertTrue(trust.acceptedIssuers.any { it.subjectX500Principal.name.contains("ISRG Root X1") })
        val unrelated=HeldCertificate.Builder().commonName("localhost").build().certificate
        try { trust.checkServerTrusted(arrayOf(unrelated),"RSA");fail("Untrusted leaf accepted") } catch (_:CertificateException) { }
    }
    @Test fun platformPathValidationAndDefaultHostnameVerificationRemainEnforced() {
        val root=HeldCertificate.Builder().certificateAuthority(1).commonName("Test root").build()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        val system=HandshakeCertificates.Builder().addPlatformTrustedCertificates().build()
        val trust=AdditionalRootTrustManager(system.trustManager,trusted.trustManager)
        val tls=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
        val client=OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust).build()
        fun exercise(leaf:HeldCertificate, accepted:Boolean, signed:Boolean=true) {
            val server=MockWebServer()
            val serverCertificates=HandshakeCertificates.Builder().apply { if(signed) heldCertificate(leaf,root.certificate) else heldCertificate(leaf) }.build()
            server.useHttps(serverCertificates.sslSocketFactory(),false)
            server.enqueue(MockResponse().setBody("ok"));server.start()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    if(!accepted) fail("Invalid certificate or hostname accepted")
                    assertEquals(200,it.code)
                }
            } catch(e:SSLException) { if(accepted) throw e }
            finally { server.shutdown() }
        }
        exercise(HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(root).build(),true)
        exercise(HeldCertificate.Builder().commonName("wrong.invalid").addSubjectAlternativeName("wrong.invalid").signedBy(root).build(),false)
        exercise(HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .validityInterval(1,1000).signedBy(root).build(),false)
        exercise(HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build(),false,false)
        client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
    }
}
