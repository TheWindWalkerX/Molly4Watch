/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import im.molly.wear.ipc.IMollyWearGateway
import im.molly.wear.ipc.WearIpc

/**
 * Binds Molly's gateway service on demand and unbinds after a short idle period, so the relay doesn't
 * keep Molly's process alive. [get] blocks while connecting: never call it on the main thread.
 */
class MollyConnection(context: Context) {
  private val app = context.applicationContext
  val mollyPackage: String = app.packageName.removeSuffix(WearIpc.RELAY_PACKAGE_SUFFIX)

  private val lock = Object()
  private var bound = false
  private var service: IMollyWearGateway? = null
  private val main = Handler(Looper.getMainLooper())
  private val idleUnbind = Runnable { unbind() }

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      synchronized(lock) {
        service = IMollyWearGateway.Stub.asInterface(binder)
        lock.notifyAll()
      }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      synchronized(lock) { service = null }
    }

    override fun onBindingDied(name: ComponentName) = unbind()

    override fun onNullBinding(name: ComponentName) = unbind()
  }

  fun get(): IMollyWearGateway? {
    check(Looper.myLooper() != Looper.getMainLooper()) { "MollyConnection.get() blocks; call it off the main thread" }
    scheduleIdleUnbind()
    synchronized(lock) {
      service?.let { return it }
      if (!bound) {
        val intent = Intent(WearIpc.ACTION_BIND_GATEWAY).setPackage(mollyPackage)
        bound = try {
          app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
          Log.w(TAG, "Not allowed to bind Molly (signed with another key?)", e)
          false
        }
        if (!bound) {
          runCatching { app.unbindService(connection) }
          return null
        }
      }
      val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
      while (service == null) {
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0) break
        lock.wait(left)
      }
      if (service == null) Log.w(TAG, "Timed out connecting to Molly")
      return service
    }
  }

  private fun scheduleIdleUnbind() {
    main.removeCallbacks(idleUnbind)
    main.postDelayed(idleUnbind, IDLE_UNBIND_MS)
  }

  private fun unbind() {
    synchronized(lock) {
      if (bound) runCatching { app.unbindService(connection) }
      bound = false
      service = null
    }
  }

  companion object {
    private const val TAG = "MollyWear/Connection"
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val IDLE_UNBIND_MS = 30_000L
  }
}
