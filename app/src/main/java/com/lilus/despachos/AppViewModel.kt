package com.lilus.despachos

import android.app.Application
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed class Screen {
    object Home : Screen()
    object Scan : Screen()
    data class Done(val uuid: String) : Screen()
    object History : Screen()
    data class Detail(val uuid: String) : Screen()
    object Config : Screen()
}

/** Último evento del escáner, para el cartel grande verde / rojo. */
sealed class ScanEvent(val id: Long) {
    class Ok(id: Long, val code: String, val count: Int) : ScanEvent(id)
    class Dup(id: Long, val code: String, val firstAt: Long) : ScanEvent(id)
    class Problem(id: Long, val message: String) : ScanEvent(id)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val lilus = app as LilusApp
    private val repo = lilus.repository
    private val settings = lilus.settings
    private val feedback = Feedback(app)
    /** Un solo hilo para la base: las lecturas se procesan de a una y en orden. */
    private val db: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // ---- Navegación ----
    private val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    fun go(s: Screen) {
        backStack.add(s)
        onEnter(s)
    }

    /** @return false si no hay a dónde volver. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        onEnter(screen)
        return true
    }

    private fun goHomeClearing() {
        backStack.clear(); backStack.add(Screen.Home); onEnter(Screen.Home)
    }

    private fun onEnter(s: Screen) {
        when (s) {
            Screen.Home -> loadHome()
            Screen.Scan -> loadScan()
            is Screen.Done -> loadDetail(s.uuid)
            Screen.History -> loadHistory()
            is Screen.Detail -> loadDetail(s.uuid)
            Screen.Config -> loadConfig()
        }
    }

    // ---- Mensajes simples para el operario ----
    var message by mutableStateOf<String?>(null)
        private set
    fun clearMessage() { message = null }

    private suspend fun <T> safeDb(block: () -> T): T? = try {
        withContext(db) { block() }
    } catch (e: SQLiteFullException) {
        message = "El celular se quedó sin espacio. Liberá espacio para seguir guardando."
        null
    } catch (e: SQLiteException) {
        message = "No se pudo acceder a los datos guardados. Cerrá y volvé a abrir la app."
        null
    }

    // ---- Inicio ----
    var operario by mutableStateOf(settings.operario)
    var empresa by mutableStateOf(settings.empresa)
    var logistica by mutableStateOf(settings.logistica)
    var logistics by mutableStateOf<List<String>>(emptyList())
        private set
    var openDispatch by mutableStateOf<Dispatch?>(null)
        private set
    var backendConfigured by mutableStateOf(settings.isBackendConfigured)
        private set
    var pendingSyncCount by mutableStateOf(0)
        private set

    init {
        loadHome()
        viewModelScope.launch { safeDb { SyncWorker.enqueueAllPending(getApplication<Application>(), repo) } }
    }

    fun loadHome() {
        viewModelScope.launch {
            safeDb {
                Triple(repo.logistics(), repo.getOpenDispatch(), repo.dispatchesToSync().size)
            }?.let { (l, open, pend) ->
                logistics = l; openDispatch = open; pendingSyncCount = pend
                if (logistica.isBlank() || logistica !in l) logistica = l.firstOrNull() ?: ""
            }
            backendConfigured = settings.isBackendConfigured
        }
    }

    fun addLogistic(name: String) {
        viewModelScope.launch {
            val ok = safeDb { repo.addLogistic(name) } ?: return@launch
            if (ok) {
                logistica = name.trim()
                loadHome()
            } else message = "Esa logística ya existe o el nombre está vacío."
        }
    }

    fun startDispatch() {
        val op = operario.trim()
        when {
            op.isEmpty() -> { message = "Escribí tu nombre antes de empezar."; return }
            logistica.isBlank() -> { message = "Elegí una logística."; return }
        }
        settings.operario = op; settings.empresa = empresa; settings.logistica = logistica
        viewModelScope.launch {
            val existing = safeDb { repo.getOpenDispatch() }
            if (existing != null) {
                message = "Ya tenés un despacho en curso. Continualo o finalizalo primero."
                openDispatch = existing
                return@launch
            }
            val d = safeDb { repo.startDispatch(op, empresa, logistica, System.currentTimeMillis()) } ?: return@launch
            openDispatch = d
            go(Screen.Scan)
        }
    }

    fun continueDispatch() { if (openDispatch != null) go(Screen.Scan) }

    // ---- Escaneo ----
    var current by mutableStateOf<Dispatch?>(null)
        private set
    var count by mutableStateOf(0)
        private set
    var lastCode by mutableStateOf<String?>(null)
        private set
    var lastEvent by mutableStateOf<ScanEvent?>(null)
        private set
    var items by mutableStateOf<List<ScanItem>>(emptyList())
        private set
    private var eventSeq = 0L

    private fun loadScan() {
        viewModelScope.launch {
            val d = safeDb { repo.getOpenDispatch() }
            if (d == null) { goHomeClearing(); return@launch }
            refreshScan(d.uuid)
        }
    }

    private suspend fun refreshScan(uuid: String) {
        safeDb { Triple(repo.getDispatch(uuid), repo.countItems(uuid), repo.lastItem(uuid)) }?.let { (d, c, last) ->
            current = d; count = c; lastCode = last?.code
        }
    }

    fun loadItems() {
        val d = current ?: return
        viewModelScope.launch { safeDb { repo.listItems(d.uuid) }?.let { items = it } }
    }

    /** Llamado por la cámara o por el ingreso manual. */
    fun submitCode(raw: String) {
        val d = current ?: return
        viewModelScope.launch {
            val r = safeDb { repo.registerCode(d.uuid, raw, System.currentTimeMillis()) } ?: run {
                feedback.duplicate()
                lastEvent = ScanEvent.Problem(++eventSeq, message ?: "No se pudo guardar el código.")
                return@launch
            }
            when (r) {
                is ScanResult.Registered -> {
                    feedback.success()
                    count = r.count; lastCode = r.code
                    lastEvent = ScanEvent.Ok(++eventSeq, r.code, r.count)
                }
                is ScanResult.Duplicate -> {
                    feedback.duplicate()
                    count = r.count
                    lastEvent = ScanEvent.Dup(++eventSeq, r.code, r.firstScannedAt)
                }
                ScanResult.Unreadable -> {
                    feedback.warning()
                    lastEvent = ScanEvent.Problem(++eventSeq, "No se pudo leer el código. Probá de nuevo o cargalo a mano.")
                }
                ScanResult.DispatchClosed -> {
                    feedback.warning()
                    lastEvent = ScanEvent.Problem(++eventSeq, "Este despacho ya está cerrado.")
                }
            }
            safeDb { repo.getDispatch(d.uuid) }?.let { current = it }
        }
    }

    fun removeItem(item: ScanItem) {
        val d = current ?: return
        viewModelScope.launch {
            val ok = safeDb { repo.removeItem(d.uuid, item.id, d.operario, System.currentTimeMillis()) } ?: return@launch
            if (ok) message = "Código quitado: ${item.code}" else message = "No se pudo quitar el código."
            refreshScan(d.uuid)
            loadItems()
        }
    }

    // ---- Cierre ----
    var closing by mutableStateOf(false)
        private set

    fun confirmClose() {
        val d = current ?: return
        if (closing) return
        closing = true
        viewModelScope.launch {
            try {
                val closed = safeDb { repo.closeDispatch(d.uuid, System.currentTimeMillis()) } ?: return@launch
                generateDocs(closed)
                SyncWorker.enqueue(getApplication<Application>(), closed.uuid)
                openDispatch = null
                current = null
                backStack.clear(); backStack.add(Screen.Home)
                go(Screen.Done(closed.uuid))
            } finally {
                closing = false
            }
        }
    }

    private suspend fun generateDocs(d: Dispatch): Boolean {
        val error: String? = safeDb {
            try {
                val list = repo.listItems(d.uuid)
                val (pdf, xlsx) = Documents.generate(getApplication<Application>(), d, list)
                repo.setDocuments(d.uuid, pdf.absolutePath, xlsx.absolutePath)
                ""
            } catch (e: StorageFullException) {
                "No hay espacio para guardar los comprobantes. Liberá espacio y tocá \"Generar comprobantes\"."
            } catch (e: Exception) {
                "No se pudieron generar los comprobantes. Tocá \"Generar comprobantes\" para reintentar."
            }
        }
        if (error == null) return false
        if (error.isNotEmpty()) { message = error; return false }
        return true
    }

    fun regenerateDocs(uuid: String) {
        viewModelScope.launch {
            val d = safeDb { repo.getDispatch(uuid) } ?: return@launch
            if (d.isOpen) return@launch
            if (generateDocs(d)) message = "Comprobantes generados."
            loadDetail(uuid)
        }
    }

    // ---- Historial / detalle ----
    var history by mutableStateOf<List<Dispatch>>(emptyList())
        private set
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<CodeHit>>(emptyList())
        private set
    var detail by mutableStateOf<Dispatch?>(null)
        private set
    var detailItems by mutableStateOf<List<ScanItem>>(emptyList())
        private set
    var detailAudit by mutableStateOf<List<AuditEntry>>(emptyList())
        private set

    fun loadHistory() {
        viewModelScope.launch { safeDb { repo.listDispatches() }?.let { history = it } }
        if (searchQuery.isNotBlank()) search()
    }

    fun search() {
        val q = searchQuery
        viewModelScope.launch { safeDb { repo.searchCode(q) }?.let { searchHits = it } }
    }

    fun loadDetail(uuid: String) {
        viewModelScope.launch {
            safeDb { Triple(repo.getDispatch(uuid), repo.listItems(uuid), repo.auditLog(uuid)) }?.let { (d, i, a) ->
                detail = d; detailItems = i; detailAudit = a
            }
        }
    }

    fun retrySync(uuid: String) {
        if (!settings.isBackendConfigured) {
            message = "Google Drive todavía no está configurado en este celular. Pedile al administrador que lo configure."
            return
        }
        SyncWorker.enqueue(getApplication<Application>(), uuid, replace = true)
        message = "Reintentando el envío a Google Drive…"
    }

    // ---- Configuración ----
    var cfgUrl by mutableStateOf(settings.backendUrl)
    var cfgToken by mutableStateOf(settings.deviceToken)
    var cfgTesting by mutableStateOf(false)
        private set
    var cfgResult by mutableStateOf<String?>(null)
        private set

    private fun loadConfig() {
        cfgUrl = settings.backendUrl; cfgToken = settings.deviceToken; cfgResult = null
    }

    fun saveConfig() {
        val url = cfgUrl.trim()
        if (url.isNotEmpty() && !Settings.isValidBackendUrl(url)) {
            cfgResult = "La dirección tiene que empezar con https://"
            return
        }
        settings.backendUrl = url
        settings.deviceToken = cfgToken
        backendConfigured = settings.isBackendConfigured
        cfgResult = if (backendConfigured) "Configuración guardada." else "Guardado. Falta completar la URL y el token para activar Google Drive."
        if (backendConfigured) viewModelScope.launch { safeDb { SyncWorker.enqueueAllPending(getApplication<Application>(), repo, replace = true) } }
    }

    fun testConfig() {
        val url = cfgUrl.trim(); val token = cfgToken.trim()
        cfgTesting = true; cfgResult = null
        viewModelScope.launch {
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) { lilus.syncClient.ping(url, token) }
            cfgResult = when (r) {
                is SyncOutcome.Success -> "✅ Conexión correcta con el backend."
                is SyncOutcome.Retryable -> "No se pudo conectar: ${r.message}"
                is SyncOutcome.Permanent -> "Error: ${r.message}"
                SyncOutcome.NotConfigured -> "Completá una URL https:// y el token."
            }
            cfgTesting = false
        }
    }

    override fun onCleared() {
        feedback.release()
        super.onCleared()
    }
}
