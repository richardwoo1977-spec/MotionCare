// ver 1.1.0 | 2026-11-05 19:34
package app.motioncare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em

/** The one Text used in the app. Size is in design pixels. */
@Composable
fun Txt(
    text: String,
    size: Int = 32,
    color: Color = Palette.Ink,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    /** Letter spacing in em (the design uses -0.02 to -0.03 for big numbers and titles). */
    spacing: Float = 0f,
    /** Cut a too long text with "..." instead of clipping it. */
    ellipsis: Boolean = false
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fs(size),
        fontWeight = weight,
        textAlign = align,
        maxLines = maxLines,
        letterSpacing = spacing.em,
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    background: Color = Palette.Card,
    pad: Int = 36,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .background(background, RoundedCornerShape(u(28)))
            .border(u(2), Palette.Line, RoundedCornerShape(u(28)))
            .padding(u(pad)),
        content = content
    )
}

/** Touch target is at least 88 design px high. [widthPx] null = fill the width. */
@Composable
fun BigButton(
    text: String,
    heightPx: Int,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    widthPx: Int? = null,
    enabled: Boolean = true,
    size: Int = 40,
    sub: String? = null,
    onClick: () -> Unit
) {
    val w = if (widthPx != null) Modifier.width(u(widthPx)) else Modifier.fillMaxWidth()
    Box(
        modifier
            .then(w)
            .height(u(heightPx))
            .background(if (enabled) bg else Palette.Seg, RoundedCornerShape(u(24)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(text, size, if (enabled) fg else Palette.Muted, FontWeight.Bold, align = TextAlign.Center)
            if (sub != null) Txt(sub, 30, if (enabled) fg else Palette.Muted, align = TextAlign.Center)
        }
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, size: Int = 30) {
    Box(
        Modifier
            .background(bg, RoundedCornerShape(u(40)))
            .padding(horizontal = u(24), vertical = u(10))
    ) {
        Txt(text, size, fg, FontWeight.Bold)
    }
}
