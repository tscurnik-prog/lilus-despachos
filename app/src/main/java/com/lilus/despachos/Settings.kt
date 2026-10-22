package com.lilus.despachos

import android.content.Context

/**
 * Preferencias locales del celular. La URL del backend y el token del dispositivo
 * los carga un administrador desde la pantalla "Configuración": NO están en el código.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("lilus_settings", Context.MODE_PRIVATE)

    var operario: String
        get() = prefs.getString("operario", "") ?: ""
        set(v) = prefs.edit().putString("operario", v.trim()).apply()

    var empresa: String
        get() = prefs.getString("empresa", Catalog.EMPRESAS.first()) ?: Catalog.EMPRESAS.first()
        set(v) = prefs.edit().putString("empresa", v).apply()

    var logistica: String
        get() = prefs.getString("logistica", "") ?: ""
        set(v) = prefs.edit().putString("logistica", v).apply()

    var backendUrl: String
        get() = prefs.getString("backend_url", "") ?: ""
        set(v) = prefs.edit().putString("backend_url", v.trim()).apply()

    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(v) = prefs.edit().putString("device_token", v.trim()).apply()

    val isBackendConfigured: Boolean
        get() = isValidBackendUrl(backendUrl) && deviceToken.isNotBlank()

    companion object {
        /** Solo HTTPS. */
        fun isValidBackendUrl(url: String): Boolean {
            val u = url.trim()
            if (!u.startsWith("https://", ignoreCase = true)) return false
            val host = u.substring("https://".length).substringBefore('/').substringBefore('?').substringBefore(':')
            return host.isNotEmpty() && !host.contains(' ')
        }
    }
}
