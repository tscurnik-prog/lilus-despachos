package com.lilus.despachos

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class LilusDbHelper(context: Context, name: String? = DB_NAME) :
    SQLiteOpenHelper(context, name, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE dispatch (
                uuid TEXT PRIMARY KEY,
                display_id TEXT,
                empresa TEXT NOT NULL,
                logistica TEXT NOT NULL,
                operario TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                closed_at INTEGER,
                status TEXT NOT NULL,
                sync_status TEXT NOT NULL,
                sync_error TEXT,
                duplicates INTEGER NOT NULL DEFAULT 0,
                pdf_path TEXT,
                xlsx_path TEXT,
                drive_url TEXT,
                synced_at INTEGER
            )"""
        )
        db.execSQL(
            """CREATE TABLE item (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dispatch_uuid TEXT NOT NULL REFERENCES dispatch(uuid),
                code TEXT NOT NULL,
                scanned_at INTEGER NOT NULL,
                removed INTEGER NOT NULL DEFAULT 0
            )"""
        )
        // Un mismo código no puede estar activo dos veces en el mismo despacho (defensa extra).
        db.execSQL("CREATE UNIQUE INDEX ux_item_active ON item(dispatch_uuid, code) WHERE removed = 0")
        db.execSQL("CREATE INDEX ix_item_code ON item(code)")
        db.execSQL(
            """CREATE TABLE audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dispatch_uuid TEXT NOT NULL,
                action TEXT NOT NULL,
                code TEXT,
                at INTEGER NOT NULL,
                operario TEXT NOT NULL,
                detail TEXT
            )"""
        )
        db.execSQL("CREATE TABLE logistic (name TEXT PRIMARY KEY, position INTEGER NOT NULL)")
        Catalog.LOGISTICAS_INICIALES.forEachIndexed { i, n ->
            db.insert("logistic", null, ContentValues().apply { put("name", n); put("position", i) })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: sin migraciones todavía. NUNCA borrar tablas: los datos del depósito no se pierden.
    }

    companion object {
        const val DB_NAME = "lilus_despachos.db"
        const val DB_VERSION = 1
    }
}

/**
 * Toda la persistencia local. Cada código se guarda en el momento en que se confirma,
 * dentro de una transacción, así que cerrar la app, bloquear el celular o reiniciarlo
 * no pierde lecturas.
 */
class DispatchRepository(private val helper: LilusDbHelper) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------- Despachos ----------

    @Synchronized
    fun getOpenDispatch(): Dispatch? =
        queryDispatches("WHERE d.status = ? ORDER BY d.started_at DESC LIMIT 1", arrayOf(DispatchStatus.OPEN.name))
            .firstOrNull()

    fun getDispatch(uuid: String): Dispatch? =
        queryDispatches("WHERE d.uuid = ?", arrayOf(uuid)).firstOrNull()

    fun listDispatches(limit: Int = 300): List<Dispatch> =
        queryDispatches("ORDER BY d.started_at DESC LIMIT $limit", emptyArray())

    fun dispatchesToSync(): List<Dispatch> =
        queryDispatches(
            "WHERE d.status = ? AND d.sync_status IN (?, ?) ORDER BY d.closed_at",
            arrayOf(DispatchStatus.CLOSED.name, SyncStatus.PENDING.name, SyncStatus.ERROR.name)
        )

    /** Cada operario trabaja un despacho por vez en su celular. */
    @Synchronized
    fun startDispatch(operario: String, empresa: String, logistica: String, now: Long): Dispatch {
        require(operario.isNotBlank()) { "Falta el nombre del operario" }
        getOpenDispatch()?.let { throw IllegalStateException("Ya hay un despacho en curso") }
        val uuid = UUID.randomUUID().toString()
        db.insertOrThrow("dispatch", null, ContentValues().apply {
            put("uuid", uuid)
            put("empresa", empresa)
            put("logistica", logistica)
            put("operario", operario.trim())
            put("started_at", now)
            put("status", DispatchStatus.OPEN.name)
            put("sync_status", SyncStatus.NONE.name)
        })
        audit(uuid, "INICIO", null, now, operario.trim(), "$empresa / $logistica")
        return getDispatch(uuid)!!
    }

    // ---------- Escaneo ----------

    @Synchronized
    fun registerCode(uuid: String, raw: String?, now: Long): ScanResult {
        val code = CodeRules.clean(raw) ?: return ScanResult.Unreadable
        val d = getDispatch(uuid) ?: return ScanResult.DispatchClosed
        if (!d.isOpen) return ScanResult.DispatchClosed

        val database = db
        database.beginTransaction()
        try {
            // Comparación EXACTA de texto: "EP013434737N'01" y "EP013434737N'02" son distintos.
            val previous = database.rawQuery(
                "SELECT scanned_at FROM item WHERE dispatch_uuid = ? AND code = ? AND removed = 0 LIMIT 1",
                arrayOf(uuid, code)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

            val result: ScanResult = if (previous != null) {
                database.execSQL("UPDATE dispatch SET duplicates = duplicates + 1 WHERE uuid = ?", arrayOf<Any>(uuid))
                ScanResult.Duplicate(code, previous, countItemsTx(database, uuid))
            } else {
                try {
                    database.insertOrThrow("item", null, ContentValues().apply {
                        put("dispatch_uuid", uuid)
                        put("code", code) // siempre TEXTO
                        put("scanned_at", now)
                        put("removed", 0)
                    })
                    ScanResult.Registered(code, countItemsTx(database, uuid))
                } catch (e: SQLiteConstraintException) {
                    ScanResult.Duplicate(code, now, countItemsTx(database, uuid))
                }
            }
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    private fun countItemsTx(database: SQLiteDatabase, uuid: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM item WHERE dispatch_uuid = ? AND removed = 0", arrayOf(uuid))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    /** El contador sale SIEMPRE de los códigos realmente guardados. */
    fun countItems(uuid: String): Int = countItemsTx(db, uuid)

    fun listItems(uuid: String): List<ScanItem> =
        db.rawQuery(
            "SELECT id, dispatch_uuid, code, scanned_at, removed FROM item WHERE dispatch_uuid = ? AND removed = 0 ORDER BY scanned_at, id",
            arrayOf(uuid)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    fun lastItem(uuid: String): ScanItem? =
        db.rawQuery(
            "SELECT id, dispatch_uuid, code, scanned_at, removed FROM item WHERE dispatch_uuid = ? AND removed = 0 ORDER BY scanned_at DESC, id DESC LIMIT 1",
            arrayOf(uuid)
        ).use { c -> if (c.moveToFirst()) c.toItem() else null }

    /** Quitar un código cargado por error. Solo con el despacho abierto; queda en auditoría. */
    @Synchronized
    fun removeItem(uuid: String, itemId: Long, operario: String, now: Long): Boolean {
        val d = getDispatch(uuid) ?: return false
        if (!d.isOpen) return false
        val database = db
        database.beginTransaction()
        try {
            val code = database.rawQuery(
                "SELECT code FROM item WHERE id = ? AND dispatch_uuid = ? AND removed = 0",
                arrayOf(itemId.toString(), uuid)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return false
            database.execSQL("UPDATE item SET removed = 1 WHERE id = ?", arrayOf<Any>(itemId))
            auditTx(database, uuid, "QUITAR_CODIGO", code, now, operario, "Quitado manualmente antes del cierre")
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    // ---------- Cierre ----------

    @Synchronized
    fun closeDispatch(uuid: String, now: Long): Dispatch {
        val d = getDispatch(uuid) ?: throw IllegalStateException("No se encontró el despacho")
        if (!d.isOpen) return d
        val prefix = DispatchIds.prefix(d.logistica, now)
        val database = db
        database.beginTransaction()
        try {
            val maxSeq = database.rawQuery(
                "SELECT display_id FROM dispatch WHERE display_id LIKE ?", arrayOf("$prefix%")
            ).use { c ->
                var m = 0
                while (c.moveToNext()) {
                    c.getString(0).removePrefix(prefix).toIntOrNull()?.let { if (it > m) m = it }
                }
                m
            }
            val displayId = DispatchIds.build(d.logistica, now, maxSeq + 1)
            database.update("dispatch", ContentValues().apply {
                put("display_id", displayId)
                put("closed_at", now)
                put("status", DispatchStatus.CLOSED.name)
                put("sync_status", SyncStatus.PENDING.name)
            }, "uuid = ?", arrayOf(uuid))
            auditTx(database, uuid, "CIERRE", null, now, d.operario, "$displayId · ${countItemsTx(database, uuid)} bultos")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return getDispatch(uuid)!!
    }

    fun setDocuments(uuid: String, pdfPath: String, xlsxPath: String) {
        db.update("dispatch", ContentValues().apply {
            put("pdf_path", pdfPath); put("xlsx_path", xlsxPath)
        }, "uuid = ?", arrayOf(uuid))
    }

    fun markSynced(uuid: String, driveUrl: String?, at: Long) {
        db.update("dispatch", ContentValues().apply {
            put("sync_status", SyncStatus.SYNCED.name)
            put("sync_error", null as String?)
            put("drive_url", driveUrl)
            put("synced_at", at)
        }, "uuid = ?", arrayOf(uuid))
    }

    fun markSyncError(uuid: String, message: String) {
        db.update("dispatch", ContentValues().apply {
            put("sync_status", SyncStatus.ERROR.name)
            put("sync_error", message)
        }, "uuid = ? AND sync_status != ?", arrayOf(uuid, SyncStatus.SYNCED.name))
    }

    // ---------- Búsqueda ----------

    /** Busca un código (o parte) en todos los despachos guardados en este celular. */
    fun searchCode(query: String): List<CodeHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val items = db.rawQuery(
            "SELECT id, dispatch_uuid, code, scanned_at, removed FROM item WHERE removed = 0 AND code LIKE ? ESCAPE '\\' ORDER BY scanned_at DESC LIMIT 200",
            arrayOf(like)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }
        val cache = HashMap<String, Dispatch?>()
        return items.mapNotNull { it ->
            val d = cache.getOrPut(it.dispatchUuid) { getDispatch(it.dispatchUuid) } ?: return@mapNotNull null
            CodeHit(d, it)
        }
    }

    // ---------- Logísticas ----------

    fun logistics(): List<String> =
        db.rawQuery("SELECT name FROM logistic ORDER BY position, name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun addLogistic(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (logistics().any { it.equals(n, ignoreCase = true) }) return false
        val pos = db.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM logistic", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        return db.insert("logistic", null, ContentValues().apply { put("name", n); put("position", pos) }) != -1L
    }

    // ---------- Auditoría ----------

    fun auditLog(uuid: String): List<AuditEntry> =
        db.rawQuery(
            "SELECT id, dispatch_uuid, action, code, at, operario, detail FROM audit WHERE dispatch_uuid = ? ORDER BY at, id",
            arrayOf(uuid)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    AuditEntry(c.getLong(0), c.getString(1), c.getString(2), c.getStringOrNull(3), c.getLong(4), c.getString(5), c.getStringOrNull(6))
                )
            }
        }

    private fun audit(uuid: String, action: String, code: String?, at: Long, operario: String, detail: String?) =
        auditTx(db, uuid, action, code, at, operario, detail)

    private fun auditTx(database: SQLiteDatabase, uuid: String, action: String, code: String?, at: Long, operario: String, detail: String?) {
        database.insert("audit", null, ContentValues().apply {
            put("dispatch_uuid", uuid); put("action", action); put("code", code)
            put("at", at); put("operario", operario); put("detail", detail)
        })
    }

    // ---------- Mapeos ----------

    private fun queryDispatches(tail: String, args: Array<String>): List<Dispatch> =
        db.rawQuery(
            """SELECT d.uuid, d.display_id, d.empresa, d.logistica, d.operario, d.started_at, d.closed_at,
                      d.status, d.sync_status, d.sync_error, d.duplicates, d.pdf_path, d.xlsx_path, d.drive_url, d.synced_at,
                      (SELECT COUNT(*) FROM item i WHERE i.dispatch_uuid = d.uuid AND i.removed = 0)
               FROM dispatch d $tail""",
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Dispatch(
                        uuid = c.getString(0),
                        displayId = c.getStringOrNull(1),
                        empresa = c.getString(2),
                        logistica = c.getString(3),
                        operario = c.getString(4),
                        startedAt = c.getLong(5),
                        closedAt = c.getLongOrNull(6),
                        status = DispatchStatus.valueOf(c.getString(7)),
                        syncStatus = SyncStatus.valueOf(c.getString(8)),
                        syncError = c.getStringOrNull(9),
                        duplicates = c.getInt(10),
                        pdfPath = c.getStringOrNull(11),
                        xlsxPath = c.getStringOrNull(12),
                        driveUrl = c.getStringOrNull(13),
                        syncedAt = c.getLongOrNull(14),
                        itemCount = c.getInt(15),
                    )
                )
            }
        }

    private fun Cursor.toItem() = ScanItem(getLong(0), getString(1), getString(2), getLong(3), getInt(4) != 0)
    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
}
