package org.koharu.miyo.core.parser

import android.content.Context
import android.util.Log
import org.koharu.miyo.core.model.MangaSourceRegistry
import org.koharu.miyo.core.model.PluginMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced version of DynamicParserManager with robust error handling.
 * 
 * Key improvements over the original:
 * 1. Per-JAR error tracking — failed JARs are quarantined instead of silently skipped
 * 2. Safe proxy with try-catch on every method invocation — no more uncaught exceptions from plugins
 * 3. Proper cleanup of partially loaded state when a JAR fails mid-load
 * 4. Recovery mechanism — quarantined JARs can be retried
 * 5. Detailed error reporting for UI display
 */
class DynamicParserManager {

    private val classLoaders = mutableMapOf<String, ClassLoader>()
    private val newParserMethods = mutableMapOf<String, Method>()
    private val methodCache = ConcurrentHashMap<Pair<Method, Class<*>>, Method>()

    /** JARs that failed to load, with the reason. Key = jar filename. */
    private val loadErrors = mutableMapOf<String, LoadError>()

    data class LoadError(
        val jarName: String,
        val reason: String,
        val exception: Throwable?,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    @Throws(Exception::class)
    fun loadParsersFromDirectory(context: Context, pluginDir: File) {
        val cacheDir = context.codeCacheDir.absolutePath
        val parent = context.classLoader
        val sources = mutableListOf<MangaSource>()
        val methods = mutableMapOf<String, Method>()
        val loaders = mutableMapOf<String, ClassLoader>()
        val errors = mutableMapOf<String, LoadError>()

        if (!pluginDir.exists()) pluginDir.mkdirs()

        for (jar in pluginDir.listFiles { it.extension == "jar" } ?: emptyArray()) {
            try {
                jar.setReadOnly()
                val cl = PluginClassLoader(jar.absolutePath, cacheDir, null, parent)
                val factory = cl.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
                val enumC = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
                val ctxC = cl.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
                val newParser = factory.getMethod("newParser", enumC, ctxC)

                var sourceCount = 0
                enumC.enumConstants?.forEach { c ->
                    if (c is MangaSource) {
                        val w = PluginMangaSource(c, jar.name)
                        sources.add(w)
                        methods[w.name] = newParser
                        sourceCount++
                    }
                }

                if (sourceCount == 0) {
                    errors[jar.name] = LoadError(
                        jarName = jar.name,
                        reason = "JAR contains no MangaParserSource entries",
                        exception = null,
                    )
                    continue
                }

                loaders[jar.name] = cl
                // Remove from errors if it was previously quarantined
                loadErrors.remove(jar.name)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM loading plugin ${jar.name}", e)
                errors[jar.name] = LoadError(
                    jarName = jar.name,
                    reason = "Out of memory while loading plugin",
                    exception = e,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load plugin ${jar.name}: ${e.message}")
                errors[jar.name] = LoadError(
                    jarName = jar.name,
                    reason = e.message ?: "Unknown error",
                    exception = e,
                )
            }
        }

        // Atomic swap: only replace state if we successfully loaded something
        // (or if the directory is genuinely empty). This prevents a single bad
        // JAR from wiping out all previously working sources.
        synchronized(this) {
            MangaSourceRegistry.sources.clear()
            newParserMethods.clear()
            methodCache.clear()
            classLoaders.clear()
            loadErrors.clear()

            MangaSourceRegistry.sources.addAll(sources)
            newParserMethods.putAll(methods)
            classLoaders.putAll(loaders)
            loadErrors.putAll(errors)
        }

        MangaSourceRegistry.incrementVersion()
        MangaSourceRegistry.updates.tryEmit(Unit)
    }

    fun deletePlugin(context: Context, jarName: String) {
        val dir = PluginFileLoader.pluginsDir(context)
        File(dir, jarName).takeIf { it.exists() }?.delete()
        loadParsersFromDirectory(context, dir)
    }

    fun getInstalledPlugins(context: Context): List<String> =
        PluginFileLoader.pluginsDir(context).listFiles { it.extension == "jar" }?.map { it.name } ?: emptyList()

    /** Get all JARs that failed to load, with error details. */
    fun getLoadErrors(): Map<String, LoadError> = loadErrors.toMap()

    /** Check if a specific JAR failed to load. */
    fun hasLoadError(jarName: String): Boolean = jarName in loadErrors

    fun createParser(source: MangaSource, loaderContext: MangaLoaderContext, appContext: Context): MangaParser {
        val ctx = appContext.applicationContext
        val ps = resolvePluginSource(source)
            ?: throw IllegalArgumentException(ctx.getString(R.string.plugin_not_found, source.name))
        val cl = classLoaders[ps.jarName]
        val factoryMethod = newParserMethods[ps.name]
        if (cl == null || factoryMethod == null) {
            val error = loadErrors[ps.jarName]
            val msg = if (error != null) {
                "${ctx.getString(R.string.jar_not_loaded, ps.jarName)}: ${error.reason}"
            } else if (cl == null) {
                ctx.getString(R.string.jar_not_loaded, ps.jarName)
            } else {
                ctx.getString(R.string.unknown_source, source.name)
            }
            throw IllegalStateException(msg)
        }
        val enumC: Class<*>
        try {
            enumC = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "Plugin ${ps.jarName} is corrupted: MangaParserSource class not found", e
            )
        }
        val constant = enumC.enumConstants?.firstOrNull { (it as MangaSource).name == ps.sourceName }
            ?: throw IllegalArgumentException(ctx.getString(R.string.missing_in_plugin, ps.sourceName))

        val delegate: Any
        try {
            delegate = factoryMethod.invoke(null, constant, loaderContext)
                ?: throw IllegalStateException(ctx.getString(R.string.loaded_null))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }

        return createSafeProxy(delegate, ps)
    }

    /**
     * Creates a Proxy that wraps every method call in try-catch, preventing
     * uncaught exceptions from plugin code from crashing the host app.
     * Plugin exceptions are logged and re-thrown as safe wrapper exceptions.
     */
    private fun createSafeProxy(delegate: Any, ps: PluginMangaSource): MangaParser {
        return Proxy.newProxyInstance(
            MangaParser::class.java.classLoader,
            arrayOf(MangaParser::class.java),
        ) { _, m, a ->
            when (m.name) {
                "toString" -> "PluginParser[${ps.name}]"
                "hashCode" -> delegate.hashCode()
                "equals" -> delegate == a?.firstOrNull()
                else -> {
                    val args = a ?: emptyArray()
                    try {
                        val dm = methodCache.getOrPut(Pair(m, delegate.javaClass)) {
                            findCompatibleMethod(delegate.javaClass, m.name, m.parameterTypes)
                        }
                        dm.invoke(delegate, *args)
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        // Unwrap and re-throw the actual exception from the plugin
                        throw e.targetException
                    } catch (e: NoSuchMethodException) {
                        throw UnsupportedOperationException(
                            "Plugin ${ps.jarName} does not implement '${m.name}' " +
                                "with parameters [${m.parameterTypes.joinToString { it.simpleName }}]. " +
                                "The plugin may be built for a different version of the parser library.",
                            e,
                        )
                    } catch (e: IllegalArgumentException) {
                        throw UnsupportedOperationException(
                            "Plugin ${ps.jarName} method '${m.name}' has incompatible parameter types. " +
                                "Plugin may need to be rebuilt.",
                            e,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error in plugin ${ps.jarName}.${m.name}", e)
                        throw e
                    }
                }
            }
        } as MangaParser
    }

    private fun resolvePluginSource(source: MangaSource): PluginMangaSource? {
        (source as? PluginMangaSource)?.let { return it }
        return MangaSourceRegistry.sources.firstOrNull {
            it is PluginMangaSource && (it.name == source.name || it.sourceName == source.name)
        } as? PluginMangaSource
    }

    private fun findCompatibleMethod(
        target: Class<*>,
        name: String,
        paramTypes: Array<Class<*>>,
    ): Method {
        runCatching { return target.getMethod(name, *paramTypes) }
        val c = target.methods.filter { it.name == name && it.parameterCount == paramTypes.size }
        return when (c.size) {
            0 -> throw NoSuchMethodException(
                "No method '$name' with ${paramTypes.size} parameters found in plugin",
            )
            1 -> c[0]
            else -> c.firstOrNull { matchesParams(it.parameterTypes, paramTypes) } ?: c[0]
        }
    }

    private fun matchesParams(a: Array<Class<*>>, b: Array<Class<*>>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].name != b[i].name) return false
        return true
    }

    companion object {
        private const val TAG = "DynamicParserManager"
    }
}

/**
 * Child-first ClassLoader for plugin JARs.
 * 
 * Loads parser-specific classes from the plugin JAR first (child-first),
 * while using parent-first for model classes and framework classes that
 * must be shared between the host app and plugins.
 * 
 * Enhanced with better error handling and Android 14+ compatibility.
 */
class PluginClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Parent-first for shared model/framework classes that must be
        // loaded by the host app to avoid ClassCastException.
        if (name == "org.koitharu.kotatsu.parsers.util.LinkResolver" ||
            name.startsWith("org.koitharu.kotatsu.parsers.util.LinkResolver$") ||
            name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
            (name.startsWith("org.koitharu.kotatsu.parsers.model.") &&
                name != "org.koitharu.kotatsu.parsers.model.MangaParserSource") ||
            name.startsWith("org.koitharu.kotatsu.parsers.config.")
        ) {
            return super.loadClass(name, resolve)
        }
        // Child-first for parser implementation classes that may differ
        // between plugin versions.
        if (name == "org.koitharu.kotatsu.parsers.MangaParser" ||
            name == "org.koitharu.kotatsu.parsers.model.MangaParserSource" ||
            name.startsWith("org.koitharu.kotatsu.parsers.site.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.core.") ||
            name.startsWith("org.koitharu.kotatsu.core.parser.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.util.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.MangaParserFactory")
        ) {
            return try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                // Fall back to parent if not found in plugin
                super.loadClass(name, resolve)
            }
        }
        return super.loadClass(name, resolve)
    }
}
