package com.lilus.despachos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(vm: AppViewModel) {
    var pickEmpresa by remember { mutableStateOf(false) }
    var pickLogistica by remember { mutableStateOf(false) }
    var addLogistic by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxWidth().background(LilusColors.Navy).padding(horizontal = 20.dp, vertical = 26.dp)
        ) {
            Text("LILUS DESPACHOS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Control de paquetes y despachos.", color = Color(0xFFCFD8E3), fontSize = 15.sp)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            vm.openDispatch?.let { d ->
                InfoCard(bg = LilusColors.OrangeBg) {
                    Text("Tenés un despacho en curso", fontWeight = FontWeight.Bold, color = LilusColors.Orange, fontSize = 17.sp)
                    Text("${d.logistica} · ${d.empresa} · ${d.operario}")
                    Text("Iniciado ${Fmt.dateTime(d.startedAt)} · ${d.itemCount} bultos")
                    Spacer(Modifier.height(6.dp))
                    BigButton("CONTINUAR DESPACHO", { vm.continueDispatch() }, color = LilusColors.Orange)
                }
            }

            OutlinedTextField(
                value = vm.operario,
                onValueChange = { vm.operario = it },
                label = { Text("Operario (tu nombre)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Selector("Empresa", vm.empresa) { pickEmpresa = true }
            Selector("Logística", vm.logistica.ifBlank { "Elegir…" }) { pickLogistica = true }
            TextButton(onClick = { addLogistic = true }) { Text("+ Agregar otra logística", color = LilusColors.Navy) }

            BigButton(
                "INICIAR DESPACHO",
                { vm.startDispatch() },
                color = LilusColors.Green,
                enabled = vm.openDispatch == null,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Historial", { vm.go(Screen.History) }, Modifier.weight(1f))
                SecondaryButton("Configuración", { vm.go(Screen.Config) }, Modifier.weight(1f))
            }

            InfoCard(bg = if (vm.backendConfigured) LilusColors.GreenBg else LilusColors.OrangeBg) {
                if (vm.backendConfigured) {
                    Text("Google Drive: configurado", color = LilusColors.Green, fontWeight = FontWeight.Bold)
                } else {
                    Text("Google Drive: pendiente de configurar", color = LilusColors.Orange, fontWeight = FontWeight.Bold)
                    Text("La app funciona igual y guarda todo en el celular. Los despachos se enviarán cuando se configure.", fontSize = 13.sp)
                }
                if (vm.pendingSyncCount > 0) Text("Despachos pendientes de envío: ${vm.pendingSyncCount}", fontSize = 13.sp)
            }
        }
    }

    if (pickEmpresa) OptionDialog("Empresa", Catalog.EMPRESAS, { vm.empresa = it; pickEmpresa = false }) { pickEmpresa = false }
    if (pickLogistica) OptionDialog("Logística", vm.logistics, { vm.logistica = it; pickLogistica = false }) { pickLogistica = false }
    if (addLogistic) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addLogistic = false },
            title = { Text("Nueva logística") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            },
            confirmButton = { TextButton(onClick = { vm.addLogistic(name); addLogistic = false }) { Text("AGREGAR") } },
            dismissButton = { TextButton(onClick = { addLogistic = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Selector(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Color(0xFF9AA5B1), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = LilusColors.Grey, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 18.sp, color = LilusColors.Grey)
        }
    }
}

@Composable
fun OptionDialog(title: String, options: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { o ->
                    Text(
                        o, fontSize = 18.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 14.dp, horizontal = 4.dp)
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
