package com.lilus.despachos

import android.content.Context

/** Preferencias locales del celular. No guarda contraseñas: solo el email de la cuenta Google elegida. */
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

    /** Pausar la cámara después de cada lectura hasta tocar SIGUIENTE. Activado de fábrica. */
    var confirmEachScan: Boolean
        get() = prefs.getBoolean("confirm_each_scan", true)
        set(v) = prefs.edit().putBoolean("confirm_each_scan", v).apply()

    /** Cuenta Google conectada para guardar en Drive (vacío = sin conectar). */
    var googleAccount: String
        get() = prefs.getString("google_account", "") ?: ""
        set(v) = prefs.edit().putString("google_account", v.trim()).apply()

    /** ID de la carpeta raíz "LILUS - DESPACHOS" en el Drive de esa cuenta (cache). */
    var driveRootId: String
        get() = prefs.getString("drive_root_id", "") ?: ""
        set(v) = prefs.edit().putString("drive_root_id", v).apply()

    /** ID de la planilla central en el Drive de esa cuenta (cache). */
    var driveBaseId: String
        get() = prefs.getString("drive_base_id", "") ?: ""
        set(v) = prefs.edit().putString("drive_base_id", v).apply()

    val isDriveConnected: Boolean get() = googleAccount.isNotBlank()

    fun disconnectDrive() {
        prefs.edit().remove("google_account").remove("drive_root_id").remove("drive_base_id").apply()
    }
}
