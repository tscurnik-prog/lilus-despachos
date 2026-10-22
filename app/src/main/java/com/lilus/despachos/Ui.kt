package com.lilus.despachos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LilusColors {
    val Navy = Color(0xFF0B2545)
    val NavyLight = Color(0xFF13315C)
    val Green = Color(0xFF2E7D32)
    val GreenBg = Color(0xFFE8F5E9)
    val Red = Color(0xFFC62828)
    val RedBg = Color(0xFFFFEBEE)
    val Orange = Color(0xFFEF6C00)
    val OrangeBg = Color(0xFFFFF3E0)
    val Grey = Color(0xFF5F6B7A)
    val Surface = Color(0xFFF4F6F9)
}

@Composable
fun LilusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = LilusColors.Navy,
            onPrimary = Color.White,
            secondary = LilusColors.Green,
            error = LilusColors.Red,
            background = Color.White,
            surface = Color.White,
        ),
        content = content,
    )
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().background(LilusColors.Navy).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("‹ Volver", color = Color.White, fontSize = 16.sp) }
        } else Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LilusColors.Navy, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(10.dp)) {
        Text(text, color = LilusColors.Navy, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoCard(bg: Color = LilusColors.Surface, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) }
}

@Composable
fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = LilusColors.Grey, modifier = Modifier.weight(0.45f))
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.55f))
    }
}

fun syncColor(d: Dispatch): Pair<Color, Color> = when {
    d.isOpen -> LilusColors.Navy to LilusColors.Surface
    d.syncStatus == SyncStatus.SYNCED -> LilusColors.Green to LilusColors.GreenBg
    d.syncStatus == SyncStatus.ERROR -> LilusColors.Red to LilusColors.RedBg
    else -> LilusColors.Orange to LilusColors.OrangeBg
}

@Composable
fun StatusChip(d: Dispatch) {
    val (fg, bg) = syncColor(d)
    Text(
        d.stateLabel,
        color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
