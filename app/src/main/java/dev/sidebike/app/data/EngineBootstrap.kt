package dev.sidebike.app.data

import android.util.Log
import dev.sidebike.core.engine.DefaultSidebikeEngine

/**
 * Installs the real `:core` engine implementation into `SidebikeEngineFactory` at app start.
 *
 * The app module only depends on the frozen `core/api` contracts; the concrete engine
 * ([dev.sidebike.core.engine.DefaultSidebikeEngine], owned by the backend workstream) registers
 * itself through `SidebikeEngineFactory.install(...)`. That registration lives in the engine's
 * companion object and only runs when the class is first touched — which never happens for an
 * interface-only consumer. To make the app deterministic we explicitly call [DefaultSidebikeEngine.install]
 * from `Application.onCreate` before any controller/service creates an engine.
 *
 * Failure is non-fatal: if the core engine module is missing/broken the app still starts, and the
 * controller reports a clear "engine failed to start" error when the user presses start.
 */
object EngineBootstrap {

    private const val TAG = "EngineBootstrap"

    @Volatile
    private var attempted: Boolean = false

    @Volatile
    private var installed: Boolean = false

    /**
     * Installs the engine factory exactly once. Never throws; returns true when the install call
     * succeeded.
     */
    fun ensureInstalled(): Boolean {
        if (attempted) return installed
        synchronized(this) {
            if (attempted) return installed
            installed = runCatching { DefaultSidebikeEngine.install() }
                .onFailure { Log.e(TAG, "SidebikeEngine install failed", it) }
                .isSuccess
            attempted = true
            if (installed) {
                Log.i(TAG, "SidebikeEngine implementation installed")
            } else {
                Log.w(TAG, "SidebikeEngine implementation could not be installed; engine start will fail clearly")
            }
            return installed
        }
    }
}
