package dev.sidebike.app

import android.app.Application
import dev.sidebike.app.controller.DefaultSidebikeController
import dev.sidebike.app.controller.SidebikeControllerHolder
import dev.sidebike.app.data.EngineBootstrap

/**
 * Application entry point.
 *
 * Two responsibilities:
 *  1. Install the real `:core` engine implementation into `SidebikeEngineFactory` before any
 *     controller/service tries to create an engine. (Historically the engine registered itself in a
 *     static initializer that an interface-only consumer never triggers.)
 *  2. Register the process-wide [DefaultSidebikeController] so `SidebikeController.get(context)`
 *     (and therefore the whole Compose UI) always talks to the real controller instead of the
 *     placeholder.
 *
 * Both steps are defensive: an unavailable engine must not crash app startup; it surfaces later as a
 * clear "engine failed to start" error state when the user presses start.
 */
class DevSidebikeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        EngineBootstrap.ensureInstalled()
        SidebikeControllerHolder.install { context -> DefaultSidebikeController.get(context) }
    }
}
