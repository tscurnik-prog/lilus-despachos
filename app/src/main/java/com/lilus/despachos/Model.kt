package com.lilus.despachos

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DispatchStatus { OPEN, CLOSED }

/** Estado de sincronización con Google Drive. */
enum class SyncStatus(val label: String) {
    NONE("En curso"),
    PENDING("Cerrado, pendiente de sincronización"),
    SYNCED("Sincronizado"),
    ERROR("Error de sincronización");
}

data class Dispatch(
    val uuid: String,
    val displayId: String?,
    val empresa: String,
    val logistica: String,
    val operario: String,
    val startedAt: Long,
    val closedAt: Long?,
    val status: DispatchStatus,
    val syncStatus: SyncStatus,
    val syncError: String?,
    val duplicates: Int,
    val pdfPath: String?,
    val xlsxPath: String?,
    val driveUrl: String?,
    val syncedAt: Long?,
    val itemCount: Int = 0,
) {
    val isOpen get() = status == DispatchStatus.OPEN
    /** Texto de estado para mostrar al operario. */
    val stateLabel: String get() = if (isOpen) SyncStatus.NONE.label else syncStatus.label
}

data class ScanItem(
    val id: Long,
    val dispatchUuid: String,
    val code: String,
    val scannedAt: Long,
    val removed: Boolean,
)

data class AuditEntry(
    val id: Long,
    val dispatchUuid: String,
    val action: String,
    val code: String?,
    val at: Long,
    val operario: String,
    val detail: String?,
)

data class CodeHit(val dispatch: Dispatch, val item: ScanItem)

sealed class ScanResult {
    data class Registered(val code: String, val count: Int) : ScanResult()
    data class Duplicate(val code: String, val firstScannedAt: Long, val count: Int) : ScanResult()
    object Unreadable : ScanResult()
    object DispatchClosed : ScanResult()
}

object CodeRules {
    /**
     * Conserva el código ÍNTEGRO: no quita sufijos, no quita ceros, no convierte a número.
     * Lo único que se descarta son espacios / caracteres de control al principio y al final
     * (saltos de línea que agregan algunos lectores o el teclado). Los caracteres internos
     * (por ejemplo el separador GS1) se mantienen tal cual.
     */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim { it.isWhitespace() || it.isISOControl() }
        return t.ifEmpty { null }
    }
}

object DispatchIds {
    /** "EnvíoPack FLEX" -> "ENVIOPACKFLEX" */
    fun slug(logistica: String): String {
        val noAccents = Normalizer.normalize(logistica, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val s = noAccents.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        return s.ifEmpty { "DESPACHO" }
    }

    fun prefix(logistica: String, at: Long): String =
        "${slug(logistica)}-${Fmt.ymd(at)}-"

    fun build(logistica: String, at: Long, seq: Int): String =
        prefix(logistica, at) + seq.toString().padStart(3, '0')
}

object Fmt {
    private fun f(p: String) = SimpleDateFormat(p, Locale("es", "AR"))
    fun date(t: Long): String = f("dd/MM/yyyy").format(Date(t))
    fun time(t: Long): String = f("HH:mm:ss").format(Date(t))
    fun hm(t: Long): String = f("HH:mm").format(Date(t))
    fun dateTime(t: Long): String = f("dd/MM/yyyy HH:mm").format(Date(t))
    fun ymd(t: Long): String = f("yyyyMMdd").format(Date(t))
    fun year(t: Long): String = f("yyyy").format(Date(t))
    fun month(t: Long): String = f("MM").format(Date(t))
    fun isoDay(t: Long): String = f("yyyy-MM-dd").format(Date(t))
}

object Catalog {
    val EMPRESAS = listOf("ProntoTec", "DE-ELECTRO")
    val LOGISTICAS_INICIALES = listOf(
        "EnvíoPack", "EnvíoPack FLEX", "Loginter", "Frávega", "Urbano",
        "Andreani", "OCA", "Ocasa", "Correo Argentino", "Otras",
    )
}
