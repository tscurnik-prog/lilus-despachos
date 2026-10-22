package com.lilus.despachos

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Paginación del comprobante (lógica pura, testeable sin Android). */
object PdfLayout {
    const val PAGE_W = 595 // A4 en puntos
    const val PAGE_H = 842
    const val MARGIN = 36f
    const val ROW_H = 16f
    const val FIRST_HEADER_H = 190f
    const val OTHER_HEADER_H = 60f
    const val TABLE_HEADER_H = 20f
    const val PAGE_FOOTER_H = 28f
    const val SIGNATURE_H = 170f

    fun rowsFirstPage(): Int = ((PAGE_H - 2 * MARGIN - FIRST_HEADER_H - TABLE_HEADER_H - PAGE_FOOTER_H) / ROW_H).toInt()
    fun rowsOtherPage(): Int = ((PAGE_H - 2 * MARGIN - OTHER_HEADER_H - TABLE_HEADER_H - PAGE_FOOTER_H) / ROW_H).toInt()
    fun signatureRows(): Int = kotlin.math.ceil(SIGNATURE_H / ROW_H).toInt()

    /**
     * Devuelve, por página, el rango de índices de filas (0-based) que se imprimen.
     * La última página siempre tiene lugar para el bloque de firmas; si no entra,
     * se agrega una página final solo con firmas.
     */
    fun plan(total: Int, first: Int = rowsFirstPage(), other: Int = rowsOtherPage(), sig: Int = signatureRows()): List<IntRange> {
        val pages = mutableListOf<IntRange>()
        var start = 0
        var capacity = first
        while (start < total) {
            val end = minOf(total, start + capacity)
            pages += start until end
            start = end
            capacity = other
        }
        if (pages.isEmpty()) {
            pages += IntRange.EMPTY
            return pages
        }
        val lastCapacity = if (pages.size == 1) first else other
        val lastCount = pages.last().count()
        if (lastCount + sig > lastCapacity) pages += IntRange.EMPTY
        return pages
    }
}

object PdfReport {

    fun write(out: OutputStream, d: Dispatch, items: List<ScanItem>) {
        val doc = PdfDocument()
        try {
            val pages = PdfLayout.plan(items.size)
            pages.forEachIndexed { index, range ->
                val info = PdfDocument.PageInfo.Builder(PdfLayout.PAGE_W, PdfLayout.PAGE_H, index + 1).create()
                val page = doc.startPage(info)
                drawPage(page.canvas, d, items, range, index, pages.size)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private val navy = Color.rgb(0x0B, 0x25, 0x45)
    private val grey = Color.rgb(0x60, 0x60, 0x60)
    private val lineGrey = Color.rgb(0xC8, 0xC8, 0xC8)
    private val zebra = Color.rgb(0xF2, 0xF4, 0xF7)

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, mono: Boolean = false) = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun drawPage(c: android.graphics.Canvas, d: Dispatch, items: List<ScanItem>, range: IntRange, pageIndex: Int, pageCount: Int) {
        val left = PdfLayout.MARGIN
        val right = PdfLayout.PAGE_W - PdfLayout.MARGIN
        var y = PdfLayout.MARGIN
        val total = items.size

        if (pageIndex == 0) {
            // Banda de título
            val band = Paint().apply { color = navy; style = Paint.Style.FILL }
            c.drawRect(left, y, right, y + 44f, band)
            c.drawText("LILUS — CONTROL DE DESPACHOS", left + 12f, y + 29f, paint(18f, true, Color.WHITE))
            y += 64f
            val lab = paint(10f, color = grey)
            val v = paint(12f, true)
            val col2 = left + (right - left) / 2f
            fun field(x: Float, yy: Float, label: String, value: String) {
                v.textSize = 12f
                c.drawText(label.uppercase(), x, yy, lab)
                c.drawText(fit(value, v, (right - left) / 2f - 12f), x, yy + 15f, v)
            }
            field(left, y, "Empresa", d.empresa)
            field(col2, y, "Logística", d.logistica)
            y += 36f
            field(left, y, "Fecha", Fmt.date(d.closedAt ?: d.startedAt))
            field(col2, y, "Operario", d.operario)
            y += 36f
            field(left, y, "Número de despacho", d.displayId ?: "(sin cerrar)")
            val horas = Fmt.hm(d.startedAt) + " a " + (d.closedAt?.let { Fmt.hm(it) } ?: "--:--")
            field(col2, y, "Horario de carga", horas)
            y += 30f
            val box = Paint().apply { color = navy; style = Paint.Style.STROKE; strokeWidth = 1.5f }
            c.drawRect(left, y, right, y + 30f, box)
            c.drawText("TOTAL DE BULTOS: $total", left + 12f, y + 21f, paint(15f, true, navy))
            y = PdfLayout.MARGIN + PdfLayout.FIRST_HEADER_H
        } else {
            c.drawText("LILUS — CONTROL DE DESPACHOS", left, y + 14f, paint(12f, true, navy))
            c.drawText(
                "${d.displayId ?: ""} · ${d.logistica} · ${Fmt.date(d.closedAt ?: d.startedAt)} · Total: $total bultos",
                left, y + 32f, paint(10f, color = grey)
            )
            y = PdfLayout.MARGIN + PdfLayout.OTHER_HEADER_H
        }

        // Tabla
        val colN = left
        val colCode = left + 52f
        val colHora = right - 70f
        if (!range.isEmpty()) {
            val head = Paint().apply { color = navy; style = Paint.Style.FILL }
            c.drawRect(left, y, right, y + PdfLayout.TABLE_HEADER_H, head)
            val hp = paint(10f, true, Color.WHITE)
            c.drawText("N°", colN + 8f, y + 14f, hp)
            c.drawText("CÓDIGO DE BARRAS", colCode, y + 14f, hp)
            c.drawText("HORA", colHora, y + 14f, hp)
            y += PdfLayout.TABLE_HEADER_H
            val rp = paint(11f)
            val cp = paint(11f, mono = true)
            val lp = Paint().apply { color = lineGrey; strokeWidth = 0.5f }
            val zp = Paint().apply { color = zebra; style = Paint.Style.FILL }
            for (i in range) {
                if (i % 2 == 1) c.drawRect(left, y, right, y + PdfLayout.ROW_H, zp)
                val it = items[i]
                cp.textSize = 11f
                c.drawText((i + 1).toString(), colN + 8f, y + 12f, rp)
                c.drawText(fit(printable(it.code), cp, colHora - colCode - 8f), colCode, y + 12f, cp)
                c.drawText(Fmt.time(it.scannedAt), colHora, y + 12f, rp)
                y += PdfLayout.ROW_H
                c.drawLine(left, y, right, y, lp)
            }
        } else if (total == 0 && pageIndex == 0) {
            c.drawText("Este despacho no tiene bultos registrados.", left, y + 14f, paint(11f, color = grey))
            y += 24f
        }

        // Bloque de firmas en la última página
        if (pageIndex == pageCount - 1) {
            y += 24f
            c.drawText("RECEPCIÓN DEL TRANSPORTISTA", left, y, paint(11f, true, navy))
            y += 10f
            val sl = Paint().apply { color = Color.BLACK; strokeWidth = 0.8f }
            val lp = paint(10f, color = grey)
            val half = (right - left) / 2f
            fun signLine(x: Float, yy: Float, w: Float, label: String) {
                c.drawLine(x, yy, x + w, yy, sl)
                c.drawText(label, x, yy + 12f, lp)
            }
            y += 40f
            signLine(left, y, half - 20f, "Firma del transportista")
            signLine(left + half, y, half, "Aclaración")
            y += 42f
            signLine(left, y, half - 20f, "DNI")
            signLine(left + half, y, half, "Patente")
            y += 42f
            signLine(left, y, half - 20f, "Hora de retiro")
            c.drawText("Bultos entregados: $total", left + half, y, paint(11f, true))
        }

        // Pie de página
        val fp = paint(9f, color = grey)
        val footY = PdfLayout.PAGE_H - PdfLayout.MARGIN + 8f
        c.drawText("Página ${pageIndex + 1} de $pageCount", right - fp.measureText("Página ${pageIndex + 1} de $pageCount"), footY, fp)
        c.drawText("Generado por LILUS DESPACHOS", left, footY, fp)
    }

    /** Muestra caracteres de control (p. ej. separador GS1) de forma visible en el papel. */
    private fun printable(s: String) = buildString {
        for (ch in s) if (ch.isISOControl()) append("<").append(ch.code.toString(16).uppercase()).append(">") else append(ch)
    }

    private fun fit(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        // Para códigos muy largos se reduce la letra en vez de cortar el código
        var size = p.textSize
        val q = Paint(p)
        while (size > 6f) {
            size -= 0.5f
            q.textSize = size
            if (q.measureText(text) <= maxWidth) {
                p.textSize = size
                return text
            }
        }
        p.textSize = size
        return text
    }
}
