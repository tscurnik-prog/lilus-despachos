package com.lilus.despachos

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Errores de Drive traducidos a mensajes para el operario. */
sealed class DriveException(message: String) : Exception(message) {
    /** El permiso venció o se revocó: hay que reconectar la cuenta (o renovar el token). */
    class Unauthorized : DriveException("Google pidió volver a conectar la cuenta.")
    /** Falla transitoria: se reintenta sola. */
    class Retryable(msg: String) : DriveException(msg)
    /** Algo que necesita intervención (configuración de Google, Drive lleno, etc.). */
    class Permanent(msg: String) : DriveException(msg)
}

/** Lo que se sube a Drive por cada despacho. */
data class DriveUpload(
    val dispatch: Dispatch,
    val items: List<ScanItem>,
    val pdf: ByteArray,
    val xlsx: ByteArray,
)

data class DriveResult(val folderUrl: String, val alreadyExisted: Boolean)

/**
 * Cliente mínimo de Google Drive v3 + Google Sheets v4 sobre HTTPS, con el permiso
 * "drive.file": la app SOLO ve y toca las carpetas y archivos que ella misma creó.
 *
 * Estructura:  despachos / <Logística> / "2026-10-27 - Colecta Meli - 001.pdf" (+ .xlsx)
 *              despachos / "LILUS - Base de despachos"  (planilla central)
 *
 * Idempotente: cada archivo lleva en appProperties el UUID del despacho; antes de subir
 * se busca, y la planilla se revisa por UUID antes de agregar filas. Reintentar no duplica.
 */
class DriveClient(
    private val http: OkHttpClient = defaultClient(),
    private val driveBase: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
    private val sheetsBase: HttpUrl = "https://sheets.googleapis.com/".toHttpUrl(),
) {
    // ---------------- API de alto nivel ----------------

    /** Email de la cuenta dueña del token (para mostrar "Conectado como ..."). */
    fun accountEmail(token: String): String {
        val j = getJson(token, drive("drive/v3/about").addQueryParameter("fields", "user(emailAddress)").build())
        return j.optJSONObject("user")?.optString("emailAddress").orEmpty()
    }

    /**
     * Sube un despacho. [cache] guarda IDs de carpeta raíz y planilla para no buscarlos cada vez.
     */
    fun uploadDispatch(token: String, up: DriveUpload, cache: DriveCache): DriveResult {
        val d = up.dispatch
        val root = ensureRoot(token, cache)
        val logFolder = findOrCreateFolder(token, d.logistica, root)
        val base = fileBaseName(d)

        val pdfExisted = ensureFile(token, logFolder, "$base.pdf", MIME_PDF, up.pdf, d.uuid, "pdf")
        val xlsxExisted = ensureFile(token, logFolder, "$base.xlsx", MIME_XLSX, up.xlsx, d.uuid, "xlsx")

        val sheetId = ensureBaseSheet(token, root, cache)
        val rowsExisted = ensureRows(token, sheetId, up)

        return DriveResult(folderUrl(logFolder), pdfExisted && xlsxExisted && rowsExisted)
    }

    // ---------------- Carpetas ----------------

    private fun ensureRoot(token: String, cache: DriveCache): String {
        cache.rootId.takeIf { it.isNotBlank() }?.let { id -> if (isAlive(token, id)) return id }
        val id = findOrCreateFolder(token, ROOT_FOLDER, "root")
        cache.rootId = id
        cache.baseId = "" // si cambió la carpeta raíz, se vuelve a ubicar la planilla
        return id
    }

    fun findOrCreateFolder(token: String, name: String, parent: String): String {
        val q = "name = ${quote(name)} and mimeType = '$MIME_FOLDER' and ${quote(parent)} in parents and trashed = false"
        findFirst(token, q)?.let { return it }
        val meta = JSONObject().put("name", name).put("mimeType", MIME_FOLDER).put("parents", JSONArray().put(parent))
        return postJson(token, drive("drive/v3/files").addQueryParameter("fields", "id").build(), meta).getString("id")
    }

    private fun isAlive(token: String, id: String): Boolean = try {
        val j = getJson(token, drive("drive/v3/files/$id").addQueryParameter("fields", "id,trashed").build())
        !j.optBoolean("trashed", false)
    } catch (e: DriveException.Permanent) {
        false // 404: la borraron
    }

    // ---------------- Archivos ----------------

    /** @return true si el archivo ya existía (reintento). */
    private fun ensureFile(token: String, folder: String, name: String, mime: String, bytes: ByteArray, uuid: String, kind: String): Boolean {
        val q = "appProperties has { key='dispatchUuid' and value=${quote(uuid)} } and " +
            "appProperties has { key='kind' and value='$kind' } and trashed = false"
        if (findFirst(token, q) != null) return true
        val meta = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folder))
            .put("appProperties", JSONObject().put("dispatchUuid", uuid).put("kind", kind))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(mime.toMediaType()))
            .build()
        val url = drive("upload/drive/v3/files").addQueryParameter("uploadType", "multipart").addQueryParameter("fields", "id").build()
        execute(token, Request.Builder().url(url).post(body))
        return false
    }

    // ---------------- Planilla central ----------------

    private fun ensureBaseSheet(token: String, root: String, cache: DriveCache): String {
        cache.baseId.takeIf { it.isNotBlank() }?.let { id -> if (isAlive(token, id)) return id }
        val q = "appProperties has { key='kind' and value='base' } and ${quote(root)} in parents and trashed = false"
        val id = findFirst(token, q) ?: run {
            // Se crea vacía desde Drive (queda dentro de "despachos") y se arma con la API de Sheets.
            val meta = JSONObject().put("name", BASE_NAME).put("mimeType", MIME_SHEET)
                .put("parents", JSONArray().put(root))
                .put("appProperties", JSONObject().put("kind", "base"))
            postJson(token, drive("drive/v3/files").addQueryParameter("fields", "id").build(), meta).getString("id")
        }
        ensureSheetStructure(token, id)
        cache.baseId = id
        return id
    }

    /** Deja la planilla con las hojas "Registros" y "Despachos" y sus encabezados. Repetible sin efectos. */
    private fun ensureSheetStructure(token: String, id: String) {
        val sheetsArr = getJson(token, sheets("v4/spreadsheets/$id").addQueryParameter("fields", "sheets.properties(sheetId,title)").build())
            .optJSONArray("sheets") ?: JSONArray()
        val titles = (0 until sheetsArr.length()).map { sheetsArr.getJSONObject(it).getJSONObject("properties") }
        val requests = JSONArray()
        if (titles.none { it.optString("title") == SHEET_REGISTROS }) {
            val first = titles.firstOrNull { it.optString("title") != SHEET_DESPACHOS }
            if (first != null) {
                requests.put(JSONObject().put("updateSheetProperties", JSONObject()
                    .put("properties", JSONObject().put("sheetId", first.getInt("sheetId")).put("title", SHEET_REGISTROS)
                        .put("gridProperties", JSONObject().put("frozenRowCount", 1)))
                    .put("fields", "title,gridProperties.frozenRowCount")))
            } else {
                requests.put(addSheet(SHEET_REGISTROS))
            }
        }
        if (titles.none { it.optString("title") == SHEET_DESPACHOS }) requests.put(addSheet(SHEET_DESPACHOS))
        if (requests.length() > 0) postJson(token, sheets("v4/spreadsheets/$id:batchUpdate").build(), JSONObject().put("requests", requests))
        if (firstCell(token, id, "$SHEET_REGISTROS!A1").isEmpty()) putValues(token, id, "$SHEET_REGISTROS!A1", listOf(HEAD_REGISTROS))
        if (firstCell(token, id, "$SHEET_DESPACHOS!A1").isEmpty()) putValues(token, id, "$SHEET_DESPACHOS!A1", listOf(HEAD_DESPACHOS))
    }

    private fun addSheet(title: String) = JSONObject().put("addSheet", JSONObject().put("properties",
        JSONObject().put("title", title).put("gridProperties", JSONObject().put("frozenRowCount", 1))))

    private fun firstCell(token: String, id: String, range: String): String =
        getJson(token, sheets("v4/spreadsheets/$id/values/$range").build())
            .optJSONArray("values")?.optJSONArray(0)?.optString(0).orEmpty()

    /** @return true si el despacho ya estaba cargado en la planilla. */
    private fun ensureRows(token: String, sheetId: String, up: DriveUpload): Boolean {
        val d = up.dispatch
        // 1) ¿ya está la fila resumen? -> todo cargado
        if (columnContains(token, sheetId, "$SHEET_DESPACHOS!A:A", d.uuid)) return true
        // 2) ¿ya están los bultos? (un append es todo o nada) -> solo falta el resumen
        if (up.items.isNotEmpty() && !columnContains(token, sheetId, "$SHEET_REGISTROS!I:I", d.uuid)) {
            appendValues(token, sheetId, "$SHEET_REGISTROS!A1", registrosRows(up))
        }
        appendValues(token, sheetId, "$SHEET_DESPACHOS!A1", listOf(despachoRow(up)))
        return false
    }

    private fun columnContains(token: String, id: String, range: String, value: String): Boolean {
        val j = getJson(token, sheets("v4/spreadsheets/$id/values/$range").addQueryParameter("majorDimension", "COLUMNS").build())
        val cols = j.optJSONArray("values") ?: return false
        val col = cols.optJSONArray(0) ?: return false
        for (i in 0 until col.length()) if (col.optString(i) == value) return true
        return false
    }

    private fun putValues(token: String, id: String, range: String, rows: List<List<Any>>) {
        val url = sheets("v4/spreadsheets/$id/values/$range").addQueryParameter("valueInputOption", "RAW").build()
        execute(token, Request.Builder().url(url).put(json(JSONObject().put("values", toJson(rows)))))
    }

    private fun appendValues(token: String, id: String, range: String, rows: List<List<Any>>) {
        val url = sheets("v4/spreadsheets/$id/values/$range:append")
            .addQueryParameter("valueInputOption", "RAW") // RAW: los códigos quedan como texto (ceros y sufijos intactos)
            .addQueryParameter("insertDataOption", "INSERT_ROWS")
            .build()
        execute(token, Request.Builder().url(url).post(json(JSONObject().put("values", toJson(rows)))))
    }

    // ---------------- HTTP ----------------

    private fun findFirst(token: String, q: String): String? {
        val url = drive("drive/v3/files").addQueryParameter("q", q).addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1").addQueryParameter("spaces", "drive").build()
        val files = getJson(token, url).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun getJson(token: String, url: HttpUrl): JSONObject = parse(execute(token, Request.Builder().url(url).get()))
    private fun postJson(token: String, url: HttpUrl, body: JSONObject): JSONObject =
        parse(execute(token, Request.Builder().url(url).post(json(body))))

    private fun execute(token: String, builder: Request.Builder): String {
        val req = builder.header("Authorization", "Bearer $token").build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return text
                val reason = errorReason(text)
                throw when {
                    resp.code == 401 -> DriveException.Unauthorized()
                    resp.code == 429 || resp.code >= 500 -> DriveException.Retryable("Google está ocupado. Se reintentará.")
                    resp.code == 403 && reason in setOf("rateLimitExceeded", "userRateLimitExceeded", "backendError") ->
                        DriveException.Retryable("Google está ocupado. Se reintentará.")
                    resp.code == 403 && reason == "storageQuotaExceeded" ->
                        DriveException.Permanent("El Google Drive de esta cuenta está lleno.")
                    resp.code == 403 && (reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" || text.contains("has not been used in project")) ->
                        DriveException.Permanent("Falta activar Google Drive/Sheets en la configuración de Google de la app.")
                    resp.code == 403 && (reason == "insufficientPermissions" || reason == "ACCESS_TOKEN_SCOPE_INSUFFICIENT") ->
                        DriveException.Unauthorized()
                    resp.code == 404 -> DriveException.Permanent("No se encontró la carpeta o archivo en Drive.")
                    else -> DriveException.Retryable("Error de Google Drive (${resp.code}). Se reintentará.")
                }
            }
        } catch (e: DriveException) {
            throw e
        } catch (e: UnknownHostException) {
            throw DriveException.Retryable("Sin conexión a internet. Se enviará cuando vuelva la conexión.")
        } catch (e: ConnectException) {
            throw DriveException.Retryable("Sin conexión a internet. Se enviará cuando vuelva la conexión.")
        } catch (e: SocketTimeoutException) {
            throw DriveException.Retryable("Google tardó demasiado en responder. Se reintentará.")
        } catch (e: SSLException) {
            throw DriveException.Retryable("No se pudo establecer una conexión segura. Se reintentará.")
        } catch (e: IOException) {
            throw DriveException.Retryable("Falló la conexión con Google. Se reintentará.")
        }
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text.ifBlank { "{}" })
    } catch (e: JSONException) {
        throw DriveException.Retryable("Respuesta inválida de Google. Se reintentará.")
    }

    private fun errorReason(text: String): String = try {
        val err = JSONObject(text).optJSONObject("error")
        err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.takeIf { it.isNotBlank() }
            ?: err?.optJSONArray("details")?.optJSONObject(0)?.optString("reason").orEmpty()
    } catch (e: JSONException) {
        ""
    }

    private fun drive(path: String) = driveBase.newBuilder().addPathSegments(path)
    private fun sheets(path: String) = sheetsBase.newBuilder().addPathSegments(path)
    private fun json(o: JSONObject): RequestBody = o.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

    companion object {
        const val ROOT_FOLDER = "despachos"
        const val BASE_NAME = "LILUS - Base de despachos"
        const val SHEET_REGISTROS = "Registros"
        const val SHEET_DESPACHOS = "Despachos"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_SHEET = "application/vnd.google-apps.spreadsheet"
        const val MIME_PDF = "application/pdf"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        val HEAD_REGISTROS = listOf("ID de despacho", "Fecha", "Hora", "Empresa", "Logística", "Operario", "Código", "Fecha de sincronización", "ID interno")
        val HEAD_DESPACHOS = listOf("ID interno", "ID de despacho", "Fecha", "Hora inicio", "Hora fin", "Empresa", "Logística", "Operario", "Bultos", "Duplicados detectados", "Fecha de sincronización")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

        /** "2026-10-27 - Colecta Meli - 001" */
        fun fileBaseName(d: Dispatch): String {
            val day = Fmt.isoDay(d.closedAt ?: d.startedAt)
            val seq = d.displayId?.substringAfterLast('-')?.takeIf { it.all(Char::isDigit) } ?: d.uuid.take(8)
            val log = d.logistica.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
            return "$day - $log - $seq"
        }

        fun folderUrl(id: String) = "https://drive.google.com/drive/folders/$id"

        fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

        fun registrosRows(up: DriveUpload): List<List<Any>> {
            val d = up.dispatch
            val now = Fmt.dateTime(System.currentTimeMillis())
            return up.items.map {
                listOf<Any>(d.displayId ?: d.uuid, Fmt.date(it.scannedAt), Fmt.time(it.scannedAt), d.empresa, d.logistica, d.operario, it.code, now, d.uuid)
            }
        }

        fun despachoRow(up: DriveUpload): List<Any> {
            val d = up.dispatch
            val end = d.closedAt ?: d.startedAt
            return listOf(
                d.uuid, d.displayId ?: d.uuid, Fmt.date(end), Fmt.time(d.startedAt), Fmt.time(end), d.empresa, d.logistica,
                d.operario, up.items.size, d.duplicates, Fmt.dateTime(System.currentTimeMillis()),
            )
        }

        private fun toJson(rows: List<List<Any>>) = JSONArray().apply {
            rows.forEach { r -> put(JSONArray().apply { r.forEach { put(it) } }) }
        }
    }
}

/** IDs de Drive recordados entre envíos. */
interface DriveCache {
    var rootId: String
    var baseId: String
}

class SettingsDriveCache(private val s: Settings) : DriveCache {
    override var rootId: String
        get() = s.driveRootId
        set(v) { s.driveRootId = v }
    override var baseId: String
        get() = s.driveBaseId
        set(v) { s.driveBaseId = v }
}
