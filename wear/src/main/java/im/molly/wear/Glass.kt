/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

/**
 * Liquid glass for the watch, matching the Golden Gate / HyperGNOME 2.1 glass on the phone:
 * tinted see-through surfaces over a soft aurora of the phone's Material You colors, a bright
 * 1dp lip on top and a deeper rim below ("edge banding"), and a specular sheen.
 *
 * Everything is drawn with plain gradients: no blur, no extra layers, so it stays light on the
 * watch's CPU and battery.
 */
object Glass {
  /** HyperGNOME glass opacities: Liquid surfaces, and denser glass for primary actions. */
  const val SURFACE_ALPHA = 0.34f
  const val BUBBLE_ALPHA = 0.72f

  /** The edge band: bright lip on top, a clear middle, a darker rim at the bottom. */
  val rim: Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.58f),
    0.18f to Color.White.copy(alpha = 0.10f),
    0.45f to Color.Black.copy(alpha = 0.18f),
    1f to Color.Black.copy(alpha = 0.32f)
  )

  val border = BorderStroke(1.dp, rim)

  /** Vertical glass fill: a little brighter at the top, like light caught in the glass. */
  fun fill(tint: Color, alpha: Float): Brush = Brush.verticalGradient(
    0f to tint.copy(alpha = (alpha + 0.10f).coerceAtMost(1f)),
    0.5f to tint.copy(alpha = alpha),
    1f to tint.copy(alpha = (alpha - 0.06f).coerceAtLeast(0f))
  )

  /**
   * Readable text on glass that sits over the (dark) watch backdrop: black or white, whichever
   * has the higher WCAG contrast against the composited glass colour.
   */
  fun onGlass(tint: Color, alpha: Float): Color {
    val l = tint.copy(alpha = alpha).compositeOver(Color.Black).luminance()
    val withBlack = (l + 0.05f) / 0.05f
    val withWhite = 1.05f / (l + 0.05f)
    return if (withBlack >= withWhite) Color.Black else Color.White
  }
}

/** A glass surface for any shape. */
fun Modifier.glass(tint: Color, shape: Shape, alpha: Float = Glass.SURFACE_ALPHA): Modifier =
  this.background(Glass.fill(tint, alpha), shape).border(Glass.border, shape)

/** Glass fill as a Painter, for Wear Material buttons (their containerPainter slot). */
@Composable
fun rememberGlassPainter(tint: Color, alpha: Float = Glass.SURFACE_ALPHA): Painter =
  remember(tint, alpha) { BrushPainter(Glass.fill(tint, alpha)) }

/**
 * The backdrop the glass floats over: black (OLED friendly) with two soft glows in the phone's
 * primary and tertiary colors, so the see-through surfaces have something to show.
 */
@Composable
fun GlassBackdrop(content: @Composable BoxScope.() -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val primary = scheme.primary
  val tertiary = scheme.tertiary
  Box(
    modifier = Modifier.fillMaxSize().drawBehind {
      drawRect(Color.Black)
      drawCircle(
        brush = Brush.radialGradient(
          listOf(primary.copy(alpha = 0.34f), Color.Transparent),
          center = Offset(size.width * 0.22f, size.height * 0.18f),
          radius = size.minDimension * 0.75f
        ),
        radius = size.minDimension * 0.75f,
        center = Offset(size.width * 0.22f, size.height * 0.18f)
      )
      drawCircle(
        brush = Brush.radialGradient(
          listOf(tertiary.copy(alpha = 0.26f), Color.Transparent),
          center = Offset(size.width * 0.86f, size.height * 0.88f),
          radius = size.minDimension * 0.70f
        ),
        radius = size.minDimension * 0.70f,
        center = Offset(size.width * 0.86f, size.height * 0.88f)
      )
    },
    content = content
  )
}
