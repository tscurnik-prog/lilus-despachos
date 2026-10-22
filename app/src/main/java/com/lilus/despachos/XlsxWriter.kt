package com.lilus.despachos

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generador mínimo de XLSX (Office Open XML) sin librerías externas.
 * Todas las celdas de texto se guardan como TEXTO (inlineStr): los códigos conservan
 * ceros iniciales y sufijos. Encabezado con formato, filtros, panel inmovilizado.
 * No escribe totales manuales.
 */
object XlsxWriter {

    val HEADERS = listOf("Número", "Fecha", "Hora", "Empresa", "Logística", "Operario", "Código de barras")
    private val WIDTHS = listOf(9.0, 12.0, 10.0, 14.0, 18.0, 18.0, 34.0)

    fun buildRows(d: Dispatch, items: List<ScanItem>): List<List<String>> =
        items.mapIndexed { i, it ->
            listOf((i + 1).toString(), Fmt.date(it.scannedAt), Fmt.time(it.scannedAt), d.empresa, d.logistica, d.operario, it.code)
        }

    fun write(out: OutputStream, sheetName: String, rows: List<List<String>>) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheetName, rows.size + 1))
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(rows))
        }
    }

    private fun sheet(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        val lastRow = rows.size + 1
        sb.append("""<dimension ref="A1:G$lastRow"/>""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("<cols>")
        WIDTHS.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        sb.append("""<row r="1">""")
        HEADERS.forEachIndexed { c, h -> sb.append(textCell(c, 1, h, style = 1)) }
        sb.append("</row>")
        rows.forEachIndexed { i, row ->
            val r = i + 2
            sb.append("""<row r="$r">""")
            row.forEachIndexed { c, v ->
                if (c == 0) sb.append("""<c r="${col(c)}$r" s="3"><v>${esc(v)}</v></c>""")
                else sb.append(textCell(c, r, v, style = 2))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("""<autoFilter ref="A1:G$lastRow"/>""")
        sb.append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/>""")
        sb.append("""<pageSetup paperSize="9" orientation="portrait"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun textCell(c: Int, r: Int, v: String, style: Int) =
        """<c r="${col(c)}$r" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(v)}</t></is></c>"""

    private fun col(i: Int) = ('A' + i).toString()

    fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                // XML 1.0 no admite caracteres de control: se representan con la notación _xHHHH_ de Excel
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' ->
                    sb.append("_x").append(ch.code.toString(16).padStart(4, '0').uppercase()).append('_')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun workbook(sheetName: String, lastRow: Int): String {
        val safe = esc(sheetName.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31).ifBlank { "Despacho" })
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="$safe" sheetId="1" r:id="rId1"/></sheets>""" +
            """<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'$safe'!${'$'}A${'$'}1:${'$'}G${'$'}$lastRow</definedName></definedNames>""" +
            """</workbook>"""
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    // Estilos: 0 normal · 1 encabezado (negrita blanca sobre azul, borde) · 2 texto con borde · 3 número con borde, centrado
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B2545"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="49" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/><xf numFmtId="1" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
