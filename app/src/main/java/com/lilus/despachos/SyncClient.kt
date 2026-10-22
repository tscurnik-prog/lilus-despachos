package com.lilus.despachos

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

sealed class SyncOutcome {
    data class Success(val driveUrl: String?, val alreadyExisted: Boolean) : SyncOutcome()
    /** Error transitorio: se reintenta solo. */
    data class Retryable(val message: String) : SyncOutcome()
    /** Error que necesita que alguien corrija la configuración. */
    data class Permanent(val message: String) : SyncOutcome()
    object NotConfigured : SyncOutcome()
}

/**
 * Cliente del backend de Google Apps Script. Se comunica SOLO por HTTPS, con la validación
 * de certificados normal de Android. El token del celular viaja en el cuerpo (Apps Script
 * no expone encabezados). El envío es idempotente: el backend identifica el despacho por
 * su UUID y si ya lo recibió devuelve lo existente sin duplicarlo.
 */
class SyncClient(
    private val http: OkHttpClient = defaultClient(),
    private val encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
) {

    fun submit(url: String, token: String, d: Dispatch, items: List<ScanItem>, pdf: File?, xlsx: File?): SyncOutcome {
        if (!Settings.isValidBackendUrl(url) || token.isBlank()) return SyncOutcome.NotConfigured
        val body = buildPayload(token, d, items, pdf?.readBytes()?.let(encoder), xlsx?.readBytes()?.let(encoder))
        return post(url, body) { json ->
            SyncOutcome.Success(
                driveUrl = json.optString("folderUrl").ifBlank { json.optString("pdfUrl") }.ifBlank { null },
                alreadyExisted = json.optString("status") == "already_exists",
            )
        }
    }

    /** Prueba de conexión desde Configuración. */
    fun ping(url: String, token: String): SyncOutcome {
        if (!Settings.isValidBackendUrl(url) || token.isBlank()) return SyncOutcome.NotConfigured
        val body = JSONObject().put("action", "ping").put("token", token)
        return post(url, body) { SyncOutcome.Success(it.optString("rootUrl").ifBlank { null }, false) }
    }

    private fun post(url: String, body: JSONObject, onOk: (JSONObject) -> SyncOutcome): SyncOutcome {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code in 500..599 -> SyncOutcome.Retryable("El servidor tuvo un problema. Se reintentará.")
                    resp.code == 401 || resp.code == 403 ->
                        SyncOutcome.Permanent("El servidor rechazó el acceso. Revisá la publicación del backend.")
                    resp.code == 404 -> SyncOutcome.Permanent("No se encontró el backend. Revisá la URL en Configuración.")
                    !resp.isSuccessful -> SyncOutcome.Retryable("El servidor respondió con un error (${resp.code}). Se reintentará.")
                    else -> parse(text, onOk)
                }
            }
        } catch (e: UnknownHostException) {
            SyncOutcome.Retryable("Sin conexión a internet. Se enviará cuando vuelva la conexión.")
        } catch (e: ConnectException) {
            SyncOutcome.Retryable("Sin conexión a internet. Se enviará cuando vuelva la conexión.")
        } catch (e: SocketTimeoutException) {
            SyncOutcome.Retryable("El servidor tardó demasiado en responder. Se reintentará.")
        } catch (e: SSLException) {
            SyncOutcome.Retryable("No se pudo establecer una conexión segura. Se reintentará.")
        } catch (e: IOException) {
            SyncOutcome.Retryable("Falló la conexión con el servidor. Se reintentará.")
        } catch (e: IllegalArgumentException) {
            SyncOutcome.Permanent("La URL del backend no es válida.")
        }
    }

    private fun parse(text: String, onOk: (JSONObject) -> SyncOutcome): SyncOutcome {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return if (text.contains("<html", ignoreCase = true))
                SyncOutcome.Permanent("El backend respondió una página web en lugar de datos. Revisá que esté publicado con acceso \"Cualquier usuario\".")
            else SyncOutcome.Retryable("Respuesta inválida del servidor. Se reintentará.")
        }
        if (json.optBoolean("ok", false)) return onOk(json)
        return when (json.optString("error")) {
            "unauthorized" -> SyncOutcome.Permanent("Este celular no está autorizado. Revisá el token en Configuración.")
            "invalid_payload" -> SyncOutcome.Permanent("El servidor rechazó los datos del despacho: ${json.optString("message")}")
            "busy" -> SyncOutcome.Retryable("El servidor está ocupado. Se reintentará.")
            else -> SyncOutcome.Retryable("Error del servidor: ${json.optString("message").ifBlank { "desconocido" }}. Se reintentará.")
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)          // Apps Script responde con una redirección a googleusercontent.com
            .followSslRedirects(true)
            .build()

        fun buildPayload(token: String, d: Dispatch, items: List<ScanItem>, pdfB64: String?, xlsxB64: String?): JSONObject {
            val arr = JSONArray()
            items.forEachIndexed { i, it ->
                arr.put(
                    JSONObject()
                        .put("n", i + 1)
                        .put("code", it.code) // texto íntegro
                        .put("date", Fmt.date(it.scannedAt))
                        .put("time", Fmt.time(it.scannedAt))
                        .put("ts", it.scannedAt)
                )
            }
            val closed = d.closedAt ?: d.startedAt
            val dispatch = JSONObject()
                .put("uuid", d.uuid)
                .put("id", d.displayId ?: d.uuid)
                .put("empresa", d.empresa)
                .put("logistica", d.logistica)
                .put("operario", d.operario)
                .put("fecha", Fmt.date(closed))
                .put("isoDay", Fmt.isoDay(closed))
                .put("year", Fmt.year(closed))
                .put("month", Fmt.month(closed))
                .put("horaInicio", Fmt.time(d.startedAt))
                .put("horaFin", Fmt.time(closed))
                .put("total", items.size)
                .put("duplicados", d.duplicates)
                .put("items", arr)
            return JSONObject()
                .put("action", "submitDispatch")
                .put("token", token)
                .put("dispatch", dispatch)
                .put("pdfBase64", pdfB64 ?: JSONObject.NULL)
                .put("xlsxBase64", xlsxB64 ?: JSONObject.NULL)
        }
    }
}
