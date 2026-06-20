package org.koharu.miyo.core.parser.tachiyomi

import android.util.Log
import org.koharu.miyo.core.parser.DynamicParserManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized error handler for all plugin/extension types (JAR and APK).
 *
 * Provides:
 * 1. Unified error tracking across Kotatsu JAR plugins and Tachiyomi APK extensions
 * 2. User-friendly error messages
 * 3. Error deduplication (same error won't be reported multiple times)
 * 4. Automatic error recovery detection (when a previously-failing plugin starts working)
 * 5. Crash-safe wrappers for plugin operations
 */
object PluginErrorHandler {

        private const val TAG = "PluginErrorHandler"

        /** Maximum errors to keep per plugin before trimming. */
        private const val MAX_ERRORS_PER_PLUGIN = 10

        /** All tracked errors, keyed by plugin file name. */
        @PublishedApi internal val errors = ConcurrentHashMap<String, MutableList<PluginError>>()

        /** Timestamp of last error for each plugin, used for deduplication. */
        @PublishedApi internal val lastErrorTime = ConcurrentHashMap<String, Long>()

        /** Minimum interval between duplicate error reports (ms). */
        private const val DEDUP_INTERVAL_MS = 5000L

        // ============================================================================
        // Error recording
        // ============================================================================

        /**
         * Record an error from a JAR plugin.
         */
        fun recordJarError(jarName: String, reason: String, exception: Throwable?) {
                recordError(jarName, PluginType.KOTATSU_JAR, reason, exception)
        }

        /**
         * Record an error from a Tachiyomi APK extension.
         */
        fun recordApkError(apkName: String, reason: String, exception: Throwable?) {
                recordError(apkName, PluginType.TACHIYOMI_APK, reason, exception)
        }

        /**
         * Record a runtime error from an extension method call.
         */
        fun recordRuntimeError(pluginName: String, method: String, exception: Throwable) {
                val reason = when (exception) {
                        is java.lang.reflect.InvocationTargetException ->
                                "Method $method failed: ${exception.targetException?.message ?: exception.message}"
                        is NoSuchMethodException ->
                                "Method $method not found — plugin may be built for a different API version"
                        is ClassCastException ->
                                "Type mismatch in $method — incompatible plugin version"
                        is IllegalStateException ->
                                "Plugin state error in $method: ${exception.message}"
                        is ExtensionExecutionException ->
                                "Extension ${exception.sourceName} failed in ${exception.method}: ${exception.cause?.message}"
                        is OutOfMemoryError ->
                                "Out of memory in $method — image or data too large"
                        is java.net.SocketTimeoutException ->
                                "Network timeout in $method"
                        is java.net.UnknownHostException ->
                                "DNS resolution failed in $method — check internet connection"
                        is javax.net.ssl.SSLException ->
                                "SSL error in $method — site may have changed certificate"
                        is java.io.IOException ->
                                "Network error in $method: ${exception.message}"
                        is kotlinx.coroutines.CancellationException ->
                                return // Don't record cancellation as errors
                        else ->
                                "Unexpected error in $method: ${exception.javaClass.simpleName}: ${exception.message}"
                }
                recordError(pluginName, PluginType.UNKNOWN, reason, exception)
        }

        /**
         * Clear errors for a specific plugin (e.g., after successful reload).
         */
        fun clearErrors(pluginName: String) {
                errors.remove(pluginName)
                lastErrorTime.remove(pluginName)
        }

        /**
         * Clear all tracked errors.
         */
        fun clearAllErrors() {
                errors.clear()
                lastErrorTime.clear()
        }

        // ============================================================================
        // Error querying
        // ============================================================================

        /**
         * Get all errors for a specific plugin.
         */
        fun getErrors(pluginName: String): List<PluginError> {
                return errors[pluginName]?.toList() ?: emptyList()
        }

        /**
         * Get all tracked errors across all plugins.
         */
        fun getAllErrors(): Map<String, List<PluginError>> {
                return errors.mapValues { it.value.toList() }
        }

        /**
         * Check if a plugin has any errors.
         */
        fun hasErrors(pluginName: String): Boolean {
                return !errors[pluginName].isNullOrEmpty()
        }

        /**
         * Get a user-friendly error summary for a plugin.
         */
        fun getErrorSummary(pluginName: String): String? {
                val pluginErrors = errors[pluginName] ?: return null
                if (pluginErrors.isEmpty()) return null

                val latest = pluginErrors.last()
                return when (latest.type) {
                        PluginType.KOTATSU_JAR -> "Kotatsu plugin error: ${latest.reason}"
                        PluginType.TACHIYOMI_APK -> "Keiyoushi extension error: ${latest.reason}"
                        PluginType.UNKNOWN -> "Plugin error: ${latest.reason}"
                }
        }

        /**
         * Get the most recent error for a plugin.
         */
        fun getLatestError(pluginName: String): PluginError? {
                return errors[pluginName]?.lastOrNull()
        }

        /**
         * Get total count of plugins with errors.
         */
        fun getErrorCount(): Int = errors.size

        // ============================================================================
        // Crash-safe execution wrappers
        // ============================================================================

        /**
         * Execute a block safely, catching any exceptions and recording them.
         * Returns null on failure instead of throwing.
         */
        inline fun <T> runSafely(pluginName: String, method: String, block: () -> T): T? {
                return try {
                        val result = block()
                        // Clear errors on success — plugin recovered
                        clearErrors(pluginName)
                        result
                } catch (e: OutOfMemoryError) {
                        recordRuntimeError(pluginName, method, e)
                        Log.e(TAG, "OOM in $pluginName.$method", e)
                        null
                } catch (e: Exception) {
                        recordRuntimeError(pluginName, method, e)
                        Log.e(TAG, "Error in $pluginName.$method", e)
                        null
                } catch (e: Error) {
                        // Catch serious errors (NoClassDefFoundError, etc.) that shouldn't crash the app
                        recordRuntimeError(pluginName, method, e)
                        Log.e(TAG, "Serious error in $pluginName.$method", e)
                        null
                }
        }

        /**
         * Execute a block safely with a default value on failure.
         */
        inline fun <T> runSafely(pluginName: String, method: String, defaultValue: T, block: () -> T): T {
                return runSafely(pluginName, method, block) ?: defaultValue
        }

        // ============================================================================
        // Sync with DynamicParserManager
        // ============================================================================

        /**
         * Import errors from DynamicParserManager (JAR plugin errors).
         */
        fun syncWithDynamicParserManager(dpm: DynamicParserManager) {
                for ((jarName, loadError) in dpm.getLoadErrors()) {
                        recordJarError(jarName, loadError.reason, loadError.exception)
                }
        }

        // ============================================================================
        // Internal
        // ============================================================================

        private fun recordError(pluginName: String, type: PluginType, reason: String, exception: Throwable?) {
                val now = System.currentTimeMillis()

                // Deduplication: if same plugin errored very recently, still record the
                // error but don't spam the log.
                val lastTime = lastErrorTime[pluginName] ?: 0L
                val isDuplicate = now - lastTime < DEDUP_INTERVAL_MS
                lastErrorTime[pluginName] = now

                val error = PluginError(
                        pluginName = pluginName,
                        type = type,
                        reason = reason,
                        exception = exception,
                        timestampMs = now,
                )

                errors.getOrPut(pluginName) { mutableListOf() }.add(error)

                // Trim to max size
                val list = errors[pluginName]
                if (list != null && list.size > MAX_ERRORS_PER_PLUGIN) {
                        while (list.size > MAX_ERRORS_PER_PLUGIN) {
                                list.removeAt(0)
                        }
                }

                if (!isDuplicate) {
                        Log.w(TAG, "[$type] $pluginName: $reason")
                }
        }
}

// ============================================================================
// Data classes and enums
// ============================================================================

enum class PluginType {
        KOTATSU_JAR,
        TACHIYOMI_APK,
        UNKNOWN,
}

data class PluginError(
        val pluginName: String,
        val type: PluginType,
        val reason: String,
        val exception: Throwable?,
        val timestampMs: Long,
)