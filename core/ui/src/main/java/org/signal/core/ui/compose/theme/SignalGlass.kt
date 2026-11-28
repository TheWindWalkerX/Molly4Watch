/*
 * Liquid Glass hook for Molly (Molly4Watch fork, Cloud Kaffee). GPL-3.0-only.
 */
package org.signal.core.ui.compose.theme

import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether Compose content is drawn on a Liquid Glass window. The app decides
 * (org.thoughtcrime.securesms.util.MollyGlass); content in dialog and bottom sheet windows never is.
 */
object SignalGlass {
  @JvmStatic
  @Volatile
  var isGlassWindow: (View) -> Boolean = { false }
}

/** True inside [SignalTheme] content that sits on glass: popups drawn from it use a solid container. */
val LocalSignalGlass = staticCompositionLocalOf { false }
