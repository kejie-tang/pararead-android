package org.pararead.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DayColors = lightColorScheme(
    primary = Color(0xFF252A34), onPrimary = Color.White, primaryContainer = Color(0xFFE9EDF4), onPrimaryContainer = Color(0xFF252A34),
    secondary = Color(0xFF4668D7), onSecondary = Color.White, secondaryContainer = Color(0xFFEBF0FF), onSecondaryContainer = Color(0xFF3554B4),
    tertiary = Color(0xFF367C6D), tertiaryContainer = Color(0xFFE8F4EE), onTertiaryContainer = Color(0xFF286453),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF202632), surface = Color.White, onSurface = Color(0xFF202632),
    surfaceVariant = Color(0xFFEEF0F4), onSurfaceVariant = Color(0xFF697386), outline = Color(0xFFD8DDE6), outlineVariant = Color(0xFFE9ECF1),
    surfaceContainer = Color(0xFFF6F7F9), surfaceContainerHigh = Color(0xFFF0F2F6), surfaceContainerHighest = Color(0xFFE9ECF2),
    error = Color(0xFFB34340), errorContainer = Color(0xFFFCEDEC), onErrorContainer = Color(0xFF872C2A))
private val NightColors = darkColorScheme(
    primary = Color(0xFFE8ECF3), onPrimary = Color(0xFF202632), primaryContainer = Color(0xFF2C3442), onPrimaryContainer = Color(0xFFE8ECF3),
    secondary = Color(0xFF9EB6FF), onSecondary = Color(0xFF17285B), secondaryContainer = Color(0xFF26334F), onSecondaryContainer = Color(0xFFC6D5FF),
    tertiary = Color(0xFF9CD2BC), tertiaryContainer = Color(0xFF223C34), onTertiaryContainer = Color(0xFFA8DDC6),
    background = Color(0xFF12161D), onBackground = Color(0xFFE8ECF3), surface = Color(0xFF1C222C), onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF282F3B), onSurfaceVariant = Color(0xFFA4AEC0), outline = Color(0xFF3C4554), outlineVariant = Color(0xFF2D3542),
    surfaceContainer = Color(0xFF12161D), surfaceContainerHigh = Color(0xFF252D39), surfaceContainerHighest = Color(0xFF303A49),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF472B2B), onErrorContainer = Color(0xFFFFDAD5))

@Composable fun ParaReadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) NightColors else DayColors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 42.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 25.sp),
            titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}

/** Small original outline icons, drawn in a consistent 24-unit coordinate space. */
enum class AppGlyph { Book, Model, Settings, Plus, File, Arrow, Check, Trash, Close, Search, Download, Globe, Sliders, More, External }
@Composable fun AppIcon(glyph: AppGlyph, modifier: Modifier = Modifier.size(22.dp), tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(tint, Offset(x * scale, y * scale), Offset(xx * scale, yy * scale), 1.65f * scale, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) = drawRoundRect(tint, Offset(x * scale, y * scale), Size(w * scale, h * scale), CornerRadius(r * scale), style = Stroke(1.65f * scale))
        when (glyph) {
            AppGlyph.Book -> { box(3f, 4f, 18f, 16f); line(12f, 5f, 12f, 19f); line(6f, 8f, 9f, 8f); line(15f, 8f, 18f, 8f) }
            AppGlyph.Model -> { box(5f, 5f, 14f, 14f, 3f); box(9f, 9f, 6f, 6f, 1f); for (a in listOf(8f,16f)) { line(a,2f,a,5f);line(a,19f,a,22f);line(2f,a,5f,a);line(19f,a,22f,a) } }
            AppGlyph.Settings -> { drawCircle(tint, 7f*scale, Offset(12f*scale,12f*scale), style=Stroke(1.65f*scale)); drawCircle(tint, 2.5f*scale, Offset(12f*scale,12f*scale), style=Stroke(1.65f*scale));line(12f,2f,12f,5f);line(12f,19f,12f,22f);line(2f,12f,5f,12f);line(19f,12f,22f,12f) }
            AppGlyph.Plus -> { line(12f,5f,12f,19f);line(5f,12f,19f,12f) }
            AppGlyph.File -> { box(5f,3f,14f,18f);line(9f,9f,15f,9f);line(9f,13f,15f,13f);line(9f,17f,13f,17f) }
            AppGlyph.Arrow -> { line(5f,12f,19f,12f);line(14f,7f,19f,12f);line(14f,17f,19f,12f) }
            AppGlyph.Check -> { line(5f,12f,10f,17f);line(10f,17f,19f,7f) }
            AppGlyph.Trash -> { line(4f,6f,20f,6f);box(7f,6f,10f,15f);line(10f,3f,14f,3f);line(10f,10f,10f,17f);line(14f,10f,14f,17f) }
            AppGlyph.Close -> { line(6f,6f,18f,18f);line(6f,18f,18f,6f) }
            AppGlyph.Search -> { drawCircle(tint,6.5f*scale,Offset(10f*scale,10f*scale),style=Stroke(1.65f*scale));line(15f,15f,21f,21f) }
            AppGlyph.Download -> { line(12f,3f,12f,15f);line(7f,10f,12f,15f);line(17f,10f,12f,15f);line(5f,19f,19f,19f) }
            AppGlyph.Globe -> { drawCircle(tint,9f*scale,Offset(12f*scale,12f*scale),style=Stroke(1.65f*scale));line(3f,12f,21f,12f);drawOval(tint,Offset(8f*scale,3f*scale),Size(8f*scale,18f*scale),style=Stroke(1.4f*scale)) }
            AppGlyph.Sliders -> { for ((y,x) in listOf(6f to 9f,12f to 16f,18f to 7f)) { line(3f,y,x-2,y);line(x+2,y,21f,y);drawCircle(tint,2f*scale,Offset(x*scale,y*scale),style=Stroke(1.65f*scale)) } }
            AppGlyph.More -> for (x in listOf(5f,12f,19f)) drawCircle(tint,1.5f*scale,Offset(x*scale,12f*scale))
            AppGlyph.External -> { line(13f,4f,20f,4f);line(20f,4f,20f,11f);line(11f,13f,20f,4f);line(6f,4f,4f,4f);line(4f,4f,4f,20f);line(4f,20f,20f,20f);line(20f,20f,20f,16f) }
        }
    }
}

@Composable fun IconTile(glyph: AppGlyph, modifier: Modifier = Modifier.size(42.dp), color: Color = MaterialTheme.colorScheme.secondary) {
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = color.copy(alpha = .09f)) {
        Box(contentAlignment = Alignment.Center) { AppIcon(glyph, tint = color) }
    }
}

@Composable fun AppDialog(title: String, onClose: () -> Unit, subtitle: String = "", glyph: AppGlyph = AppGlyph.Settings,
    closeLabel: String = "关闭", closeEnabled: Boolean = true, footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (closeEnabled) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(.94f).heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background, shadowElevation = 12.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(24.dp, 22.dp, 16.dp, 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(glyph)
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose, enabled = closeEnabled) { Text(closeLabel) }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                if (footer != null) Row(Modifier.fillMaxWidth().padding(24.dp, 16.dp), horizontalArrangement = Arrangement.End) { footer() }
                else Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable fun SettingsSection(title: String, glyph: AppGlyph, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(glyph, Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable fun LanguagePicker(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, autoLabel: String = "自动识别") {
    var expanded by remember { mutableStateOf(false) }
    val names = linkedMapOf("auto" to autoLabel, "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語", "ko" to "한국어", "fr" to "Français", "de" to "Deutsch", "es" to "Español", "pt" to "Português", "ru" to "Русский", "it" to "Italiano")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp)) {
                Text(names[value] ?: value, Modifier.weight(1f)); Text("⌄")
            }
            DropdownMenu(expanded, { expanded = false }) {
                names.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(code); expanded = false }) }
            }
        }
    }
}
