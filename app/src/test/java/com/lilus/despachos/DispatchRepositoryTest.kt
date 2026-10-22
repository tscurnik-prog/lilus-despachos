package com.lilus.despachos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DispatchRepositoryTest {

    private lateinit var ctx: Context
    private lateinit var helper: LilusDbHelper
    private lateinit var repo: DispatchRepository
    private val dbName = "test_despachos.db"
    private val t0 = 1_761_150_000_000L // 22/10/2025 aprox.

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
        helper = LilusDbHelper(ctx, dbName)
        repo = DispatchRepository(helper)
    }

    @After fun tearDown() { helper.close(); ctx.deleteDatabase(dbName) }

    private fun start() = repo.startDispatch("Juan", "ProntoTec", "EnvíoPack", t0)

    @Test fun registraCodigoValido() {
        val d = start()
        val r = repo.registerCode(d.uuid, "EP013434737N'01", t0 + 1000)
        assertTrue(r is ScanResult.Registered)
        assertEquals(1, (r as ScanResult.Registered).count)
        assertEquals("EP013434737N'01", r.code)
        assertEquals(1, repo.countItems(d.uuid))
    }

    @Test fun rechazaDuplicadoYNoSumaAlContador() {
        val d = start()
        repo.registerCode(d.uuid, "ABC123", t0 + 1000)
        val r = repo.registerCode(d.uuid, "ABC123", t0 + 5000)
        assertTrue(r is ScanResult.Duplicate)
        r as ScanResult.Duplicate
        assertEquals(t0 + 1000, r.firstScannedAt)   // hora del registro original
        assertEquals(1, r.count)
        assertEquals(1, repo.countItems(d.uuid))
        assertEquals(1, repo.getDispatch(d.uuid)!!.duplicates)
    }

    @Test fun conservaCerosInicialesYGuardaTexto() {
        val d = start()
        repo.registerCode(d.uuid, "000123", t0)
        repo.registerCode(d.uuid, "123", t0 + 1)
        val codes = repo.listItems(d.uuid).map { it.code }
        assertEquals(listOf("000123", "123"), codes)   // no se convierte a número, no son duplicados
    }

    @Test fun distingueCodigosConSufijosDiferentes() {
        val d = start()
        val a = repo.registerCode(d.uuid, "EP013434737N'01", t0)
        val b = repo.registerCode(d.uuid, "EP013434737N'02", t0 + 1)
        assertTrue(a is ScanResult.Registered)
        assertTrue(b is ScanResult.Registered)
        assertEquals(2, repo.countItems(d.uuid))
    }

    @Test fun soloLimpiaEspaciosExternosNoContenido() {
        val d = start()
        repo.registerCode(d.uuid, "  EP-01 \n", t0)
        assertEquals("EP-01", repo.listItems(d.uuid).single().code)
        assertTrue(repo.registerCode(d.uuid, "   ", t0) is ScanResult.Unreadable)
        assertTrue(repo.registerCode(d.uuid, null, t0) is ScanResult.Unreadable)
    }

    @Test fun persisteYRecuperaDespachoPendienteTrasReabrir() {
        val d = start()
        repo.registerCode(d.uuid, "A1", t0)
        repo.registerCode(d.uuid, "A2", t0 + 1)
        helper.close() // simula cierre de la app / reinicio

        val helper2 = LilusDbHelper(ctx, dbName)
        val repo2 = DispatchRepository(helper2)
        val open = repo2.getOpenDispatch()
        assertNotNull(open)
        assertEquals(d.uuid, open!!.uuid)
        assertEquals(2, repo2.countItems(d.uuid))
        // los duplicados se detectan aunque se haya cerrado la app
        assertTrue(repo2.registerCode(d.uuid, "A1", t0 + 10) is ScanResult.Duplicate)
        helper2.close()
        helper = LilusDbHelper(ctx, dbName) // para tearDown
    }

    @Test fun recuentoCorrectoAlQuitarCodigoConAuditoria() {
        val d = start()
        repo.registerCode(d.uuid, "X1", t0)
        repo.registerCode(d.uuid, "X2", t0 + 1)
        val item = repo.listItems(d.uuid).first { it.code == "X1" }
        assertTrue(repo.removeItem(d.uuid, item.id, "Juan", t0 + 2))
        assertEquals(1, repo.countItems(d.uuid))
        assertEquals("QUITAR_CODIGO", repo.auditLog(d.uuid).last().action)
        // se puede volver a escanear el código quitado
        assertTrue(repo.registerCode(d.uuid, "X1", t0 + 3) is ScanResult.Registered)
        assertEquals(2, repo.countItems(d.uuid))
    }

    @Test fun cierreAsignaIdYBloqueaModificaciones() {
        val d = start()
        repo.registerCode(d.uuid, "C1", t0)
        val closed = repo.closeDispatch(d.uuid, t0 + 60_000)
        assertFalse(closed.isOpen)
        assertEquals(SyncStatus.PENDING, closed.syncStatus)
        assertTrue(closed.displayId!!.matches(Regex("ENVIOPACK-\\d{8}-001")))
        assertTrue(repo.registerCode(d.uuid, "C2", t0 + 61_000) is ScanResult.DispatchClosed)
        val item = repo.listItems(d.uuid).single()
        assertFalse(repo.removeItem(d.uuid, item.id, "Juan", t0))
        assertNull(repo.getOpenDispatch())
        // cerrar dos veces no cambia nada
        assertEquals(closed.displayId, repo.closeDispatch(d.uuid, t0 + 99_000).displayId)
    }

    @Test fun numeracionCorrelativaPorLogisticaYDia() {
        val a = start(); repo.closeDispatch(a.uuid, t0 + 1)
        val b = start(); val cb = repo.closeDispatch(b.uuid, t0 + 2)
        assertTrue(cb.displayId!!.endsWith("-002"))
    }

    @Test(expected = IllegalStateException::class)
    fun unSoloDespachoEnCursoPorCelular() {
        start(); start()
    }

    @Test fun estadoDeSincronizacion() {
        val d = start(); repo.closeDispatch(d.uuid, t0 + 1)
        assertEquals(1, repo.dispatchesToSync().size)
        repo.markSyncError(d.uuid, "Sin conexión")
        assertEquals(SyncStatus.ERROR, repo.getDispatch(d.uuid)!!.syncStatus)
        assertEquals(1, repo.dispatchesToSync().size)
        repo.markSynced(d.uuid, "https://drive.google.com/x", t0 + 5)
        assertEquals(SyncStatus.SYNCED, repo.getDispatch(d.uuid)!!.syncStatus)
        assertEquals(0, repo.dispatchesToSync().size)
        // un error tardío no pisa un envío ya confirmado
        repo.markSyncError(d.uuid, "tarde")
        assertEquals(SyncStatus.SYNCED, repo.getDispatch(d.uuid)!!.syncStatus)
    }

    @Test fun busquedaPorCodigo() {
        val d = start()
        repo.registerCode(d.uuid, "EP013434737N'01", t0)
        repo.registerCode(d.uuid, "ZZZ", t0)
        val hits = repo.searchCode("34737")
        assertEquals(1, hits.size)
        assertEquals("EP013434737N'01", hits[0].item.code)
        assertEquals(0, repo.searchCode("%").size)
    }

    @Test fun logisticasInicialesYAlta() {
        assertTrue(repo.logistics().containsAll(Catalog.LOGISTICAS_INICIALES))
        assertTrue(repo.addLogistic("Mi Transporte"))
        assertFalse(repo.addLogistic("mi transporte"))
        assertTrue("Mi Transporte" in repo.logistics())
    }
}
