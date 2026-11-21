/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: WatchViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { WatchApp(viewModel.controller) }
  }

  override fun onStart() {
    super.onStart()
    viewModel.controller.refresh()
  }
}

class WatchViewModel(application: Application) : AndroidViewModel(application) {
  private val link = DataLayerPhoneLink(application).also { it.attach() }
  val controller = WatchController(link, viewModelScope)

  init {
    viewModelScope.launch { link.loadStoredSnapshot() }
    controller.start()
  }

  override fun onCleared() {
    link.detach()
  }
}
