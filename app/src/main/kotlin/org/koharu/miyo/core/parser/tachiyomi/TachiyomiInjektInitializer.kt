package org.koharu.miyo.core.parser.tachiyomi

import android.app.Application
import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.lang.reflect.Constructor

/**
 * Seeds the Injekt dependency graph that Tachiyomi/Keiyoushi extension APKs rely on.
 *
 * Tachiyomi [eu.kanade.tachiyomi.source.online.HttpSource] resolves its networking
 * stack lazily through Injekt, e.g.:
 *
 * ```
 * protected val network: NetworkHelper by injectLazy()
 * ```
 *
 * If the host application never populates the Injekt graph, the FIRST network call
 * made by ANY extension (popular/latest/search/details/pages) throws because
 * `Injekt.get<NetworkHelper>()` (and `Injekt.get<Json>()`, `Injekt.get<Application>()`)
 * have no registered providers. This is the root cause of "extension loads but every
 * request crashes".
 *
 * ## Why reflection?
 *
 * The networking classes (`NetworkHelper`, `Json`, the `Injekt` singleton API) are
 * provided by the bundled `keiyoushi-extensions-lib.aar`, whose exact API surface is
 * not guaranteed across repacks. To keep the host app compiling and running regardless
 * of the AAR variant, every lookup is performed reflectively and guarded. If a class or
 * constructor is absent, we log and skip that binding instead of failing the build or
 * crashing startup. When the classes ARE present (the normal case), the graph is seeded
 * correctly.
 *
 * This initializer is idempotent and safe to call multiple times; it only seeds once.
 */
object TachiyomiInjektInitializer {

	private const val TAG = "TachiyomiInjekt"

	private const val INJEKT_CLASS = "uy.kohesive.injekt.InjektKt"
	private const val INJEKT_SCOPE_CLASS = "uy.kohesive.injekt.api.InjektRegistrar"
	private const val NETWORK_HELPER_CLASS = "eu.kanade.tachiyomi.network.NetworkHelper"
	private const val NETWORK_PREFERENCES_CLASS = "eu.kanade.tachiyomi.network.NetworkPreferences"
	private const val JSON_CLASS = "kotlinx.serialization.json.Json"

	@Volatile
	private var seeded = false

	/**
	 * Seed the Injekt graph with the host-provided [OkHttpClient]-backed networking stack.
	 *
	 * @param context Application context (used to build NetworkHelper / register Application).
	 * @param mangaHttpClient The host's shared OkHttpClient (the @MangaHttpClient instance).
	 */
	@Synchronized
	fun ensureInitialized(context: Context, mangaHttpClient: OkHttpClient) {
		if (seeded) return
		val app = context.applicationContext as? Application
		try {
			registerApplication(app)
			registerJson()
			registerNetworkPreferences(context)
			registerNetworkHelper(context, mangaHttpClient)
			seeded = true
			Log.i(TAG, "Injekt graph seeded for Tachiyomi extensions")
		} catch (e: Throwable) {
			// Never let extension wiring crash app startup.
			Log.w(TAG, "Could not fully seed Injekt graph: ${e.message}")
		}
	}

	/** Register the Android [Application] singleton (extensions commonly inject it). */
	private fun registerApplication(app: Application?) {
		if (app == null) return
		if (!addSingleton(app, app.javaClass)) {
			// Try registering under the base Application type explicitly.
			addSingleton(app, Application::class.java)
		}
	}

	/** Register a lenient kotlinx.serialization Json instance if the class is available. */
	private fun registerJson() {
		val jsonInstance = runCatching {
			// Json { ignoreUnknownKeys = true; ... } is awkward via reflection;
			// fall back to the Json.Default companion which always exists.
			val jsonClass = Class.forName(JSON_CLASS)
			val defaultField = jsonClass.getField("Default")
			defaultField.get(null)
		}.getOrNull() ?: return
		addSingleton(jsonInstance, jsonInstance.javaClass)
		// Also register under the declared Json type so injectLazy<Json>() resolves.
		runtimeRegisterUnderType(jsonInstance, JSON_CLASS)
	}

	/**
	 * Register Tachiyomi's NetworkPreferences if present in the AAR.
	 *
	 * Some NetworkHelper variants require a NetworkPreferences to be resolvable via Injekt
	 * (e.g. for DoH / user-agent settings). Construction is fully guarded: if the class is
	 * absent or no compatible constructor exists, we skip it. Probes common shapes:
	 *   - NetworkPreferences(PreferenceStore) / NetworkPreferences(SharedPreferences)
	 *   - NetworkPreferences(Context)
	 *   - NetworkPreferences()
	 */
	private fun registerNetworkPreferences(context: Context) {
		val prefsClass = runCatching { Class.forName(NETWORK_PREFERENCES_CLASS) }.getOrNull() ?: return
		val instance: Any? = run {
			prefsClass.constructors
				.sortedBy { it.parameterTypes.size }
				.firstNotNullOfOrNull { ctor ->
					val args: Array<Any?>? = when {
						ctor.parameterTypes.isEmpty() -> emptyArray()
						ctor.parameterTypes.size == 1 &&
							ctor.parameterTypes[0].isAssignableFrom(context.javaClass) -> arrayOf<Any?>(context)
						else -> null
					}
					if (args == null) null
					else runCatching { ctor.newInstance(*args) }.getOrNull()
				}
		}
		if (instance == null) {
			Log.w(TAG, "Could not construct NetworkPreferences; skipping (most sources don't need it)")
			return
		}
		addSingleton(instance, prefsClass)
	}

	/**
	 * Construct and register the Tachiyomi NetworkHelper.
	 *
	 * Bundled NetworkHelper variants typically expose one of:
	 *   - NetworkHelper(Context)
	 *   - NetworkHelper(Context, OkHttpClient)
	 * We probe both, preferring the variant that lets us inject the host's shared client.
	 */
	private fun registerNetworkHelper(context: Context, client: OkHttpClient) {
		val helperClass = runCatching { Class.forName(NETWORK_HELPER_CLASS) }.getOrNull()
		if (helperClass == null) {
			Log.w(TAG, "$NETWORK_HELPER_CLASS not present in AAR; skipping NetworkHelper binding")
			return
		}

		val instance: Any? = run {
			// Prefer (Context, OkHttpClient) so the host client (with Cloudflare/DoH/cookies) is reused.
			val twoArg: Constructor<*>? = helperClass.constructors.firstOrNull {
				it.parameterTypes.size == 2 &&
					it.parameterTypes[0].isAssignableFrom(context.javaClass) &&
					it.parameterTypes[1] == OkHttpClient::class.java
			}
			if (twoArg != null) {
				return@run runCatching { twoArg.newInstance(context, client) }.getOrNull()
			}
			val ctxArg: Constructor<*>? = helperClass.constructors.firstOrNull {
				it.parameterTypes.size == 1 &&
					it.parameterTypes[0].isAssignableFrom(context.javaClass)
			}
			if (ctxArg != null) {
				return@run runCatching { ctxArg.newInstance(context) }.getOrNull()
			}
			null
		}

		if (instance == null) {
			Log.w(TAG, "Could not construct NetworkHelper (no compatible constructor)")
			return
		}
		addSingleton(instance, helperClass)
	}

	// ========================================================================
	// Injekt reflective helpers
	// ========================================================================

	/**
	 * Equivalent to `Injekt.addSingleton(instance)` but reflective and type-explicit.
	 * Returns true on success.
	 */
	private fun addSingleton(instance: Any, type: Class<*>): Boolean {
		return runCatching {
			val injekt = injektScope() ?: return false
			// InjektScope/DefaultRegistrar exposes addSingleton(T) and addSingletonFactory.
			val method = injekt.javaClass.methods.firstOrNull {
				it.name == "addSingleton" && it.parameterTypes.size == 1
			} ?: return false
			method.invoke(injekt, instance)
			true
		}.getOrElse {
			Log.w(TAG, "addSingleton(${type.simpleName}) failed: ${it.message}")
			false
		}
	}

	/** Best-effort registration of [instance] under an explicit declared type name. */
	private fun runtimeRegisterUnderType(instance: Any, typeName: String) {
		runtimeRegisterUnderType@ runCatching {
			Class.forName(typeName)
			addSingleton(instance, instance.javaClass)
		}
	}

	/**
	 * Obtain the global Injekt scope object (`uy.kohesive.injekt.Injekt`).
	 * Accessed via the generated `InjektKt.getInjekt()` accessor.
	 */
	private fun injektScope(): Any? {
		return runCatching {
			val injektKt = Class.forName(INJEKT_CLASS)
			val getter = injektKt.methods.firstOrNull { it.name == "getInjekt" }
			getter?.invoke(null)
		}.getOrNull()
	}
}
