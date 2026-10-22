package com.lilus.despachos

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Prueba el protocolo con un backend falso por HTTPS que imita la idempotencia del Apps Script. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient
    private lateinit var url: String

    private val stored = mutableMapOf<String, JSONObject>()
    private var failNext = 0

    private val d = Dispatch(
        "uuid-1", "ENVIOPACK-20261022-001", "ProntoTec", "EnvíoPack", "Juan", 0L, 1000L,
        DispatchStatus.CLOSED, SyncStatus.PENDING, null, 0, null, null, null, null,
    )
    private val items = listOf(ScanItem(1, "uuid-1", "000123", 0L, false), ScanItem(2, "uuid-1", "EP013434737N'02", 1L, false))

    @Before fun setUp() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failNext > 0) { failNext--; return MockResponse().setResponseCode(503) }
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("token") != "tok") return json("""{"ok":false,"error":"unauthorized"}""")
                if (body.optString("action") == "ping") return json("""{"ok":true}""")
                val disp = body.getJSONObject("dispatch")
                val uuid = disp.getString("uuid")
                val existed = stored.containsKey(uuid)
                if (!existed) stored[uuid] = disp
                return json("""{"ok":true,"status":"${if (existed) "already_exists" else "created"}","folderUrl":"https://drive.google.com/f"}""")
            }
        }
        server.start()
        url = HttpUrl.Builder().scheme("https").host("localhost").port(server.port).addPathSegment("exec").build().toString()
        val http = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        client = SyncClient(http)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    @Test fun envioExitosoConservaCodigosComoTexto() {
        val r = client.submit(url, "tok", d, items, null, null)
        assertTrue(r is SyncOutcome.Success)
        assertEquals("https://drive.google.com/f", (r as SyncOutcome.Success).driveUrl)
        val arr = stored.getValue("uuid-1").getJSONArray("items")
        assertEquals("000123", arr.getJSONObject(0).getString("code"))
        assertEquals("EP013434737N'02", arr.getJSONObject(1).getString("code"))
    }

    @Test fun reintentoTrasErrorDeServidorNoDuplica() {
        failNext = 1
        assertTrue(client.submit(url, "tok", d, items, null, null) is SyncOutcome.Retryable)
        assertTrue(client.submit(url, "tok", d, items, null, null) is SyncOutcome.Success)
        // respuesta perdida -> la app reintenta -> el backend reconoce el UUID
        val again = client.submit(url, "tok", d, items, null, null)
        assertTrue((again as SyncOutcome.Success).alreadyExisted)
        assertEquals(1, stored.size)
    }

    @Test fun tokenInvalidoEsErrorPermanente() {
        assertTrue(client.submit(url, "malo", d, items, null, null) is SyncOutcome.Permanent)
    }

    @Test fun respuestasInvalidas() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>login</html>")
        }
        assertTrue(client.submit(url, "tok", d, items, null, null) is SyncOutcome.Permanent)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{no es json")
        }
        assertTrue(client.submit(url, "tok", d, items, null, null) is SyncOutcome.Retryable)
    }

    @Test fun tiempoDeEsperaAgotado() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        assertTrue(client.submit(url, "tok", d, items, null, null) is SyncOutcome.Retryable)
    }

    @Test fun sinConfigurarNoEnviaNada() {
        assertTrue(client.submit("", "tok", d, items, null, null) is SyncOutcome.NotConfigured)
        assertTrue(client.submit("http://inseguro.com/x", "tok", d, items, null, null) is SyncOutcome.NotConfigured)
        assertEquals(0, server.requestCount)
    }
}
