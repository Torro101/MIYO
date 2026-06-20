package org.koharu.miyo.core.parser.tachiyomi

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koharu.miyo.core.parser.tachiyomi.TachiyomiExtensionInfo.Companion.META_DATA_CLASS
import org.koharu.miyo.core.parser.tachiyomi.TachiyomiExtensionInfo.Companion.META_DATA_EXTENSION
import org.koharu.miyo.core.parser.tachiyomi.TachiyomiExtensionInfo.Companion.META_DATA_NSFW
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

/**
 * Loads Tachiyomi-format extension APKs (used by Keiyoushi and other Tachiyomi-based repos).
 *
 * This loader:
 * 1. Reads the APK's AndroidManifest.xml to extract the entry class name
 * 2. Creates a [TachiyomiClassLoader] (child-first DexClassLoader) for the APK
 * 3. Instantiates the entry class (either [HttpSource] or [SourceFactory])
 * 4. Returns [TachiyomiExtensionInfo] with loaded sources and metadata
 *
 * Thread safety: All public methods are safe to call from any thread, but the
 * returned [TachiyomiExtensionInfo] objects are NOT thread-safe — they should
 * be consumed on a single thread or protected externally.
 */
class TachiyomiExtensionLoader {

        private val classLoaders = java.util.concurrent.ConcurrentHashMap<String, TachiyomiClassLoader>()

        /**
         * Load all extension APKs from the given directory.
         *
         * @param context Application context
         * @param apkDir Directory containing .apk files
         * @return List of [TachiyomiExtensionInfo], one per valid APK
         */
        fun loadExtensionsFromDirectory(context: Context, apkDir: File): List<TachiyomiExtensionInfo> {
                if (!apkDir.exists()) apkDir.mkdirs()

                val results = mutableListOf<TachiyomiExtensionInfo>()
                val oldClassLoaders = classLoaders.toMap()
                classLoaders.clear()

                for (apk in apkDir.listFiles { it.extension == "apk" } ?: emptyArray()) {
                        try {
                                val info = loadExtension(context, apk)
                                if (info != null) {
                                        results.add(info)
                                        classLoaders[apk.name] = info.classLoader
                                }
                        } catch (e: OutOfMemoryError) {
                                Log.e(TAG, "OOM loading extension ${apk.name}", e)
                                results.add(
                                        TachiyomiExtensionInfo(
                                                fileName = apk.name,
                                                packageName = "",
                                                className = "",
                                                sources = emptyList(),
                                                classLoader = TachiyomiClassLoader(apk.absolutePath, context.codeCacheDir.absolutePath, null, context.classLoader),
                                                isNsfw = false,
                                                error = ExtensionLoadError(apk.name, "Out of memory while loading extension", e),
                                        ),
                                )
                        } catch (e: Exception) {
                                Log.w(TAG, "Failed to load extension ${apk.name}: ${e.message}")
                                results.add(
                                        TachiyomiExtensionInfo(
                                                fileName = apk.name,
                                                packageName = "",
                                                className = "",
                                                sources = emptyList(),
                                                classLoader = TachiyomiClassLoader(apk.absolutePath, context.codeCacheDir.absolutePath, null, context.classLoader),
                                                isNsfw = false,
                                                error = ExtensionLoadError(apk.name, e.message ?: "Unknown error", e),
                                        ),
                                )
                        }
                }

                return results
        }

        /**
         * Load a single extension APK.
         *
         * @param context Application context
         * @param apkFile The APK file to load
         * @return [TachiyomiExtensionInfo] or null if the APK is not a valid extension
         */
        fun loadExtension(context: Context, apkFile: File): TachiyomiExtensionInfo? {
                val cacheDir = context.codeCacheDir.absolutePath
                val parentCl = context.classLoader

                // Step 1: Parse APK metadata from AndroidManifest.xml
                val meta = parseApkMetadata(context, apkFile)
                if (meta == null) {
                        Log.w(TAG, "Could not parse metadata from ${apkFile.name}")
                        return TachiyomiExtensionInfo(
                                fileName = apkFile.name,
                                packageName = "",
                                className = "",
                                sources = emptyList(),
                                classLoader = TachiyomiClassLoader(apkFile.absolutePath, cacheDir, null, parentCl),
                                isNsfw = false,
                                error = ExtensionLoadError(apkFile.name, "Not a valid Tachiyomi extension APK (missing metadata)", null),
                        )
                }

                // Step 2: Create class loader for the APK
                val classLoader = TachiyomiClassLoader(
                        apkFile.absolutePath,
                        cacheDir,
                        null,
                        parentCl,
                )

                // Step 3: Load and instantiate the entry class
                val fullClassName = if (meta.className.startsWith(".")) {
                        meta.packageName + meta.className
                } else {
                        meta.className
                }

                val sources = mutableListOf<HttpSource>()
                try {
                        val clazz = classLoader.loadClass(fullClassName)
                        val instance = instantiateExtensionClass(clazz, context)

                        when (instance) {
                                is HttpSource -> {
                                        sources.add(instance)
                                }
                                is SourceFactory -> {
                                        @Suppress("UNCHECKED_CAST")
                                        sources.addAll(instance.createSources().filterIsInstance<HttpSource>())
                                }
                                else -> {
                                        Log.w(TAG, "Extension class $fullClassName is neither HttpSource nor SourceFactory")
                                }
                        }
                } catch (e: ClassNotFoundException) {
                        return TachiyomiExtensionInfo(
                                fileName = apkFile.name,
                                packageName = meta.packageName,
                                className = fullClassName,
                                sources = emptyList(),
                                classLoader = classLoader,
                                isNsfw = meta.isNsfw,
                                error = ExtensionLoadError(apkFile.name, "Class not found: $fullClassName", e),
                        )
                } catch (e: InvocationTargetException) {
                        val cause = e.targetException ?: e
                        Log.e(TAG, "Extension constructor failed for $fullClassName", cause)
                        return TachiyomiExtensionInfo(
                                fileName = apkFile.name,
                                packageName = meta.packageName,
                                className = fullClassName,
                                sources = emptyList(),
                                classLoader = classLoader,
                                isNsfw = meta.isNsfw,
                                error = ExtensionLoadError(apkFile.name, "Constructor failed: ${cause.message}", cause),
                        )
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to instantiate $fullClassName", e)
                        return TachiyomiExtensionInfo(
                                fileName = apkFile.name,
                                packageName = meta.packageName,
                                className = fullClassName,
                                sources = emptyList(),
                                classLoader = classLoader,
                                isNsfw = meta.isNsfw,
                                error = ExtensionLoadError(apkFile.name, e.message ?: "Unknown error", e),
                        )
                }

                if (sources.isEmpty()) {
                        return TachiyomiExtensionInfo(
                                fileName = apkFile.name,
                                packageName = meta.packageName,
                                className = fullClassName,
                                sources = emptyList(),
                                classLoader = classLoader,
                                isNsfw = meta.isNsfw,
                                error = ExtensionLoadError(apkFile.name, "Extension contains no HttpSource instances", null),
                        )
                }

                Log.i(TAG, "Loaded extension ${apkFile.name}: ${sources.size} source(s)")
                return TachiyomiExtensionInfo(
                        fileName = apkFile.name,
                        packageName = meta.packageName,
                        className = fullClassName,
                        sources = sources,
                        classLoader = classLoader,
                        isNsfw = meta.isNsfw,
                        error = null,
                )
        }

        /**
         * Get the class loader for a specific APK file.
         */
        fun getClassLoader(apkFileName: String): TachiyomiClassLoader? = classLoaders[apkFileName]

        /**
         * Parse APK metadata using PackageManager.
         */
        private fun parseApkMetadata(context: Context, apkFile: File): ApkMetadata? {
                return try {
                        val pm = context.packageManager
                        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_META_DATA)
                                ?: return null

                        val appInfo = info.applicationInfo ?: return null
                        appInfo.sourceDir = apkFile.absolutePath
                        appInfo.publicSourceDir = apkFile.absolutePath

                        val metaData = appInfo.metaData ?: return null

                        // Check for the tachiyomi.extension meta-data key
                        if (!metaData.containsKey(META_DATA_EXTENSION)) {
                                return null
                        }

                        val className = metaData.getString(META_DATA_CLASS)
                                ?: return null

                        val isNsfw = metaData.containsKey(META_DATA_NSFW)

                        ApkMetadata(
                                packageName = info.packageName,
                                className = className,
                                isNsfw = isNsfw,
                                versionName = info.versionName,
                                versionCode = info.longVersionCode,
                        )
                } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse APK metadata for ${apkFile.name}: ${e.message}")
                        null
                }
        }

        /**
         * Instantiate an extension class, handling both default constructors and
         * constructors that require [android.content.Context].
         */
        private fun instantiateExtensionClass(clazz: Class<*>, context: Context): Any {
                // Try no-arg constructor first (most common for HttpSource)
                val noArgConstructor: Constructor<*>? = try {
                        clazz.getConstructor()
                } catch (_: NoSuchMethodException) {
                        null
                }

                if (noArgConstructor != null) {
                        return noArgConstructor.newInstance()
                }

                // Try Context-arg constructor (some extensions need it for Injekt)
                val contextConstructor: Constructor<*>? = try {
                        clazz.getConstructor(android.content.Context::class.java)
                } catch (_: NoSuchMethodException) {
                        null
                }

                if (contextConstructor != null) {
                        return contextConstructor.newInstance(context)
                }

                // Fallback: try any declared constructor
                val constructors = clazz.declaredConstructors
                if (constructors.isEmpty()) {
                        throw IllegalStateException("No constructors found for ${clazz.name}")
                }

                // Find the shortest constructor (least dependencies)
                val ctor = constructors.minByOrNull { it.parameterCount }!!
                ctor.isAccessible = true

                val args = ctor.parameterTypes.map { paramType ->
                        when {
                                paramType == android.content.Context::class.java -> context
                                paramType == android.app.Application::class.java -> context.applicationContext
                                else -> null // Will fail for required params, but best effort
                        }
                }.toTypedArray()

                return ctor.newInstance(*args)
        }

        companion object {
                private const val TAG = "TachiyomiExtLoader"
        }
}

// ============================================================================
// Supporting data classes
// ============================================================================

/**
 * Metadata extracted from a Tachiyomi extension APK's AndroidManifest.xml.
 */
data class ApkMetadata(
        val packageName: String,
        val className: String,
        val isNsfw: Boolean,
        val versionName: String?,
        val versionCode: Long,
)

/**
 * Information about a loaded Tachiyomi extension, including its sources and any errors.
 */
data class TachiyomiExtensionInfo(
        val fileName: String,
        val packageName: String,
        val className: String,
        val sources: List<HttpSource>,
        val classLoader: TachiyomiClassLoader,
        val isNsfw: Boolean,
        val error: ExtensionLoadError?,
) {
        /** Whether this extension loaded successfully (has at least one source and no error). */
        val isSuccess: Boolean get() = sources.isNotEmpty() && error == null

        /** Display name derived from the first source or file name. */
        val displayName: String get() = sources.firstOrNull()?.name ?: fileName.removeSuffix(".apk")

        companion object {
                const val META_DATA_EXTENSION = "tachiyomi.extension"
                const val META_DATA_CLASS = "tachiyomi.extension.class"
                const val META_DATA_NSFW = "tachiyomi.extension.nsfw"
        }
}

/**
 * Error information for a failed extension load.
 */
data class ExtensionLoadError(
        val fileName: String,
        val reason: String,
        val exception: Throwable?,
)

/**
 * Child-first [DexClassLoader] for Tachiyomi extension APKs.
 *
 * Delegates Tachiyomi source API classes and common libraries (OkHttp, Jsoup, RxJava)
 * to the parent classloader (host app), while loading extension-specific code
 * from the APK first. This ensures:
 * 1. Extensions find the API classes provided by the host app
 * 2. Extension-specific implementations are loaded from the APK
 * 3. Common library instances are shared (single OkHttp client, etc.)
 */
@Suppress("DEPRECATION")
class TachiyomiClassLoader(
        dexPath: String,
        optimizedDirectory: String?,
        librarySearchPath: String?,
        parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
                // Parent-first for Tachiyomi source API — must be shared with host
                if (name.startsWith("eu.kanade.tachiyomi.source.") ||
                        name.startsWith("eu.kanade.tachiyomi.network.") ||
                        name.startsWith("eu.kanade.tachiyomi.util.") ||
                        name == "eu.kanade.tachiyomi.AppInfo"
                ) {
                        return super.loadClass(name, resolve)
                }

                // Parent-first for common libraries — share single instances
                if (name.startsWith("okhttp3.") ||
                        name.startsWith("okio.") ||
                        name.startsWith("org.jsoup.") ||
                        name.startsWith("rx.") ||
                        name.startsWith("rx.android.") ||
                        name.startsWith("kotlin.") ||
                        name.startsWith("kotlinx.") ||
                        name.startsWith("androidx.preference.") ||
                        name.startsWith("android.content.Context") ||
                        name.startsWith("app.cash.quickjs.") ||
                        name.startsWith("uy.kohesive.injekt.")
                ) {
                        return super.loadClass(name, resolve)
                }

                // Child-first for extension-specific classes
                return try {
                        findClass(name)
                } catch (e: ClassNotFoundException) {
                        super.loadClass(name, resolve)
                }
        }
}
