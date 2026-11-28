/*
 * Liquid Glass for Molly (Molly4Watch fork, Cloud Kaffee). GPL-3.0-only, like Molly.
 */
package org.thoughtcrime.securesms.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.signal.core.ui.compose.theme.SignalGlass
import org.signal.core.util.logging.Log
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Liquid Glass: Molly's screens sit on a glass sheet over the blurred wallpaper (Android 12+).
 *
 * On Cloud Kaffee's GrapheneOS build the OS already turns app windows into glass (android.view.HgGlass) and
 * Molly only adds what the OS cannot reach: Compose screens drawn clear ([SignalGlass]) and chats
 * in Signal's wallpaper style, so bubbles, toolbar and compose bar stay readable on glass. On any
 * other Android 12+ ROM the same window glass is done here by [GlassWindow].
 *
 * Off by default outside Cloud Kaffee's GrapheneOS build; Settings > Appearance > Liquid glass.
 * Media, camera, editors, calls and the story viewer always stay opaque.
 */
object MollyGlass {
  private val TAG = Log.tag(MollyGlass::class.java)

  /** Screens that show photos, video or a camera: never glass. */
  private val OPAQUE_ACTIVITIES = setOf(
    "AvatarPreviewActivity",
    "AvatarSelectionActivity",
    "ChatWallpaperActivity",
    "ChatWallpaperPreviewActivity",
    "GiphyActivity",
    "ImageEditorStickerSelectActivity",
    "MediaPreviewV2Activity",
    "MediaSelectionActivity",
    "PhotoEditorActivity",
    "PlacePickerActivity",
    "QrImageSelectionActivity",
    "StoryViewerActivity",
    "ViewOnceMessageActivity",
    "WallpaperCropActivity",
    "WallpaperImageSelectionActivity",
    "WebRtcCallActivity"
  )

  private const val OS_STYLE = "hg_glass_style"
  private val OS_KEYS = listOf(
    OS_STYLE, "hg_glass_windows", "hg_glass_exclude", "hg_glass_apps", "hg_glass_apps_mode",
    "hg_glass_tint", "hg_glass_blur", "high_text_contrast_enabled"
  )

  /** The OS lists (android.view.HgGlass) when never changed in Settings. */
  private const val OS_DEFAULT_APPS = "com.android.settings,app.grapheneos.apps,com.android.dialer,com.android.contacts," +
    "com.android.messaging,com.android.deskclock,com.android.calculator2,com.android.documentsui,app.grapheneos.info," +
    "org.schabi.newpipe,com.maxrave.simpmusic.glass,im.molly.app,im.molly.app.wear"
  private const val OS_DEFAULT_EXCLUDE = "app.grapheneos.camera,com.android.camera2"

  /** [isOn], cached: it is asked for every bound chat item. Cleared when a glass setting changes. */
  @Volatile private var cachedOn: Boolean? = null
  @Volatile private var watching = false
  private var osGlass: Boolean? = null

  init {
    SignalGlass.isGlassWindow = { view -> isGlassWindow(view) }
  }

  /** Make sure core/ui can ask us (called from DynamicTheme before any Compose content). */
  @JvmStatic
  fun install() = Unit

  /**
   * True on Cloud Kaffee's GrapheneOS build, whose framework has the OS-wide Liquid Glass (its settings keys
   * have no stored value until changed, so look for the framework class itself).
   */
  @JvmStatic
  fun osHasGlass(context: Context): Boolean {
    osGlass?.let { return it }
    val has = try {
      Class.forName("android.view.HgGlass", false, null)
      true
    } catch (e: Throwable) {
      false
    }
    osGlass = has
    return has
  }

  /** The Liquid glass switch (Appearance). Defaults to on where the OS has Liquid Glass. */
  @JvmStatic
  fun isEnabled(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 31 && TextSecurePreferences.isLiquidGlassEnabled(context, osHasGlass(context))

  /** Glass is on for Molly's screens right now (switch, OS settings, High Contrast). */
  @JvmStatic
  fun isOn(context: Context): Boolean {
    cachedOn?.let { return it }
    val app = context.applicationContext ?: context
    watch(app)
    val on = isEnabled(app) && Os.read(app).let { it.on && it.windowsFor(app.packageName) }
    cachedOn = on
    return on
  }

  /** Forget [isOn] whenever one of the glass settings changes (the switch itself restarts Molly). */
  private fun watch(app: Context) {
    if (watching) return
    synchronized(this) {
      if (watching) return
      watching = true
      val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
          cachedOn = null
        }
      }
      for (key in OS_KEYS) {
        try {
          app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        } catch (e: Exception) {
          Log.w(TAG, "Can't watch $key", e)
        }
      }
    }
  }

  /**
   * The decision made when each activity was created (main thread only). Kept because our own
   * window overlay makes the theme translucent afterwards.
   */
  private val decided = WeakHashMap<Activity, Boolean>()

  /** Whether [activity] is drawn on glass: a full-screen, non-media screen while glass is on. */
  @JvmStatic
  fun isGlassActivity(activity: Activity): Boolean = decided[activity] ?: decide(activity)

  private fun decide(activity: Activity): Boolean {
    if (activity.javaClass.simpleName in OPAQUE_ACTIVITIES) return false
    val a = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent))
    val floating = a.getBoolean(0, false) || a.getBoolean(1, false)
    a.recycle()
    return !floating && isOn(activity)
  }

  /**
   * Called by [DynamicTheme] right after the theme is set. Outside Cloud Kaffee's GrapheneOS build this installs the
   * window glass; on it the OS does that part.
   */
  @JvmStatic
  fun onActivityCreate(activity: Activity) {
    val glass = decide(activity)
    decided[activity] = glass
    if (Build.VERSION.SDK_INT < 31 || osHasGlass(activity) || !glass) return
    val owner = activity as? LifecycleOwner ?: return
    // before the decor exists, so the window is created translucent (needed for the blur)
    activity.theme.applyStyle(org.thoughtcrime.securesms.R.style.ThemeOverlay_Molly_GlassWindow, true)
    owner.lifecycle.addObserver(GlassWindow(activity))
  }

  /** Compose content drawn in this view's window is on glass (not a dialog or sheet window). */
  private fun isGlassWindow(view: View): Boolean {
    val activity = view.context.findActivity() ?: return false
    val decor = activity.window?.peekDecorView() ?: return false
    return view.rootView === decor && isGlassActivity(activity)
  }

  private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

  /** Same curve as the OS (android.view.HgGlass.glassAlpha), with a floor for text. */
  internal fun glassAlpha(designed: Float, tint: Int, floor: Float): Float {
    val t = tint / 100f
    val a = if (t <= 0.5f) designed * (0.35f + 1.3f * t) else designed + max(0f, 0.97f - designed) * 0.85f * ((t - 0.5f) / 0.5f)
    return max(min(floor, designed), a).coerceIn(0f, 1f)
  }

  internal data class Os(
    val on: Boolean,
    val style: Int,
    val tint: Int,
    val blur: Int,
    val windows: Boolean = true,
    val exclude: String? = null,
    val apps: String? = null,
    val appsMode: Int = 1
  ) {
    /** android.view.HgGlass.glassWindowsFor: master switch, keep-plain list, always list, mode. */
    fun windowsFor(pkg: String): Boolean {
      fun listed(list: String, p: String) = list.split(',').any { it.trim() == p }
      if (!windows) return false
      if (listed(exclude ?: OS_DEFAULT_EXCLUDE, pkg)) return false
      if (listed(apps ?: OS_DEFAULT_APPS, pkg)) return true
      return appsMode != 0
    }

    companion object {
      fun read(context: Context): Os {
        val cr = context.contentResolver
        fun int(key: String, def: Int) = try {
          Settings.Secure.getInt(cr, key, def)
        } catch (e: Exception) {
          def
        }
        fun str(key: String) = try {
          Settings.Secure.getString(cr, key)
        } catch (e: Exception) {
          null
        }
        val style = int(OS_STYLE, 1).coerceIn(0, 2)
        return Os(
          on = style != 0 && int("high_text_contrast_enabled", 0) != 1,
          style = if (style == 0) 1 else style,
          tint = int("hg_glass_tint", 50).coerceIn(0, 100),
          blur = int("hg_glass_blur", 100).coerceIn(0, 200),
          windows = int("hg_glass_windows", 1) != 0,
          exclude = str("hg_glass_exclude"),
          apps = str("hg_glass_apps"),
          appsMode = int("hg_glass_apps_mode", 1)
        )
      }
    }
  }

  /**
   * Window glass for ROMs without the OS feature (a trimmed port of Cloud Kaffee's android.view.HgGlass
   * AppGlass, by the same author). The window shows the wallpaper, blurred by the system, under
   * a tinted sheet; before each frame, page-wide neutral backgrounds painted in the page colour
   * are cleared and narrower sheets / cards thinned, so the glass shows. Accent fills, images and
   * anything narrower than half the window are left alone. Battery saver (no blur): the sheet
   * becomes nearly opaque.
   */
  @RequiresApi(31)
  private class GlassWindow(private val activity: Activity) :
    DefaultLifecycleObserver,
    ViewTreeObserver.OnPreDrawListener,
    ViewTreeObserver.OnGlobalLayoutListener,
    ViewTreeObserver.OnScrollChangedListener,
    Consumer<Boolean> {

    private val handler = Handler(Looper.getMainLooper())
    private val sheet = ColorDrawable()
    private val touched = WeakHashMap<Drawable, Boolean>()
    private val skipped = WeakHashMap<Drawable, Int>()
    private var base = 0
    private var night = -1
    private var blurOn = true
    private var cardAlpha = 255
    private var dirty = true
    private var applied = false
    private var failed = false
    private val observer = object : ContentObserver(handler) {
      override fun onChange(selfChange: Boolean) {
        apply()
        dirty = true
        activity.window?.peekDecorView()?.invalidate()
      }
    }

    override fun onCreate(owner: LifecycleOwner) {
      val wm = activity.getSystemService(WindowManager::class.java)
      blurOn = wm.isCrossWindowBlurEnabled
      wm.addCrossWindowBlurEnabledListener(activity.mainExecutor, this)
      for (key in OS_KEYS) activity.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
      val decor = activity.window.decorView
      decor.viewTreeObserver.addOnPreDrawListener(this)
      decor.viewTreeObserver.addOnGlobalLayoutListener(this)
      decor.viewTreeObserver.addOnScrollChangedListener(this)
      apply()
    }

    override fun onDestroy(owner: LifecycleOwner) {
      activity.getSystemService(WindowManager::class.java).removeCrossWindowBlurEnabledListener(this)
      activity.contentResolver.unregisterContentObserver(observer)
      activity.window?.peekDecorView()?.viewTreeObserver?.let {
        it.removeOnPreDrawListener(this)
        it.removeOnGlobalLayoutListener(this)
        it.removeOnScrollChangedListener(this)
      }
    }

    override fun accept(enabled: Boolean) {
      if (enabled == blurOn) return
      blurOn = enabled
      apply()
    }

    private fun readBase() {
      val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
      val tv = TypedValue()
      var b = 0
      if (activity.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true) &&
        tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
      ) {
        b = tv.data
      }
      if ((b ushr 24) < 0x80) b = if (dark) 0xFF111314.toInt() else 0xFFF4F5F7.toInt()
      base = b or 0xFF000000.toInt()
      night = if (dark) 1 else 0
    }

    private fun apply() {
      if (failed) return
      try {
        val window = activity.window ?: return
        readBase()
        val os = Os.read(activity)
        val dark = night == 1
        val liquid = os.style == 2
        val blur = blurOn && os.blur > 0
        val on = os.on && os.windowsFor(activity.packageName)
        var a = if (liquid) glassAlpha(if (dark) 0.58f else 0.64f, os.tint, 0.40f) else glassAlpha(if (dark) 0.74f else 0.78f, os.tint, 0.55f)
        if (!blur) a = max(a, 0.92f)
        if (!on) a = 1f // High Contrast or glass turned off while open: solid until the next start
        sheet.color = (base and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        cardAlpha = (min(1f, glassAlpha(if (liquid) 0.55f else 0.74f, os.tint, 0.38f)) * 255).toInt()
        if (!applied) {
          window.setFormat(PixelFormat.TRANSLUCENT)
          window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
          window.setBackgroundDrawable(sheet)
          window.statusBarColor = Color.TRANSPARENT
          window.navigationBarColor = Color.TRANSPARENT
          applied = true
        } else {
          sheet.invalidateSelf()
        }
        val radius = ((if (liquid) 44 else 64) * activity.resources.displayMetrics.density * os.blur / 100f).toInt()
        window.setBackgroundBlurRadius(if (blur && on) max(radius, 1) else 0)
        for ((d, card) in touched) d?.alpha = if (!on) 255 else if (card) cardAlpha else 0
      } catch (t: Throwable) {
        fail(t)
      }
    }

    override fun onGlobalLayout() {
      if (!applied) return
      val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
      if (night != (if (dark) 1 else 0)) {
        skipped.clear()
        apply()
      }
      dirty = true
    }

    /** Rows bound while a list scrolls are not a layout pass: look at them too. */
    override fun onScrollChanged() {
      dirty = true
    }

    override fun onPreDraw(): Boolean {
      if (!dirty || !applied || failed) return true
      dirty = false
      try {
        val decor = activity.window.decorView
        clearContent(decor, decor, decor.width, decor.height)
      } catch (t: Throwable) {
        fail(t)
      }
      return true
    }

    /** Something went wrong: give the app its own look back and stop. */
    private fun fail(t: Throwable) {
      Log.w(TAG, "Liquid glass off for this screen", t)
      failed = true
      for ((d, _) in touched) d?.alpha = 255
      touched.clear()
    }

    private fun clearContent(v: View, decor: View, fullW: Int, fullH: Int) {
      if (v.visibility != View.VISIBLE) return
      if (v !== decor && v.width < fullW / 2) return
      val d = if (v !== decor) v.background else null
      if (d != null && v.width > 0 && fullW > 0 && fullH > 0) {
        val leaf = solidLeaf(d)
        val size = (v.width shl 16) xor v.height xor (fillState(v) shl 30)
        if (leaf != null && !touched.containsKey(leaf) && skipped[leaf] != size) {
          var card: Boolean? = null
          val fill = solidFill(leaf)
          if (fill != 0 && Color.alpha(fill) >= 0xF0 && isSurface(fill, base)) {
            val wide = v.width >= fullW * 4 / 5
            val h = v.height
            if (near(fill, base, 12) && wide) {
              card = !(h >= fullH * 7 / 10 || h < fullH / 4)
            } else if (v.width >= fullW / 2) {
              card = true
            }
          }
          if (card != null) {
            d.mutate()
            val m = solidLeaf(d)
            if (m != null) {
              touched[m] = card
              m.alpha = if (card) cardAlpha else 0
            }
          } else {
            skipped[leaf] = size
          }
        }
      }
      if (v is ViewGroup) for (i in 0 until v.childCount) clearContent(v.getChildAt(i), decor, fullW, fullH)
    }

    private fun fillState(v: View) = (if (v.isPressed || v.isActivated) 1 else 0) or (if (v.isSelected) 2 else 0)

    private fun solidLeaf(start: Drawable?): Drawable? {
      var d = start
      repeat(6) {
        when (d) {
          null -> return null
          is ColorDrawable, is GradientDrawable -> return d
          is LayerDrawable -> {
            val layers = d as LayerDrawable
            d = (0 until layers.numberOfLayers).firstOrNull { layers.getId(it) != android.R.id.mask }?.let { layers.getDrawable(it) }
          }
          is DrawableWrapper -> d = (d as DrawableWrapper).drawable
          is DrawableContainer -> d = d!!.current
          else -> return null
        }
      }
      return null
    }

    private fun solidFill(d: Drawable): Int = when (d) {
      is ColorDrawable -> d.color
      is GradientDrawable -> d.color?.defaultColor ?: 0
      else -> 0
    }

    private fun isSurface(c: Int, base: Int): Boolean {
      val r = Color.red(c)
      val g = Color.green(c)
      val b = Color.blue(c)
      if (max(r, max(g, b)) - min(r, min(g, b)) >= 40) return false
      val lum = (r * 3 + g * 6 + b) / 10
      val baseLum = (Color.red(base) * 3 + Color.green(base) * 6 + Color.blue(base)) / 10
      return abs(lum - baseLum) < 48
    }

    private fun near(a: Int, b: Int, tol: Int) =
      abs(Color.red(a) - Color.red(b)) <= tol && abs(Color.green(a) - Color.green(b)) <= tol && abs(Color.blue(a) - Color.blue(b)) <= tol
  }
}
