package com.lilus.despachos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class PureLogicTest {

    private fun dispatch() = Dispatch(
        "u1", "ENVIOPACK-20261022-001", "ProntoTec", "EnvíoPack", "Juan", 0L, 1L,
        DispatchStatus.CLOSED, SyncStatus.PENDING, null, 0, null, null, null, null,
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { out[e.name] = z.readBytes().toString(Charsets.UTF_8); e = z.nextEntry }
        }
        return out
    }

    @Test fun xlsxGeneraUnaFilaPorBultoConCodigosComoTexto() {
        val items = listOf(
            ScanItem(1, "u1", "000123", 1_761_150_000_000, false),
            ScanItem(2, "u1", "EP013434737N'01", 1_761_150_001_000, false),
            ScanItem(3, "u1", "EP013434737N'02", 1_761_150_002_000, false),
        )
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(bos, "ENVIOPACK-20261022-001", XlsxWriter.buildRows(dispatch(), items))
        val files = unzip(bos.toByteArray())
        assertTrue(files.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml")))
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<t xml:space=\"preserve\">000123</t>"))           // texto con ceros
        assertTrue(sheet.contains("EP013434737N&apos;01"))
        assertTrue(sheet.contains("EP013434737N&apos;02"))
        assertTrue(sheet.contains("<autoFilter ref=\"A1:G4\"/>"))
        assertEquals(4, Regex("<row ").findAll(sheet).count())                      // encabezado + 3, sin filas vacías
        assertFalse(sheet.contains("SUM("))                                          // sin totales manuales
        XlsxWriter.HEADERS.forEach { assertTrue(sheet.contains(XlsxWriter.esc(it))) }
    }

    @Test fun paginacionDelPdf() {
        val first = PdfLayout.rowsFirstPage()
        val other = PdfLayout.rowsOtherPage()
        val sig = PdfLayout.signatureRows()
        assertEquals(1, PdfLayout.plan(0).size)
        assertEquals(1, PdfLayout.plan(first - sig).size)                 // entra todo con firmas
        assertEquals(2, PdfLayout.plan(first).size)                       // firmas en hoja nueva
        val big = PdfLayout.plan(500)
        assertEquals(500, big.sumOf { it.count() })                       // ninguna fila se pierde
        assertEquals(0, big.first().first)
        assertEquals(499, big.filter { !it.isEmpty() }.last().last)
        big.drop(1).filter { !it.isEmpty() }.forEach { assertTrue(it.count() <= other) }
    }

    @Test fun debouncerEvitaLecturasRepetidas() {
        val d = ScanDebouncer(sameCodeMs = 2500, anyCodeMs = 700)
        assertTrue(d.accept("A", 0))
        assertFalse(d.accept("A", 300))      // sigue frente a la cámara
        assertFalse(d.accept("A", 2700))     // se extendió la espera porque se lo siguió viendo
        assertTrue(d.accept("A", 6000))      // se apartó y volvió: se informa (el repo lo marcará duplicado)
        assertFalse(d.accept("B", 6200))     // pausa mínima entre lecturas
        assertTrue(d.accept("B", 6800))
    }

    @Test fun idDeDespachoYLimpiezaDeCodigos() {
        assertEquals("ENVIOPACKFLEX", DispatchIds.slug("EnvíoPack FLEX"))
        assertEquals("CORREOARGENTINO", DispatchIds.slug("Correo Argentino"))
        assertEquals("FRAVEGA", DispatchIds.slug("Frávega"))
        assertEquals("0012", CodeRules.clean(" 0012\r\n"))
        assertEquals("EP013434737N'01", CodeRules.clean("EP013434737N'01"))
        assertEquals(null, CodeRules.clean(" \n "))
    }

    @Test fun soloSeAceptaHttps() {
        assertTrue(Settings.isValidBackendUrl("https://script.google.com/macros/s/abc/exec"))
        assertFalse(Settings.isValidBackendUrl("http://script.google.com/macros/s/abc/exec"))
        assertFalse(Settings.isValidBackendUrl(""))
    }
}
