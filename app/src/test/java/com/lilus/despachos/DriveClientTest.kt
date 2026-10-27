package com.lilus.despachos

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Prueba la subida a Drive contra un Google Drive/Sheets FALSO (en memoria) que se comporta
 * como el real en lo que usa la app: búsquedas por nombre/carpeta/appProperties, subida
 * multipart y planillas con append. No usa una cuenta Google real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveClientTest {

    data class FakeFile(val id: String, val name: String, val mime: String, val parents: List<String>, val props: Map<String, String>, var trashed: Boolean = false)

    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient
    private val files = mutableListOf<FakeFile>()
    private val sheetData = mutableMapOf<String, MutableList<List<String>>>() // "Registros" / "Despachos"
    private val sheetTitles = mutableListOf<String>()
    private var nextId = 1
    private var failNext = 0
    private var status401 = false

    private val cache = object : DriveCache {
        override var rootId = ""
        override var baseId = ""
    }

    private val t0 = 1_761_580_000_000L
    private fun dispatch(uuid: String = "uuid-1", log: String = "Colecta Meli") = Dispatch(
        uuid, "COLECTAMELI-20261027-001", "ProntoTec", log, "Juan", t0, t0 + 60_000,
        DispatchStatus.CLOSED, SyncStatus.PENDING, null, 2, null, null, null, null,
    )
    private val items = listOf(
        ScanItem(1, "uuid-1", "000123", t0 + 1000, false),
        ScanItem(2, "uuid-1", "EP013434737N'01", t0 + 2000, false),
        ScanItem(3, "uuid-1", "EP013434737N'02", t0 + 3000, false),
    )
    private fun upload(d: Dispatch = dispatch()) = DriveUpload(d, items, "%PDF-1.4".toByteArray(), "PK".toByteArray())

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        client = DriveClient(http, server.url("/"), server.url("/"))
    }

    @After fun tearDown() = server.shutdown()

    // ------------------------------------------------------------------ pruebas

    @Test fun creaCarpetasArchivosYPlanilla() {
        val r = client.uploadDispatch("tok", upload(), cache)
        assertFalse(r.alreadyExisted)

        val root = files.single { it.name == "despachos" && it.mime == DriveClient.MIME_FOLDER }
        assertEquals(listOf("root"), root.parents)
        val log = files.single { it.name == "Colecta Meli" }
        assertEquals(listOf(root.id), log.parents)
        assertTrue(r.folderUrl.endsWith(log.id))

        val pdf = files.single { it.name.endsWith(".pdf") }
        val xlsx = files.single { it.name.endsWith(".xlsx") }
        assertTrue(pdf.name.matches(Regex("\\d{4}-\\d{2}-\\d{2} - Colecta Meli - 001\\.pdf")))
        assertEquals(listOf(log.id), pdf.parents)
        assertEquals(listOf(log.id), xlsx.parents)

        val base = files.single { it.props["kind"] == "base" }
        assertEquals(listOf(root.id), base.parents)

        val reg = sheetData.getValue("Registros")
        assertEquals(DriveClient.HEAD_REGISTROS, reg[0])
        assertEquals(4, reg.size)                       // encabezado + 3 bultos
        assertEquals("000123", reg[1][6])               // ceros iniciales intactos
        assertEquals("EP013434737N'02", reg[3][6])      // sufijos intactos
        assertEquals("uuid-1", reg[1][8])
        assertEquals(2, sheetData.getValue("Despachos").size)
    }

    @Test fun reintentarNoDuplicaNada() {
        client.uploadDispatch("tok", upload(), cache)
        val before = files.size
        val r = client.uploadDispatch("tok", upload(), cache)
        assertTrue(r.alreadyExisted)
        assertEquals(before, files.size)
        assertEquals(4, sheetData.getValue("Registros").size)
        assertEquals(2, sheetData.getValue("Despachos").size)
    }

    @Test fun corteAMitadDeCaminoYReintento() {
        // Falla la primera llamada a la planilla después de subir los archivos
        failAfterUploads = true
        try { client.uploadDispatch("tok", upload(), cache) } catch (e: DriveException.Retryable) { }
        client.uploadDispatch("tok", upload(), cache)
        assertEquals(1, files.count { it.name.endsWith(".pdf") })
        assertEquals(4, sheetData.getValue("Registros").size)
        assertEquals(2, sheetData.getValue("Despachos").size)
    }

    @Test fun reutilizaCarpetasEntreDespachos() {
        client.uploadDispatch("tok", upload(dispatch("uuid-1")), cache)
        client.uploadDispatch("tok", upload(dispatch("uuid-2")), DriveCacheEmpty())  // otro celular, misma cuenta
        assertEquals(1, files.count { it.name == "despachos" })
        assertEquals(1, files.count { it.name == "Colecta Meli" })
        assertEquals(1, files.count { it.props["kind"] == "base" })
        assertEquals(2, files.count { it.name.endsWith(".pdf") })
        assertEquals(3, sheetData.getValue("Despachos").size)
    }

    @Test fun otraLogisticaVaEnOtraSubcarpeta() {
        client.uploadDispatch("tok", upload(dispatch("uuid-1", "Colecta Meli")), cache)
        client.uploadDispatch("tok", upload(dispatch("uuid-2", "Flex")), cache)
        val flex = files.single { it.name == "Flex" }
        assertEquals(1, files.count { it.parents == listOf(flex.id) && it.name.endsWith(".pdf") })
    }

    @Test fun siBorranLaCarpetaLaVuelveACrear() {
        client.uploadDispatch("tok", upload(dispatch("uuid-1")), cache)
        files.filter { it.name == "despachos" }.forEach { it.trashed = true }
        client.uploadDispatch("tok", upload(dispatch("uuid-2")), cache)
        assertEquals(1, files.count { it.name == "despachos" && !it.trashed })
    }

    @Test fun erroresTraducidos() {
        status401 = true
        assertThrowsType<DriveException.Unauthorized> { client.uploadDispatch("tok", upload(), cache) }
        status401 = false
        failNext = 1
        assertThrowsType<DriveException.Retryable> { client.uploadDispatch("tok", upload(), cache) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        assertThrowsType<DriveException.Retryable> { client.uploadDispatch("tok", upload(), cache) }
    }

    @Test fun drivellenoEsPermanente() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":403,"errors":[{"reason":"storageQuotaExceeded"}]}}""")
        }
        assertThrowsType<DriveException.Permanent> { client.uploadDispatch("tok", upload(), cache) }
    }

    @Test fun emailDeLaCuenta() {
        assertEquals("deposito@lilus.com.ar", client.accountEmail("tok"))
    }

    // ------------------------------------------------------------------ Drive falso

    private var failAfterUploads = false
    class DriveCacheEmpty : DriveCache { override var rootId = ""; override var baseId = "" }

    private inline fun <reified T : Throwable> assertThrowsType(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            assertTrue("Se esperaba ${T::class.simpleName} y vino $e", e is T); return
        }
        throw AssertionError("Se esperaba ${T::class.simpleName}")
    }

    private fun json(o: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    private fun handle(req: RecordedRequest): MockResponse {
        if (req.getHeader("Authorization") != "Bearer tok") return MockResponse().setResponseCode(401)
        if (status401) return MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}""")
        if (failNext > 0) { failNext--; return MockResponse().setResponseCode(503) }
        val url = req.requestUrl!!
        val path = URLDecoder.decode(url.encodedPath, "UTF-8")
        val body = req.body.readUtf8()

        return when {
            path == "/drive/v3/about" -> json(JSONObject().put("user", JSONObject().put("emailAddress", "deposito@lilus.com.ar")))

            path == "/drive/v3/files" && req.method == "GET" -> {
                val hits = search(url.queryParameter("q")!!)
                json(JSONObject().put("files", JSONArray().apply { hits.take(1).forEach { put(JSONObject().put("id", it.id)) } }))
            }
            path == "/drive/v3/files" && req.method == "POST" -> json(JSONObject().put("id", create(JSONObject(body)).id))
            path.startsWith("/drive/v3/files/") && req.method == "GET" -> {
                val f = files.find { it.id == path.removePrefix("/drive/v3/files/") } ?: return MockResponse().setResponseCode(404)
                json(JSONObject().put("id", f.id).put("trashed", f.trashed))
            }
            path == "/upload/drive/v3/files" -> {
                val start = body.indexOf("\r\n\r\n") + 4
                val meta = JSONObject(body.substring(start, body.indexOf("\r\n--", start)))
                json(JSONObject().put("id", create(meta).id))
            }

            path.startsWith("/v4/spreadsheets/") -> {
                if (failAfterUploads) { failAfterUploads = false; return MockResponse().setResponseCode(503) }
                val rest = path.removePrefix("/v4/spreadsheets/")
                when {
                    rest.endsWith(":batchUpdate") -> {
                        val reqs = JSONObject(body).getJSONArray("requests")
                        for (i in 0 until reqs.length()) {
                            val r = reqs.getJSONObject(i)
                            r.optJSONObject("updateSheetProperties")?.let { u ->
                                val p = u.getJSONObject("properties"); sheetTitles[p.getInt("sheetId")] = p.getString("title")
                            }
                            r.optJSONObject("addSheet")?.let { a -> sheetTitles += a.getJSONObject("properties").getString("title") }
                        }
                        json(JSONObject())
                    }
                    rest.contains("/values/") && rest.endsWith(":append") -> {
                        val sheet = rest.substringAfter("/values/").substringBefore("!")
                        if (sheet !in sheetTitles) return MockResponse().setResponseCode(400).setBody("""{"error":{"code":400}}""")
                        rows(body).forEach { sheetData.getOrPut(sheet) { mutableListOf() }.add(it) }
                        json(JSONObject())
                    }
                    rest.contains("/values/") && req.method == "PUT" -> {
                        val sheet = rest.substringAfter("/values/").substringBefore("!")
                        val data = sheetData.getOrPut(sheet) { mutableListOf() }
                        val newRows = rows(body)
                        if (data.isEmpty()) data.addAll(newRows) else data[0] = newRows[0]
                        json(JSONObject())
                    }
                    rest.contains("/values/") -> {
                        val range = rest.substringAfter("/values/")
                        val sheet = range.substringBefore("!")
                        val data = sheetData[sheet].orEmpty()
                        if (url.queryParameter("majorDimension") == "COLUMNS") {
                            val col = range.substringAfter("!").substringBefore(":")[0] - 'A'
                            json(JSONObject().put("values", JSONArray().put(JSONArray(data.map { it.getOrElse(col) { "" } }))))
                        } else {
                            if (data.isEmpty()) json(JSONObject().put("range", range))
                            else json(JSONObject().put("values", JSONArray().put(JSONArray().put(data[0][0]))))
                        }
                    }
                    else -> json(JSONObject().put("sheets", JSONArray().apply {
                        sheetTitles.forEachIndexed { i, t -> put(JSONObject().put("properties", JSONObject().put("sheetId", i).put("title", t))) }
                    }))
                }
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun rows(body: String): List<List<String>> {
        val arr = JSONObject(body).getJSONArray("values")
        return (0 until arr.length()).map { i -> val r = arr.getJSONArray(i); (0 until r.length()).map { r.get(it).toString() } }
    }

    private fun create(meta: JSONObject): FakeFile {
        val parents = meta.optJSONArray("parents")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("root")
        val props = meta.optJSONObject("appProperties")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()
        val f = FakeFile("id${nextId++}", meta.getString("name"), meta.optString("mimeType", "file"), parents, props)
        if (f.mime == DriveClient.MIME_SHEET) { sheetTitles.clear(); sheetTitles += "Hoja 1" }
        files += f
        return f
    }

    private fun search(q: String): List<FakeFile> {
        val name = Regex("name = '(.*?)'").find(q)?.groupValues?.get(1)
        val mime = Regex("mimeType = '(.*?)'").find(q)?.groupValues?.get(1)
        val parent = Regex("'([^']*)' in parents").find(q)?.groupValues?.get(1)
        val props = Regex("key='(.*?)' and value='(.*?)'").findAll(q).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return files.filter { f ->
            !f.trashed &&
                (name == null || f.name == name) &&
                (mime == null || f.mime == mime) &&
                (parent == null || parent in f.parents) &&
                props.all { (k, v) -> f.props[k] == v }
        }
    }
}
