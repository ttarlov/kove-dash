package com.kovedash.app.ui.dash

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Presentation that hosts a Compose tree. Wires up the lifecycle / viewmodel-store /
 * saved-state-registry owners on the window decor view so ComposeView is happy outside
 * an Activity. Used to render KoveDash UI into a VirtualDisplay backed by the H.264 encoder.
 */
class DashPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    private val ssrController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrController.savedStateRegistry

    private var composeView: ComposeView? = null
    private var pendingContent: (@Composable () -> Unit)? = null

    init {
        ssrController.performAttach()
    }

    fun setComposeContent(content: @Composable () -> Unit) {
        pendingContent = content
        composeView?.setContent(content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ssrController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val decor = window!!.decorView
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeViewModelStoreOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)

        val view = ComposeView(context)
        composeView = view
        setContentView(view)

        pendingContent?.let { view.setContent(it) }
    }

    override fun show() {
        super.show()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun dismiss() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        vmStore.clear()
        super.dismiss()
    }
}
