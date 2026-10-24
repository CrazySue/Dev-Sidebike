package dev.sidebike.app.data

import android.util.Log
import dev.sidebike.core.api.SidebikeEngineFactory
import java.lang.reflect.Modifier

/**
 * Installs the real `:core` engine implementation into [SidebikeEngineFactory] at app start.
 *
 * The app module only depends on the frozen `core/api` contracts; the concrete engine
 * (`dev.sidebike.core.engine.DefaultSidebikeEngine`, owned by the backend workstream) registers
 * itself through `SidebikeEngineFactory.install(...)`. Historically that registration happened
 * lazily inside `DefaultSidebikeEngine`'s static initializer; a class initializer only runs when the
 * class is first touched, which never happens for an interface-only consumer. To make the app
 * deterministic we explicitly bootstrap it from `Application.onCreate`.
 *
 * TODO(android-dev): once `dev.sidebike.core.engine.DefaultSidebikeEngine` is available in the
 *  compiled classpath, replace the reflective body below with a direct call:
 *  `DefaultSidebikeEngine.install()`. Reflection is only an interim scaffolding so the app module
 *  keeps compiling while the engine workstream lands.
 */
object EngineBootstrap {

    private const val TAG = "EngineBootstrap"
    private const val ENGINE_IMPL_CLASS = "dev.sidebike.core.engine.DefaultSidebikeEngine"

    @Volatile
    private var attempted: Boolean = false

    @Volatile
    private var installed: Boolean = false

    /**
     * Installs the engine factory exactly once. Never throws; returns true when an install method
     * was found and invoked successfully, false when the engine implementation is not on the
     * classpath (the controller then reports a clear startup error instead of crashing).
     */
    fun ensureInstalled(): Boolean {
        if (attempted) return installed
        synchronized(this) {
            if (attempted) return installed
            installed = installReflectively()
            attempted = true
            if (installed) {
                Log.i(TAG, "SidebikeEngine implementation installed")
            } else {
                Log.w(TAG, "SidebikeEngine implementation not available yet ($ENGINE_IMPL_CLASS.install missing)")
            }
            return installed
        }
    }

    private fun installReflectively(): Boolean = runCatching {
        val engineClass = Class.forName(ENGINE_IMPL_CLASS)
        val companion = runCatching { engineClass.getField("Companion").get(null) }.getOrNull()

        val candidates = buildList {
            addAll(engineClass.methods.filter { it.name == "install" && it.parameterCount == 0 })
            addAll(
                engineClass.declaredMethods
                    .filter { it.name == "install" && it.parameterCount == 0 }
                    .onEach { it.isAccessible = true },
            )
            companion?.let { instance ->
                addAll(instance.javaClass.methods.filter { it.name == "install" && it.parameterCount == 0 })
            }
        }.distinctBy { method ->
            "${method.declaringClass.name}#${method.name}${Modifier.isStatic(method.modifiers)}"
        }

        for (method in candidates) {
            val target = if (Modifier.isStatic(method.modifiers)) {
                null
            } else {
                companion ?: continue
            }
            runCatching { method.invoke(target) }
                .onSuccess { return true }
                .onFailure { Log.w(TAG, "Reflective engine install via ${method.declaringClass.name} failed", it) }
        }

        // `install` may also be a top-level function compiled into `DefaultSidebikeEngineKt`.
        val fileClass = runCatching { Class.forName("${ENGINE_IMPL_CLASS}Kt") }.getOrNull()
        fileClass?.let { type ->
            for (method in type.methods) {
                if (method.name != "install" || method.parameterCount != 0) continue
                runCatching { method.invoke(null) }.onSuccess { return true }
                    .onFailure { Log.w(TAG, "Reflective engine install via ${type.name} failed", it) }
            }
        }
        false
    }.getOrElse { error ->
        Log.w(TAG, "Engine bootstrap failed", error)
        false
    }
}
