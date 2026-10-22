package com.lilus.despachos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Abrir, compartir e imprimir comprobantes con las apps del celular. */
object FileActions {

    private const val PDF = "application/pdf"
    private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun uri(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun mimeOf(f: File) = if (f.name.endsWith(".pdf", true)) PDF else XLSX

    fun open(ctx: Context, path: String?) {
        val f = path?.let(::File)?.takeIf { it.exists() } ?: return toast(ctx, "El archivo no está disponible. Generá los comprobantes de nuevo.")
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast(ctx, if (mimeOf(f) == PDF) "No hay una app para ver PDF. Usá \"Compartir\" o \"Imprimir\"." else "No hay una app para abrir Excel. Usá \"Compartir\".")
        }
    }

    fun share(ctx: Context, vararg paths: String?) {
        val files = paths.mapNotNull { p -> p?.let(::File)?.takeIf { it.exists() } }
        if (files.isEmpty()) return toast(ctx, "No hay archivos para compartir.")
        val i = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeOf(files[0])).putExtra(Intent.EXTRA_STREAM, uri(ctx, files[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(ctx, it) }))
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(i, "Compartir comprobante").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "No hay apps para compartir.")
        }
    }

    fun print(ctx: Context, path: String?, jobName: String) {
        val f = path?.let(::File)?.takeIf { it.exists() } ?: return toast(ctx, "El PDF no está disponible.")
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return toast(ctx, "Este celular no permite imprimir.")
        val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        try {
            pm.print(jobName, PdfFileAdapter(f, jobName), attrs)
        } catch (e: RuntimeException) {
            toast(ctx, "No se pudo abrir la impresión.")
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    private class PdfFileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                file.inputStream().use { input ->
                    FileOutputStream(destination.fileDescriptor).use { out -> input.copyTo(out) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed("No se pudo enviar el PDF a la impresora")
            }
        }
    }
}
