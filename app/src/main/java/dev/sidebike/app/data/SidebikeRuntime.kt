package dev.sidebike.app.data

import android.content.Context
import android.util.Log
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.SidebikeEngineFactory
import dev.sidebike.core.api.SidebikeLogger

/**
 * Process-wide owner of the single [SidebikeEngine] instance.
 *
 * The engine must be a singleton per process: it owns the local HTTP proxy listener
 * (`127.0.0.1:31181`) and the DNS provider pool, and two instances would fight over the port.
 * Both the [dev.sidebike.app.controller.DefaultSidebikeController] (UI-driven lifecycle) and the
 * [dev.sidebike.app.vpn.SidebikeVpnService] (which resolves intercepted DNS queries and re-creates
 * the engine when Android restarts the always-on service in a fresh process) need access to it,
 * hence this small holder.
 *
 * Ownership rules:
 *  - The controller normally creates/starts the engine and releases it on stop/failure.
 *  - The service may bootstrap the engine itself when it is started without a controller (Android
 *    always-on VPN restarts deliver a `null` intent). It then marks the engine as service-owned and
 *    is responsible for releasing it during teardown.
 *
 * All methods are thread-safe; creation and release are serialized on [lock].
 */
object SidebikeRuntime {

    private const val TAG = "SidebikeRuntime"
    private val lock = Any()

    @Volatile
    private var engine: SidebikeEngine? = null

    @Volatile
    private var serviceOwned: Boolean = false

    /** Returns the current engine without creating one. */
    fun engineOrNull(): SidebikeEngine? = engine

    /** True when the currently held engine was created by the VPN service (standalone start). */
    fun isServiceOwned(): Boolean = serviceOwned

    /**
     * Creates (if absent) and starts the engine, applying [config] first. Safe to call repeatedly
     * and from both the controller and the service: the returned engine is the process singleton.
     *
     * @throws IllegalStateException when no engine implementation is installed (the `core/engine`
     *   module is missing or failed to bootstrap); callers must surface this as a startup error.
     */
    fun startEngine(context: Context, config: SidebikeConfig): SidebikeEngine = synchronized(lock) {
        val target = engine ?: createEngine(context, config).also { engine = it }
        target.updateConfig(config)
        if (!target.isRunning) target.start()
        serviceOwned = false
        target
    }

    /** Returns the process engine, creating (but not starting) it on first use. */
    fun ensureEngine(context: Context, config: SidebikeConfig, logger: SidebikeLogger? = null): SidebikeEngine =
        synchronized(lock) {
            engine ?: createEngine(context, config, logger).also { engine = it }
        }

    /** Marks the currently held engine as owned by the VPN service (standalone always-on start). */
    fun claimServiceOwnership() {
        synchronized(lock) {
            if (engine != null) serviceOwned = true
        }
    }

    /**
     * Stops and closes the engine, then drops the reference. Returns true when an engine was
     * released. Errors are logged, never thrown (safe to call from `onDestroy`).
     */
    fun releaseEngine(): Boolean = synchronized(lock) {
        val target = engine ?: return false
        engine = null
        serviceOwned = false
        stopQuietly(target)
        true
    }

    /** Releases the engine only when the service created it (used by the service teardown path). */
    fun releaseEngineIfServiceOwned(): Boolean = synchronized(lock) {
        if (!serviceOwned) return false
        serviceOwned = false
        val target = engine ?: return false
        engine = null
        stopQuietly(target)
        true
    }

    /** Test hook: forget any engine without lifecycle calls (JVM tests install a fake factory). */
    internal fun resetForTest() {
        synchronized(lock) {
            engine = null
            serviceOwned = false
        }
    }

    private fun stopQuietly(target: SidebikeEngine) {
        runCatching { target.stop() }.onFailure { Log.w(TAG, "engine.stop() failed during release", it) }
        runCatching { target.close() }.onFailure { Log.w(TAG, "engine.close() failed during release", it) }
    }

    private fun createEngine(context: Context, config: SidebikeConfig, logger: SidebikeLogger? = null): SidebikeEngine {
        // Engine files (CA, caches) live directly under the app-private filesDir.
        return SidebikeEngineFactory.create(context.applicationContext.filesDir, config, logger)
    }
}
