package dev.sidebike.app.data

import android.util.Log
import dev.sidebike.core.api.RuleSourceManager
import dev.sidebike.core.api.RuleSourceManagerFactory
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
 * The same bootstrap also installs the remote rule-source manager
 * (`dev.sidebike.core.rules.DefaultRuleSourceManager`, 0.1.1-alpha) when present. That class may not
 * exist during early builds, so it is loaded reflectively and absence is tolerated (the rule-source
 * APIs then surface a clear "not installed" warning instead of crashing the app).
 *
 * Failure is non-fatal: if the core engine module is missing/broken the app still starts, and the
 * controller reports a clear "engine failed to start" error when the user presses start.
 */
object EngineBootstrap {

    private const val TAG = "EngineBootstrap"
    private const val RULE_SOURCE_MANAGER_CLASS = "dev.sidebike.core.rules.DefaultRuleSourceManager"

    @Volatile
    private var attempted: Boolean = false

    @Volatile
    private var installed: Boolean = false

    @Volatile
    private var ruleManagerAttempted: Boolean = false

    @Volatile
    private var ruleManagerInstalled: Boolean = false

    /**
     * Installs the engine factory exactly once. Never throws; returns true when the install call
     * succeeded. Also attempts the (optional) rule-source manager installation.
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
            ensureRuleSourceManagerInstalled()
            return installed
        }
    }

    /**
     * Installs `RuleSourceManagerFactory` if a concrete manager is available. Idempotent and tolerant:
     *  - implementation registers itself through a companion/static `install()` (preferred), or
     *  - it has a no-arg constructor we can wrap in the factory lambda.
     *
     * Returns true when [RuleSourceManagerFactory.create] works afterwards.
     */
    fun ensureRuleSourceManagerInstalled(): Boolean {
        if (ruleManagerAttempted) return ruleManagerInstalled
        synchronized(this) {
            if (ruleManagerAttempted) return ruleManagerInstalled
            ruleManagerAttempted = true
            if (runCatching { RuleSourceManagerFactory.create() }.isSuccess) {
                ruleManagerInstalled = true
                return true
            }
            ruleManagerInstalled = runCatching { installReflectively() }
                .onFailure { Log.w(TAG, "RuleSourceManager install failed", it) }
                .getOrDefault(false)
            if (ruleManagerInstalled) {
                Log.i(TAG, "RuleSourceManager implementation installed")
            } else {
                Log.w(TAG, "RuleSourceManager implementation unavailable ($RULE_SOURCE_MANAGER_CLASS); rule import disabled")
            }
            return ruleManagerInstalled
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun installReflectively(): Boolean {
        val managerClass = Class.forName(RULE_SOURCE_MANAGER_CLASS)

        // 1) Companion / static no-arg install() (same pattern as DefaultSidebikeEngine.install()).
        val companion = runCatching { managerClass.getField("Companion").get(null) }.getOrNull()
        val installMethods = buildList {
            addAll(managerClass.methods.filter { it.name == "install" && it.parameterCount == 0 })
            addAll(
                managerClass.declaredMethods
                    .filter { it.name == "install" && it.parameterCount == 0 }
                    .onEach { it.isAccessible = true },
            )
            companion?.let { instance ->
                addAll(instance.javaClass.methods.filter { it.name == "install" && it.parameterCount == 0 })
            }
        }
        for (method in installMethods) {
            val target = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else companion
            runCatching { method.invoke(target) }.onSuccess {
                if (runCatching { RuleSourceManagerFactory.create() }.isSuccess) return true
            }
        }

        // 2) No-arg constructor: wrap it in the factory lambda ourselves.
        val constructor = managerClass.getDeclaredConstructor().apply { isAccessible = true }
        constructor.newInstance() as? RuleSourceManager ?: return false
        RuleSourceManagerFactory.install { constructor.newInstance() as RuleSourceManager }
        return runCatching { RuleSourceManagerFactory.create() }.isSuccess
    }
}
