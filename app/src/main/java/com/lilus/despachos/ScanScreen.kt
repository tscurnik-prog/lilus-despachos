package com.lilus.despachos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun ScanScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val d = vm.current

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionAsked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted; permissionAsked = true
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var showManual by remember { mutableStateOf(false) }
    var showItems by remember { mutableStateOf(false) }
    var showFinish by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<ScanItem?>(null) }
    val dialogsOpen = showManual || showItems || showFinish || toRemove != null

    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("Despacho en curso", onBack = { vm.back() })

        // ---- Cámara ----
        Box(Modifier.fillMaxWidth().height(290.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                hasPermission -> CameraPreview(paused = dialogsOpen || vm.closing || vm.waitingNext, waitingNext = vm.waitingNext, onCode = { vm.onCameraCode(it) })
                else -> Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("La app necesita la cámara para leer los códigos.", color = Color.White, fontSize = 16.sp)
                    Text("Mientras tanto podés cargar los códigos a mano.", color = Color(0xFFCFD8E3), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Permitir cámara") }
                        if (permissionAsked) Button(onClick = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Abrir ajustes") }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- Contador ----
            Box(
                Modifier.fillMaxWidth().background(LilusColors.Navy, RoundedCornerShape(12.dp)).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("BULTOS REGISTRADOS: ${vm.count}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }

            if (vm.waitingNext) {
                val dup = vm.lastEvent is ScanEvent.Dup
                BigButton(
                    "SIGUIENTE  ▶",
                    { vm.next() },
                    modifier = Modifier.height(72.dp),
                    color = if (dup) LilusColors.Red else LilusColors.Green,
                )
            }

            EventCard(vm.lastEvent, vm.count)

            if (d != null) InfoCard {
                LabelValue("Logística", d.logistica)
                LabelValue("Empresa", d.empresa)
                LabelValue("Operario", d.operario)
                LabelValue("Hora de inicio", Fmt.hm(d.startedAt))
                LabelValue("Último código", vm.lastCode ?: "—")
                LabelValue("Duplicados rechazados", d.duplicates.toString())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cargar a mano", { showManual = true }, Modifier.weight(1f))
                SecondaryButton("Ver / quitar", { vm.loadItems(); showItems = true }, Modifier.weight(1f))
            }
            BigButton("FINALIZAR DESPACHO", { showFinish = true }, enabled = !vm.closing)
        }
    }

    // ---- Carga manual ----
    if (showManual) {
        var code by remember { mutableStateOf("") }
        val submit = { if (code.isNotBlank()) { vm.submitCode(code); code = "" } }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Cargar código a mano") },
            text = {
                Column {
                    OutlinedTextField(
                        code, { code = it }, singleLine = true, label = { Text("Código") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    Text("Se guarda exactamente como lo escribís.", fontSize = 12.sp, color = LilusColors.Grey)
                    EventCard(vm.lastEvent, vm.count, compact = true)
                }
            },
            confirmButton = { TextButton(onClick = { submit() }) { Text("REGISTRAR") } },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Cerrar") } },
        )
    }

    // ---- Lista / quitar ----
    if (showItems) {
        AlertDialog(
            onDismissRequest = { showItems = false },
            title = { Text("Códigos registrados (${vm.items.size})") },
            text = {
                if (vm.items.isEmpty()) Text("Todavía no hay códigos.")
                else LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    itemsIndexed(vm.items.reversed()) { idx, it ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${vm.items.size - idx}. ${it.code}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                Text(Fmt.time(it.scannedAt), fontSize = 12.sp, color = LilusColors.Grey)
                            }
                            TextButton(onClick = { toRemove = it }) { Text("Quitar", color = LilusColors.Red) }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showItems = false }) { Text("Cerrar") } },
        )
    }
    toRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("¿Quitar este código?") },
            text = { Text("${item.code}\n\nSe descuenta del contador y queda registrado en la auditoría.") },
            confirmButton = {
                TextButton(onClick = { vm.removeItem(item); toRemove = null }) { Text("QUITAR", color = LilusColors.Red) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancelar") } },
        )
    }

    // ---- Finalizar ----
    if (showFinish && d != null) {
        val now = remember { System.currentTimeMillis() }
        AlertDialog(
            onDismissRequest = { showFinish = false },
            title = { Text("Finalizar despacho") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabelValue("Fecha", Fmt.date(now))
                    LabelValue("Empresa", d.empresa)
                    LabelValue("Operario", d.operario)
                    LabelValue("Logística", d.logistica)
                    LabelValue("Bultos", vm.count.toString())
                    LabelValue("Hora de inicio", Fmt.hm(d.startedAt))
                    LabelValue("Hora de fin", Fmt.hm(now))
                    LabelValue("Duplicados", d.duplicates.toString())
                    Text(
                        "Una vez cerrado no se puede modificar.",
                        color = LilusColors.Orange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)
                    )
                    if (vm.count == 0) Text("Atención: el despacho no tiene bultos.", color = LilusColors.Red)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFinish = false; vm.confirmClose() },
                    colors = ButtonDefaults.buttonColors(containerColor = LilusColors.Green),
                ) { Text("CONFIRMAR Y CERRAR") }
            },
            dismissButton = { TextButton(onClick = { showFinish = false }) { Text("Seguir escaneando") } },
        )
    }
}

@Composable
private fun EventCard(e: ScanEvent?, count: Int, compact: Boolean = false) {
    val pad = if (compact) 8.dp else 14.dp
    val big = if (compact) 16.sp else 22.sp
    when (e) {
        null -> if (!compact) InfoCard { Text("Apuntá la cámara al código de barras del paquete.", color = LilusColors.Grey) }
        is ScanEvent.Ok -> Column(Modifier.fillMaxWidth().background(LilusColors.GreenBg, RoundedCornerShape(12.dp)).padding(pad)) {
            Text("✅ CÓDIGO REGISTRADO", color = LilusColors.Green, fontSize = big, fontWeight = FontWeight.ExtraBold)
            Text("Código: ${e.code}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Text("Bultos registrados: ${e.count}", fontWeight = FontWeight.Bold)
        }
        is ScanEvent.Dup -> Column(Modifier.fillMaxWidth().background(LilusColors.RedBg, RoundedCornerShape(12.dp)).padding(pad)) {
            Text("🔴 CÓDIGO DUPLICADO", color = LilusColors.Red, fontSize = big, fontWeight = FontWeight.ExtraBold)
            Text("Este código ya fue registrado.", fontWeight = FontWeight.Bold)
            Text("Código: ${e.code}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Text("Registrado anteriormente a las ${Fmt.hm(e.firstAt)}.")
            Text("No se sumó al contador ($count).", color = LilusColors.Grey, fontSize = 13.sp)
        }
        is ScanEvent.Problem -> Column(Modifier.fillMaxWidth().background(LilusColors.OrangeBg, RoundedCornerShape(12.dp)).padding(pad)) {
            Text("⚠ ATENCIÓN", color = LilusColors.Orange, fontSize = big, fontWeight = FontWeight.ExtraBold)
            Text(e.message)
        }
    }
}

@Composable
private fun CameraPreview(paused: Boolean, waitingNext: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchOn by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    if (!hasCamera) {
        Text("Este celular no tiene cámara disponible. Cargá los códigos a mano.", color = Color.White, modifier = Modifier.padding(20.dp))
        return
    }

    val debouncer = remember { ScanDebouncer() }
    val latestOnCode = rememberUpdated(onCode)
    // Al reanudar, el código que sigue frente a la cámara no se vuelve a leer
    LaunchedEffect(paused) { if (!paused) debouncer.holdCurrent(System.currentTimeMillis()) }
    val analyzer = remember { BarcodeAnalyzer(debouncer) { code -> ContextCompat.getMainExecutor(context).execute { latestOnCode.value(code) } } }
    analyzer.paused = paused

    val controller = remember { LifecycleCameraController(context) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            controller.setImageAnalysisResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            controller.setImageAnalysisAnalyzer(executor, analyzer)
            controller.bindToLifecycle(lifecycleOwner)
            previewView.controller = controller
            val init = controller.initializationFuture
            init.addListener({
                try {
                    init.get()
                    hasFlash = controller.cameraInfo?.hasFlashUnit() == true
                } catch (e: Exception) {
                    cameraError = "No se pudo abrir la cámara. Cerrá otras apps que la usen o cargá los códigos a mano."
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            cameraError = "No se pudo abrir la cámara. Cargá los códigos a mano."
        }
        onDispose {
            try { controller.clearImageAnalysisAnalyzer() } catch (e: Exception) { }
            try { controller.unbind() } catch (e: Exception) { }
            executor.shutdown()
        }
    }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Guía de encuadre
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(2.dp).background(Color(0xCCFF5252))
        )
        cameraError?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(16.dp))
        }
        if (hasFlash) {
            Button(
                onClick = {
                    torchOn = !torchOn
                    try { controller.enableTorch(torchOn) } catch (e: Exception) { torchOn = false }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (torchOn) LilusColors.Orange else Color(0x99000000)),
            ) { Text(if (torchOn) "Linterna: SÍ" else "Linterna") }
        }
        if (paused) {
            Text(
                if (waitingNext) "Tocá SIGUIENTE para leer el próximo paquete" else "Lectura en pausa", color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(6.dp)
            )
        }
    }
}

@Composable
private fun <T> rememberUpdated(value: T): androidx.compose.runtime.State<T> {
    val ref = remember { mutableStateOf(value) }
    ref.value = value
    return ref
}
