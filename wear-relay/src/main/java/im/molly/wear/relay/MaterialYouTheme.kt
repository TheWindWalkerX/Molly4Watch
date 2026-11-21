/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import android.content.Context
import android.os.Build
import im.molly.wear.protocol.WearTheme

/**
 * The phone's Material You palette mapped to Material 3 dark-scheme roles (watches are always dark),
 * using the same tones Material 3 uses for its dynamic dark scheme. Null before Android 12.
 */
object MaterialYouTheme {
  fun read(context: Context): WearTheme? {
    if (Build.VERSION.SDK_INT < 31) return null
    fun c(id: Int) = context.getColor(id)
    return WearTheme(
      primary = c(android.R.color.system_accent1_200),
      onPrimary = c(android.R.color.system_accent1_800),
      primaryContainer = c(android.R.color.system_accent1_700),
      onPrimaryContainer = c(android.R.color.system_accent1_100),
      secondary = c(android.R.color.system_accent2_200),
      onSecondary = c(android.R.color.system_accent2_800),
      secondaryContainer = c(android.R.color.system_accent2_700),
      onSecondaryContainer = c(android.R.color.system_accent2_100),
      tertiary = c(android.R.color.system_accent3_200),
      onTertiary = c(android.R.color.system_accent3_800),
      tertiaryContainer = c(android.R.color.system_accent3_700),
      onTertiaryContainer = c(android.R.color.system_accent3_100),
      surfaceContainerLow = c(android.R.color.system_neutral1_900),
      surfaceContainer = c(android.R.color.system_neutral1_800),
      surfaceContainerHigh = c(android.R.color.system_neutral2_800),
      onSurface = c(android.R.color.system_neutral1_100),
      onSurfaceVariant = c(android.R.color.system_neutral2_200),
      outline = c(android.R.color.system_neutral2_400),
      outlineVariant = c(android.R.color.system_neutral2_700)
    )
  }
}
