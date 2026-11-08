package com.dorxon.browserdrop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dorxon.browserdrop.R

/** The same tokens as the website's style.css, light and dark. */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color, val fg: Color, val fg2: Color, val fg3: Color,
    val glass: Color, val glassStrong: Color, val stroke: Color, val strokeStrong: Color,
    val fill: Color, val fill2: Color,
    val accent: Color, val accent2: Color, val accentTint: Color,
    val ok: Color, val okDot: Color, val err: Color, val warn: Color,
    /** Solid card surface, its hairline lit from above (top -> bottom), and the accent's outline. */
    val card: Color, val edgeTop: Color, val edgeBottom: Color, val accentLine: Color,
    val aurora: List<Color>, val ring: Color,
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFF6F5F2), fg = Color(0xFF1C1C1E), fg2 = Color(0xFF6B6B70), fg3 = Color(0xFF8E8E93),
    glass = Color(0x94FFFFFF), glassStrong = Color(0xC7FFFFFF), stroke = Color(0x12000000), strokeStrong = Color(0x1A000000),
    fill = Color(0x1F787880), fill2 = Color(0x33787880),
    accent = Color(0xFF0071E3), accent2 = Color(0xFF2997FF), accentTint = Color(0x1A0071E3),
    ok = Color(0xFF248A3D), okDot = Color(0xFF34C759), err = Color(0xFFD70015), warn = Color(0xFFC93400),
    card = Color(0xFFFFFFFF), edgeTop = Color(0x0F000000), edgeBottom = Color(0x24000000), accentLine = Color(0x470071E3),
    aurora = listOf(Color(0x8C78B4FF), Color(0x61FFAAD6), Color(0x6B8CECDC)),
    ring = Color(0x290071E3),
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF0B0B0C), fg = Color(0xFFF5F5F7), fg2 = Color(0xFFA1A1A6), fg3 = Color(0xFF86868B),
    glass = Color(0x801E1E22), glassStrong = Color(0xB82C2C30), stroke = Color(0x14FFFFFF), strokeStrong = Color(0x24FFFFFF),
    fill = Color(0x3D787880), fill2 = Color(0x5C787880),
    accent = Color(0xFF0A84FF), accent2 = Color(0xFF64D2FF), accentTint = Color(0x2E0A84FF),
    ok = Color(0xFF30D158), okDot = Color(0xFF30D158), err = Color(0xFFFF453A), warn = Color(0xFFFF9F0A),
    card = Color(0xFF161618), edgeTop = Color(0x29FFFFFF), edgeBottom = Color(0x0FFFFFFF), accentLine = Color(0x660A84FF),
    aurora = listOf(Color(0x80285ADC), Color(0x57963288), Color(0x5C148C96)),
    ring = Color(0x380A84FF),
)

val LocalPalette = staticCompositionLocalOf { Light }

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

/** Numbers that are read as numbers: sizes, speeds, times, pairing codes. */
fun mono(size: Int, weight: Int = 400, color: Color = Color.Unspecified, tracking: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = FontWeight(weight), color = color, letterSpacing = tracking.em,
)

/** Tight, Apple-like tracking; tabular numbers so counters don't jitter. */
fun type(size: Int, weight: Int = 400, color: Color = Color.Unspecified, tracking: Double = -0.01) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color,
    letterSpacing = tracking.em, fontFeatureSettings = "tnum",
)

/** Light, dark, or follow the system (the default). */
enum class ThemeMode { AUTO, LIGHT, DARK }

@Composable
fun ThemeMode.isDark() = when (this) { ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; ThemeMode.AUTO -> isSystemInDarkTheme() }

/** Switching light/dark fades every colour across rather than flipping. */
@Composable
fun FerryTheme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val t by animateFloatAsState(if (mode.isDark()) 1f else 0f, tween(450, easing = Glide), label = "theme")
    val p = remember(t) { if (t <= 0f) Light else if (t >= 1f) Dark else mix(Light, Dark, t) }
    CompositionLocalProvider(LocalPalette provides p, content = content)
}

private fun mix(a: Palette, b: Palette, t: Float): Palette {
    fun c(f: (Palette) -> Color) = lerp(f(a), f(b), t)
    return Palette(
        dark = t >= 0.5f,
        bg = c { it.bg }, fg = c { it.fg }, fg2 = c { it.fg2 }, fg3 = c { it.fg3 },
        glass = c { it.glass }, glassStrong = c { it.glassStrong }, stroke = c { it.stroke }, strokeStrong = c { it.strokeStrong },
        fill = c { it.fill }, fill2 = c { it.fill2 },
        accent = c { it.accent }, accent2 = c { it.accent2 }, accentTint = c { it.accentTint },
        ok = c { it.ok }, okDot = c { it.okDot }, err = c { it.err }, warn = c { it.warn },
        card = c { it.card }, edgeTop = c { it.edgeTop }, edgeBottom = c { it.edgeBottom }, accentLine = c { it.accentLine },
        aurora = a.aurora.zip(b.aurora) { x, y -> lerp(x, y, t) }, ring = c { it.ring },
    )
}

/* -------------------------------------------- line icons (24px grid, 1.5 stroke) */

private fun line(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
}.build()

object Glyph {
    val Up = line("up") { moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
    val Down = line("down") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f) }
    val Close = line("close") { moveTo(7f, 7f); lineTo(17f, 17f); moveTo(17f, 7f); lineTo(7f, 17f) }
    val Info = line("info") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.6f); lineTo(12f, 7.7f)
    }
    val Lock = line("lock") {
        moveTo(7f, 11f); lineTo(17f, 11f); arcTo(2f, 2f, 0f, false, true, 19f, 13f); lineTo(19f, 18f)
        arcTo(2f, 2f, 0f, false, true, 17f, 20f); lineTo(7f, 20f); arcTo(2f, 2f, 0f, false, true, 5f, 18f)
        lineTo(5f, 13f); arcTo(2f, 2f, 0f, false, true, 7f, 11f)
        moveTo(8f, 11f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); lineTo(16f, 11f)
    }
    val Sun = line("sun") {
        moveTo(16f, 12f); arcTo(4f, 4f, 0f, true, true, 8f, 12f); arcTo(4f, 4f, 0f, true, true, 16f, 12f)
        moveTo(12f, 2.5f); lineTo(12f, 4.5f); moveTo(12f, 19.5f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(4.5f, 12f); moveTo(19.5f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(6.7f, 6.7f); moveTo(17.3f, 17.3f); lineTo(18.7f, 18.7f)
        moveTo(5.3f, 18.7f); lineTo(6.7f, 17.3f); moveTo(17.3f, 6.7f); lineTo(18.7f, 5.3f)
    }
    val Moon = line("moon") {
        moveTo(20.5f, 13.6f); arcTo(8.5f, 8.5f, 0f, true, true, 10.4f, 3.5f); arcTo(6.6f, 6.6f, 0f, false, false, 20.5f, 13.6f)
    }
    val Pencil = line("pencil") {
        moveTo(4f, 20f); lineTo(4.6f, 16.4f); lineTo(15.6f, 5.4f); lineTo(18.6f, 8.4f); lineTo(7.6f, 19.4f); lineTo(4f, 20f)
        moveTo(13.6f, 7.4f); lineTo(16.6f, 10.4f)
    }
    val Clip = line("clip") {
        moveTo(8f, 4.5f); lineTo(16f, 4.5f); arcTo(2f, 2f, 0f, false, true, 18f, 6.5f); lineTo(18f, 19f)
        arcTo(2f, 2f, 0f, false, true, 16f, 21f); lineTo(8f, 21f); arcTo(2f, 2f, 0f, false, true, 6f, 19f)
        lineTo(6f, 6.5f); arcTo(2f, 2f, 0f, false, true, 8f, 4.5f)
        moveTo(9.5f, 4.5f); lineTo(9.5f, 3f); lineTo(14.5f, 3f); lineTo(14.5f, 4.5f)
        moveTo(9f, 10f); lineTo(15f, 10f); moveTo(9f, 13.5f); lineTo(15f, 13.5f); moveTo(9f, 17f); lineTo(12.5f, 17f)
    }
    val Share = line("share") {
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
        moveTo(5f, 13f); lineTo(5f, 19f); arcTo(2f, 2f, 0f, false, false, 7f, 21f); lineTo(17f, 21f)
        arcTo(2f, 2f, 0f, false, false, 19f, 19f); lineTo(19f, 13f)
    }
}
