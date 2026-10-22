package com.lilus.despachos

import android.content.Context
import java.io.File
import java.io.IOException

class StorageFullException(cause: Throwable) : IOException("Sin espacio de almacenamiento", cause)

/** Genera y guarda en el celular el PDF y el XLSX de un despacho cerrado. */
object Documents {

    fun dir(context: Context): File = File(context.filesDir, "despachos").apply { mkdirs() }

    fun generate(context: Context, d: Dispatch, items: List<ScanItem>): Pair<File, File> {
        val name = d.displayId ?: d.uuid
        val folder = File(dir(context), name).apply { mkdirs() }
        val pdf = File(folder, "$name.pdf")
        val xlsx = File(folder, "$name.xlsx")
        try {
            writeAtomic(pdf) { out -> PdfReport.write(out, d, items) }
            writeAtomic(xlsx) { out -> XlsxWriter.write(out, name, XlsxWriter.buildRows(d, items)) }
        } catch (e: IOException) {
            if (isNoSpace(e) || context.filesDir.usableSpace < 1_000_000L) throw StorageFullException(e)
            throw e
        }
        return pdf to xlsx
    }

    private fun writeAtomic(target: File, block: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { block(it) }
        if (tmp.length() == 0L) throw IOException("Archivo vacío")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("No se pudo guardar ${target.name}")
    }

    private fun isNoSpace(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space", ignoreCase = true) == true) return true
            t = t.cause
        }
        return false
    }
}
