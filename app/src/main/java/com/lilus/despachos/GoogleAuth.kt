package com.lilus.despachos

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Conexión con una cuenta Google del celular (la que elija el operario en el selector nativo
 * de Android). No se piden contraseñas: Google entrega un permiso de acceso SOLO a los
 * archivos que crea la app (drive.file).
 */
object GoogleAuth {
    private const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
        if (!email.isNullOrBlank()) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    sealed class Start {
        /** Ya hay permiso: token listo. */
        data class Token(val token: String) : Start()
        /** Hay que mostrar la pantalla de Google (elegir cuenta / aceptar permiso). */
        data class NeedsUi(val sender: IntentSender) : Start()
        data class Failed(val message: String) : Start()
    }

    /** Inicia la conexión desde una pantalla (puede abrir el selector de cuentas de Google). */
    suspend fun start(activity: Activity, email: String? = null): Start = try {
        val r = Identity.getAuthorizationClient(activity).authorize(request(email)).await()
        val pi = r.pendingIntent
        when {
            r.hasResolution() && pi != null -> Start.NeedsUi(pi.intentSender)
            r.accessToken != null -> Start.Token(r.accessToken!!)
            else -> Start.Failed("Google no devolvió el permiso. Probá de nuevo.")
        }
    } catch (e: ApiException) {
        Start.Failed(friendly(e))
    } catch (e: Exception) {
        Start.Failed("No se pudo conectar con Google. Revisá que el celular tenga Google Play actualizado.")
    }

    /** Resultado de la pantalla de Google. */
    fun finish(activity: Activity, data: Intent?): Start = if (data == null) Start.Failed("No se otorgó el permiso.") else try {
        val r: AuthorizationResult = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Start.Token(it) } ?: Start.Failed("No se otorgó el permiso.")
    } catch (e: ApiException) {
        Start.Failed(friendly(e))
    }

    /**
     * Token en segundo plano (sincronización), sin mostrar nada. Si Google necesita que el
     * usuario vuelva a confirmar, devuelve null.
     */
    suspend fun silentToken(context: Context, email: String): String? {
        val r = try {
            Identity.getAuthorizationClient(context).authorize(request(email)).await()
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) throw DriveException.Retryable("Sin conexión a internet. Se enviará cuando vuelva la conexión.")
            throw DriveException.Permanent(friendly(e))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DriveException.Retryable("No se pudo obtener el permiso de Google. Se reintentará.")
        }
        if (r.hasResolution()) return null
        return r.accessToken
    }

    /** Descarta un token vencido para que Google entregue uno nuevo. */
    fun invalidate(context: Context, token: String) {
        try { GoogleAuthUtil.clearToken(context, token) } catch (e: Exception) { }
    }

    fun friendly(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "La app todavía no está habilitada en Google (falta registrarla en Google Cloud). Avisá al administrador."
        CommonStatusCodes.NETWORK_ERROR -> "Sin conexión a internet."
        CommonStatusCodes.CANCELED -> "Se canceló la conexión con Google."
        CommonStatusCodes.SIGN_IN_REQUIRED -> "Elegí una cuenta Google para continuar."
        else -> "No se pudo conectar con Google (código ${e.statusCode})."
    }
}
