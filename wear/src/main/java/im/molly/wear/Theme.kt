/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.wear.compose.material3.ColorScheme
import im.molly.wear.protocol.WearTheme
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** The phone's Material You palette as a Wear Material 3 scheme; Wear defaults when unknown. */
fun WearTheme?.toColorScheme(): ColorScheme {
  val t = this ?: return ColorScheme()
  return ColorScheme(
    primary = Color(t.primary),
    primaryDim = Color(t.primary).copy(alpha = 0.85f),
    primaryContainer = Color(t.primaryContainer),
    onPrimary = Color(t.onPrimary),
    onPrimaryContainer = Color(t.onPrimaryContainer),
    secondary = Color(t.secondary),
    secondaryDim = Color(t.secondary).copy(alpha = 0.85f),
    secondaryContainer = Color(t.secondaryContainer),
    onSecondary = Color(t.onSecondary),
    onSecondaryContainer = Color(t.onSecondaryContainer),
    tertiary = Color(t.tertiary),
    tertiaryDim = Color(t.tertiary).copy(alpha = 0.85f),
    tertiaryContainer = Color(t.tertiaryContainer),
    onTertiary = Color(t.onTertiary),
    onTertiaryContainer = Color(t.onTertiaryContainer),
    surfaceContainerLow = Color(t.surfaceContainerLow),
    surfaceContainer = Color(t.surfaceContainer),
    surfaceContainerHigh = Color(t.surfaceContainerHigh),
    onSurface = Color(t.onSurface),
    onSurfaceVariant = Color(t.onSurfaceVariant),
    outline = Color(t.outline),
    outlineVariant = Color(t.outlineVariant)
  )
}

/** Decodes a snapshot avatar; null if absent or corrupt. */
fun decodeAvatar(base64: String?): ImageBitmap? = base64?.let {
  runCatching {
    val bytes = Base64.decode(it, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
  }.getOrNull()
}

/** Chat-list time like Google Messages: "now", "5 min", "14:02", "Tue", "Nov 3". */
fun listTime(sentAt: Long, now: Long = System.currentTimeMillis()): String {
  val age = now - sentAt
  if (age < 60_000) return "now"
  if (age < 60 * 60_000) return "${age / 60_000} min"
  val then = Calendar.getInstance().apply { timeInMillis = sentAt }
  val today = Calendar.getInstance().apply { timeInMillis = now }
  val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
  return when {
    sameDay -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sentAt))
    age < 6 * 24 * 60 * 60_000L -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(sentAt))
    else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(Date(sentAt))
  }
}
