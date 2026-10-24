package dev.sidebike.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.sidebike.app.controller.SidebikeController
import dev.sidebike.app.data.SidebikeConfigStore
import dev.sidebike.app.data.SidebikeRuntime

/**
 * Debug-only fault injection for QA (E5). Declared in `app/src/debug/AndroidManifest.xml`, exported
 * and protected by `android.permission.DUMP`, so only `adb shell` (or system/root) can trigger it.
 * Nothing in this file is part of a release build.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a dev.sidebike.app.debug.KILL_PROXY   -n dev.sidebike.app.debug/dev.sidebike.app.debug.FaultInjectionReceiver
 * adb shell am broadcast -a dev.sidebike.app.debug.CRASH_ENGINE -n dev.sidebike.app.debug/dev.sidebike.app.debug.FaultInjectionReceiver
 * adb shell am broadcast -a dev.sidebike.app.debug.DUMP_STATE   -n dev.sidebike.app.debug/dev.sidebike.app.debug.FaultInjectionReceiver
 * # optional package selection instead of -n:
 * adb shell am broadcast -a dev.sidebike.app.debug.KILL_PROXY -p dev.sidebike.app.debug
 * ```
 */
class FaultInjectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (val action = intent.action) {
            ACTION_KILL_PROXY -> {
                // Kill the engine's proxy/DNS listeners abruptly (no controller stop path). The
                // service's health monitor / controller proxy watchdog must tear the VPN down and
                // the system network must recover on its own.
                Log.w(TAG, "QA fault injection: killing proxy/engine abruptly")
                runCatching { SidebikeRuntime.engineOrNull()?.stop() }
                    .onFailure { Log.e(TAG, "engine.stop() failed during fault injection", it) }
                Log.w(TAG, "QA fault injection: engine stop() returned")
            }
            ACTION_CRASH_ENGINE -> {
                // Simulate an engine process/instance crash: close the engine and drop the runtime
                // reference without a clean controller stop.
                Log.w(TAG, "QA fault injection: crashing engine instance")
                runCatching { SidebikeRuntime.engineOrNull()?.close() }
                    .onFailure { Log.e(TAG, "engine.close() failed during fault injection", it) }
                runCatching { SidebikeRuntime.engineOrNull()?.stop() }
                Log.w(TAG, "QA fault injection: engine crashed")
            }
            ACTION_DUMP_STATE -> dumpState(appContext)
            else -> Log.w(TAG, "Unknown fault action: $action")
        }
    }

    /**
     * Dumps ui state, config rule counts and engine stats to logcat (single multi-line log entry
     * prefixed with `DSB_FAULT_DUMP`) so QA can grep/attach the full snapshot.
     */
    private fun dumpState(context: Context) {
        val report = buildString {
            appendLine("=== Dev-Sidebike state dump ===")
            val config = runCatching { SidebikeConfigStore(context).load() }.getOrNull()
            if (config != null) {
                appendLine("config.interceptsDomains=${config.intercepts.size}")
                appendLine("config.interceptPaths=${config.intercepts.values.sumOf { it.size }}")
                appendLine("config.whiteList=${config.whiteList.size}")
                appendLine("config.preSetIpDomains=${config.preSetIpList.size}")
                appendLine("config.excludeIpList=${config.excludeIpList.size}")
                appendLine("config.dnsProviders=${config.dns.providers.size}")
                appendLine("config.dnsMapping=${config.dns.mapping.size}")
                appendLine("config.domesticAllowList=${config.domesticAllowList.size}")
                appendLine("config.proxyPort=${config.proxy.port} autoStartOnBoot=${config.app.autoStartOnBoot}")
            } else {
                appendLine("config: <unavailable>")
            }
            val engine = SidebikeRuntime.engineOrNull()
            if (engine != null) {
                appendLine("engine.isRunning=${runCatching { engine.isRunning }.getOrNull()}")
                appendLine("engine.stats=${runCatching { engine.stats() }.getOrNull()}")
                appendLine("engine.currentConfig.intercepts=${runCatching { engine.currentConfig().intercepts.size }.getOrNull()}")
            } else {
                appendLine("engine: <none>")
            }
            val controller = runCatching { SidebikeController.get(context) }.getOrNull()
            if (controller != null) {
                val state = controller.state.value
                appendLine("ui.status=${state.status} message=${state.statusMessage}")
                appendLine("ui.lastError=${state.lastError}")
                appendLine("ui.vpnActive=${state.vpnActive} proxyRunning=${state.proxyRunning}")
                appendLine("ui.effectiveProxyPort=${state.effectiveProxyPort}")
                appendLine("ui.systemProxyRegistered=${state.systemProxyRegistered}")
                appendLine("ui.privateDnsActive=${state.privateDnsActive} lockdown=${state.lockdownDetected}")
                appendLine("ui.stats=${state.stats}")
                appendLine("ui.logsTail=")
                controller.logs.value.takeLast(15).forEach { entry ->
                    appendLine("  [${entry.level}] ${entry.tag}: ${entry.message}")
                }
            } else {
                appendLine("ui: <controller unavailable>")
            }
        }
        // One log entry per line keeps `adb logcat -s DSB_FAULT_DUMP` output readable.
        report.lineSequence().forEach { line ->
            Log.i(DUMP_TAG, line)
        }
        Log.i(TAG, "QA state dump written to logcat tag $DUMP_TAG")
    }

    private companion object {
        private const val TAG = "FaultInjection"
        private const val DUMP_TAG = "DSB_FAULT_DUMP"
        private const val ACTION_KILL_PROXY = "dev.sidebike.app.debug.KILL_PROXY"
        private const val ACTION_CRASH_ENGINE = "dev.sidebike.app.debug.CRASH_ENGINE"
        private const val ACTION_DUMP_STATE = "dev.sidebike.app.debug.DUMP_STATE"
    }
}
