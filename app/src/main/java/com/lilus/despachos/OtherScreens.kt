package com.lilus.despachos

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Resumen después de cerrar + detalle desde el historial (misma pantalla). */
@Composable
fun DetailScreen(vm: AppViewModel, uuid: String, justClosed: Boolean) {
    val context = LocalContext.current
    // Refresco periódico para ver el estado de sincronización real
    LaunchedEffect(uuid) {
        while (true) { vm.loadDetail(uuid); delay(4000) }
    }
    val d = vm.detail?.takeIf { it.uuid == uuid }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar(if (justClosed) "Despacho cerrado" else "Detalle del despacho", onBack = { vm.back() })
        if (d == null) {
            Text("Cargando…", modifier = Modifier.padding(20.dp)); return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (justClosed) InfoCard(bg = LilusColors.GreenBg) {
                        Text("✅ Despacho cerrado y guardado en el celular", color = LilusColors.Green, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    InfoCard {
                        Text(d.displayId ?: "En curso", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = LilusColors.Navy)
                        LabelValue("Fecha", Fmt.date(d.closedAt ?: d.startedAt))
                        LabelValue("Empresa", d.empresa)
                        LabelValue("Logística", d.logistica)
                        LabelValue("Operario", d.operario)
                        LabelValue("Bultos", d.itemCount.toString())
                        LabelValue("Inicio", Fmt.hm(d.startedAt))
                        LabelValue("Fin", d.closedAt?.let { Fmt.hm(it) } ?: "—")
                        LabelValue("Duplicados detectados", d.duplicates.toString())
                    }
                    SyncCard(vm, d)
                    if (!d.isOpen) {
                        val hasDocs = d.pdfPath != null && d.xlsxPath != null
                        if (hasDocs) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton("Abrir PDF", { FileActions.open(context, d.pdfPath) }, Modifier.weight(1f))
                                SecondaryButton("Imprimir", { FileActions.print(context, d.pdfPath, d.displayId ?: "Despacho") }, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton("Abrir Excel", { FileActions.open(context, d.xlsxPath) }, Modifier.weight(1f))
                                SecondaryButton("Compartir", { FileActions.share(context, d.pdfPath, d.xlsxPath) }, Modifier.weight(1f))
                            }
                        }
                        SecondaryButton(if (hasDocs) "Volver a generar comprobantes" else "Generar comprobantes", { vm.regenerateDocs(uuid) }, Modifier.fillMaxWidth())
                    }
                    if (justClosed) BigButton("NUEVO DESPACHO", { vm.back() }, color = LilusColors.Green)
                    Text("Códigos (${vm.detailItems.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(vm.detailItems.withIndex().toList()) { (i, item) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${i + 1}.", modifier = Modifier.padding(end = 8.dp), color = LilusColors.Grey)
                    Text(item.code, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text(Fmt.time(item.scannedAt), color = LilusColors.Grey, fontSize = 12.sp)
                }
                HorizontalDivider()
            }
            if (vm.detailAudit.any { it.action == "QUITAR_CODIGO" }) item {
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text("Auditoría de correcciones", fontWeight = FontWeight.Bold)
                    vm.detailAudit.filter { it.action == "QUITAR_CODIGO" }.forEach {
                        Text("${Fmt.dateTime(it.at)} · ${it.operario} quitó ${it.code}", fontSize = 13.sp, color = LilusColors.Grey)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(vm: AppViewModel, d: Dispatch) {
    val context = LocalContext.current
    val (fg, bg) = syncColor(d)
    InfoCard(bg = bg) {
        Text("Google Drive", fontWeight = FontWeight.Bold, color = fg)
        when {
            d.isOpen -> Text("El despacho sigue en curso.")
            d.syncStatus == SyncStatus.SYNCED -> {
                Text("Sincronizado ${d.syncedAt?.let { Fmt.dateTime(it) } ?: ""}")
                d.driveUrl?.let { url ->
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { }
                    }) { Text("Abrir carpeta en Drive") }
                }
            }
            !vm.backendConfigured -> {
                Text("Pendiente: Google Drive todavía no está configurado en este celular.")
                Text("Los comprobantes quedan guardados acá y se enviarán cuando se configure.", fontSize = 13.sp)
            }
            d.syncStatus == SyncStatus.ERROR -> {
                Text("No se pudo enviar todavía.")
                d.syncError?.let { Text(it, fontSize = 13.sp) }
                TextButton(onClick = { vm.retrySync(d.uuid) }) { Text("Reintentar ahora") }
            }
            else -> {
                Text("Pendiente de sincronización. Se envía automáticamente cuando hay internet.")
                TextButton(onClick = { vm.retrySync(d.uuid) }) { Text("Enviar ahora") }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { while (true) { vm.loadHistory(); delay(5000) } }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("Historial de despachos", onBack = { vm.back() })
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = { vm.searchQuery = it; vm.search() },
                label = { Text("Buscar código de barras") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                modifier = Modifier.weight(1f),
            )
            if (vm.searchQuery.isNotEmpty()) TextButton(onClick = { vm.searchQuery = ""; vm.search() }) { Text("Limpiar") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (vm.searchQuery.isNotBlank()) {
                item { Text("Resultados: ${vm.searchHits.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
                items(vm.searchHits) { h ->
                    Column(Modifier.fillMaxWidth().clickable { vm.go(Screen.Detail(h.dispatch.uuid)) }.padding(vertical = 8.dp)) {
                        Text(h.item.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(
                            "${h.dispatch.displayId ?: "En curso"} · ${h.dispatch.logistica} · ${Fmt.dateTime(h.item.scannedAt)} · ${h.dispatch.operario}",
                            fontSize = 13.sp, color = LilusColors.Grey,
                        )
                    }
                    HorizontalDivider()
                }
            } else {
                if (vm.history.isEmpty()) item { Text("Todavía no hay despachos.", modifier = Modifier.padding(8.dp)) }
                items(vm.history) { d ->
                    Column(Modifier.fillMaxWidth().clickable { vm.go(Screen.Detail(d.uuid)) }.padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.displayId ?: "En curso", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusChip(d)
                        }
                        Text("${Fmt.dateTime(d.closedAt ?: d.startedAt)} · ${d.logistica} · ${d.empresa}", fontSize = 13.sp)
                        Text("${d.operario} · ${d.itemCount} paquetes", fontSize = 13.sp, color = LilusColors.Grey)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ConfigScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("Configuración", onBack = { vm.back() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conexión con Google Drive (la carga un administrador)", fontWeight = FontWeight.Bold)
            Text(
                "Pegá la URL del backend de Apps Script (empieza con https://script.google.com/…) y el token de este celular. " +
                    "No se usa la contraseña de Gmail.",
                fontSize = 13.sp, color = LilusColors.Grey,
            )
            OutlinedTextField(vm.cfgUrl, { vm.cfgUrl = it }, label = { Text("URL del backend (https)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                vm.cfgToken, { vm.cfgToken = it }, label = { Text("Token del celular") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Probar conexión", { vm.testConfig() }, Modifier.weight(1f), enabled = !vm.cfgTesting)
                SecondaryButton("Guardar", { vm.saveConfig() }, Modifier.weight(1f))
            }
            if (vm.cfgTesting) Text("Probando…")
            vm.cfgResult?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            HorizontalDivider()
            Text("Versión ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = LilusColors.Grey)
        }
    }
}
