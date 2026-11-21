/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import im.molly.wear.bridge.WearBridge
import im.molly.wear.ipc.WearIpc
import java.util.concurrent.Executors

/** Setup/status screen. Opening it once also takes the relay out of Android's "stopped" state. */
class StatusActivity : Activity() {
  private val worker = Executors.newSingleThreadExecutor()
  private lateinit var status: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pad = (16 * resources.displayMetrics.density).toInt()
    val title = TextView(this).apply {
      setText(R.string.app_name)
      textSize = 22f
      setPadding(0, 0, 0, pad)
    }
    status = TextView(this).apply { textSize = 16f }
    val refresh = Button(this).apply {
      text = "Refresh"
      setOnClickListener { refresh() }
    }
    val publish = Button(this).apply {
      text = "Send to watch now"
      setOnClickListener { WearBridge.onStateChanged() }
    }
    setContentView(
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Android 15+ draws edge-to-edge: keep content clear of the status and navigation bars.
        setOnApplyWindowInsetsListener { view, insets ->
          val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
          view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
          insets
        }
        addView(title)
        addView(status)
        addView(refresh)
        addView(publish)
      }
    )
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  override fun onDestroy() {
    worker.shutdownNow()
    super.onDestroy()
  }

  private fun refresh() {
    status.text = "Checking…"
    val molly = (application as RelayApp).molly
    worker.execute {
      val text = buildString {
        appendLine("Molly package: ${molly.mollyPackage}")
        val installed = try {
          packageManager.getPackageInfo(molly.mollyPackage, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
          false
        }
        if (!installed) {
          appendLine("Molly: not installed")
          return@buildString
        }
        val service = runCatching { molly.get() }.getOrNull()
        when {
          service == null -> appendLine("Molly: can't connect (is it this fork, signed with the same key?)")
          runCatching { service.ipcVersion() }.getOrNull() != WearIpc.IPC_VERSION -> appendLine("Molly: different IPC version")
          runCatching { service.isLocked }.getOrDefault(true) -> appendLine("Molly: connected, locked")
          else -> {
            appendLine("Molly: connected, unlocked")
            // Diagnostics without content: what a snapshot would carry.
            runCatching {
              val bytes = service.loadSnapshot(System.currentTimeMillis())
              val snapshot = WearIpc.decodeSnapshot(bytes)
              val theme = MaterialYouTheme.read(this@StatusActivity)
              appendLine("Snapshot: ${snapshot.threads.size} chats, ${bytes.size} bytes")
              appendLine("Avatars: ${snapshot.threads.count { it.avatar != null }}, chat colors: ${snapshot.threads.count { it.chatColor != null }}")
              appendLine("Phone theme: " + (theme?.let { "primary #%08X, surface #%08X".format(it.primary, it.surfaceContainer) } ?: "unavailable"))
            }.onFailure { appendLine("Snapshot failed: ${it.javaClass.simpleName}") }
          }
        }
      }
      runOnUiThread { if (!isDestroyed) status.text = text }
    }
  }
}
