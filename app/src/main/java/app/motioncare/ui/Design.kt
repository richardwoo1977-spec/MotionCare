// ver 1.3.7 | 2026-11-12 14:10
package app.motioncare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/** Shown on the 점검 screen. */
const val VERSION_LABEL = "ver 2.2.0 | 2026-11-12 14:10"

/**
 * The screens are drawn on a 1706 x 1210 px canvas (Pleos Connect app area), like TripSign.
 * LocalUnit = real pixels per design pixel, so the layout matches the design on any density.
 */
val LocalUnit = staticCompositionLocalOf { 1f }

const val DESIGN_W = 1706f
const val DESIGN_H = 1210f

@Composable
fun u(designPx: Number): Dp {
    val unit = LocalUnit.current
    return with(LocalDensity.current) { (designPx.toFloat() * unit).toDp() }
}

/** Text size in design pixels; ignores the system font scale (glance layout is fixed). Minimum used in the app: 30. */
@Composable
fun fs(designPx: Number): TextUnit {
    val unit = LocalUnit.current
    return with(LocalDensity.current) { (designPx.toFloat() * unit).toSp() }
}

/**
 * Dark Pleos-like palette of the confirmed home design: one accent (mint), white and grey text.
 * Text colours keep >= 4.5:1 on their surface; the accent carries dark text (9.5:1), never white (1.8:1).
 */
object Palette {
    val Bg = Color(0xFF0B1215)
    val Card = Color(0xFF1C2325)
    val Rail = Color(0xFF15262C)
    val RailLine = Color(0x14FFFFFF)
    val Primary = Color(0xFF4FD6BC)
    val PrimarySoft = Color(0xFF16332F)
    val Ink = Color(0xFFF2F6F7)
    val Sub = Color(0xFFA6B2B6)
    val Muted = Color(0xFF8E9CA1)
    val Line = Color(0xFF2F3A3E)
    val Tile = Color(0xFF232C2F)
    val Seg = Color(0xFF2A3437)
    val Gray = Color(0xFF6B7A80)
    val Ok = Color(0xFF5BD6A0)
    val OkSoft = Color(0xFF173326)
    val Warn = Color(0xFFF2B35E)
    val WarnSoft = Color(0xFF3A2D16)
    val Bad = Color(0xFFFF8A7A)
    val BadSoft = Color(0xFF3C1D1A)
    val OnPrimary = Color(0xFF04201A)
}
