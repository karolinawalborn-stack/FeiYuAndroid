package com.feiyu.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.companion.AppTheme
import kotlin.math.PI
import kotlin.math.sin

data class Palette(
    val dark: Boolean,
    val background: List<Color>,
    val glass: Color,
    val stroke: Color,
    val field: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val accent2: Color,
    val danger: Color,
    val success: Color,
) {
    val accentBrush get() = Brush.linearGradient(listOf(accent, accent2))
    val softAccent get() = accent.copy(alpha = if (dark) 0.16f else 0.11f)
}

val NightPalette = Palette(
    dark = true,
    background = listOf(Color(0xFF040C1D), Color(0xFF0A2140), Color(0xFF0F3A63)),
    glass = Color(0x2EFFFFFF), stroke = Color(0x33FFFFFF), field = Color(0x26FFFFFF),
    text = Color(0xFFF2F7FF), secondary = Color(0xFF9DB4CF),
    accent = Color(0xFF4FC3FF), accent2 = Color(0xFF8B7BFF),
    danger = Color(0xFFFF6B7D), success = Color(0xFF33C779),
)

val DayPalette = Palette(
    dark = false,
    background = listOf(Color(0xFFEAF6FF), Color(0xFFD7ECFF), Color(0xFFF4E9FF)),
    glass = Color(0xB3FFFFFF), stroke = Color(0x1F1E4A7A), field = Color(0xE6FFFFFF),
    text = Color(0xFF14253D), secondary = Color(0xFF5B6E88),
    accent = Color(0xFF1C8DE8), accent2 = Color(0xFF7B5CFA),
    danger = Color(0xFFE0455A), success = Color(0xFF22A366),
)

val LocalPalette = staticCompositionLocalOf { NightPalette }

@Composable
fun FeiYuTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val palette = if (theme == AppTheme.NIGHT) NightPalette else DayPalette
    val scheme = if (palette.dark) {
        darkColorScheme(primary = palette.accent, secondary = palette.accent2, background = palette.background.first(),
            surface = palette.background[1], onSurface = palette.text, onBackground = palette.text, error = palette.danger)
    } else {
        lightColorScheme(primary = palette.accent, secondary = palette.accent2, background = palette.background.first(),
            surface = Color.White, onSurface = palette.text, onBackground = palette.text, error = palette.danger)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides palette) { content() }
    }
}

/** Deep-sea backdrop: gradient, soft aurora glow, rising bubbles. */
@Composable
fun SeaBackdrop(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "sea")
    val time by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(modifier.fillMaxSize().background(Brush.verticalGradient(p.background))) {
        val w = size.width
        val h = size.height
        drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.28f), Color.Transparent), Offset(w * 0.2f, h * 0.25f), w * 0.7f),
            radius = w * 0.7f, center = Offset(w * 0.2f, h * 0.25f))
        drawCircle(Brush.radialGradient(listOf(p.accent2.copy(alpha = 0.22f), Color.Transparent), Offset(w * 0.85f, h * 0.55f), w * 0.6f),
            radius = w * 0.6f, center = Offset(w * 0.85f, h * 0.55f))
        // Holographic floor under her feet.
        drawOval(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.35f), Color.Transparent), Offset(w / 2, h * 0.86f), w * 0.42f),
            topLeft = Offset(w * 0.12f, h * 0.83f), size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.07f))
        for (i in 0 until 14) {
            val seed = i * 0.6180339f
            val phase = (time + seed) % 1f
            val x = w * ((seed * 7.3f) % 1f) + sin((phase * 2 * PI + i).toFloat()) * 14f
            val y = h * (1.05f - phase * 1.15f)
            val r = 3f + (i % 4) * 2.2f
            drawCircle(p.text.copy(alpha = 0.10f + 0.12f * (1 - phase)), radius = r, center = Offset(x, y))
        }
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, radius: Int = 22, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(radius.dp))
            .background(p.glass)
            .border(0.8.dp, p.stroke, RoundedCornerShape(radius.dp))
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionCard(title: String, icon: ImageVector, caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.accentBrush), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (caption != null) Text(caption, color = p.secondary, fontSize = 12.sp)
            }
        }
        content()
    }
}

enum class StatusKind { WORKING, OK, FAILED }

data class InlineStatus(val kind: StatusKind, val text: String) {
    companion object {
        fun working(text: String) = InlineStatus(StatusKind.WORKING, text)
        fun ok(text: String) = InlineStatus(StatusKind.OK, text)
        fun failed(text: String) = InlineStatus(StatusKind.FAILED, text)
    }
}

@Composable
fun StatusView(status: InlineStatus) {
    val p = LocalPalette.current
    val tint = when (status.kind) {
        StatusKind.WORKING -> p.accent
        StatusKind.OK -> p.success
        StatusKind.FAILED -> p.danger
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.12f))
            .border(0.8.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status.kind) {
            StatusKind.WORKING -> CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            StatusKind.OK -> Icon(Icons.Filled.CheckCircle, null, tint = tint, modifier = Modifier.size(20.dp))
            StatusKind.FAILED -> Icon(Icons.Filled.ErrorOutline, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(status.text, color = if (status.kind == StatusKind.FAILED) p.danger else p.text, fontSize = 13.sp)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = p.secondary.copy(alpha = 0.7f)) },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = if (secret) {
            { IconButton(onClick = { revealed = !revealed }) { Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (revealed) "隐藏密钥" else "显示密钥") } }
        } else null,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text, unfocusedTextColor = p.text, focusedContainerColor = p.field, unfocusedContainerColor = p.field,
            focusedBorderColor = p.accent, unfocusedBorderColor = p.stroke, focusedLabelColor = p.accent, unfocusedLabelColor = p.secondary,
            cursorColor = p.accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ActionButton(
    title: String,
    icon: ImageVector? = null,
    prominent: Boolean = true,
    busy: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val tint = if (destructive) p.danger else p.accent
    val shape = RoundedCornerShape(14.dp)
    val base = modifier.heightIn(min = 46.dp).clip(shape)
    val styled = if (prominent) base.background(p.accentBrush) else base.background(tint.copy(alpha = 0.1f)).border(0.8.dp, tint.copy(alpha = 0.3f), shape)
    Row(
        styled.clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = (if (prominent) Color.White else tint).copy(alpha = if (enabled || busy) 1f else 0.45f)
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        if (busy || icon != null) Spacer(Modifier.width(7.dp))
        Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = p.secondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = Color.White))
    }
}

/** Segmented choice (Material3 segmented buttons are still experimental). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.field).padding(3.dp)) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (active) p.accent.copy(alpha = if (p.dark) 0.35f else 0.18f) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(option) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = if (active) p.text else p.secondary, fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    val p = LocalPalette.current
    Row(Modifier.clip(CircleShape).background(p.field).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = p.text, fontSize = 12.sp, maxLines = 1)
    }
}
