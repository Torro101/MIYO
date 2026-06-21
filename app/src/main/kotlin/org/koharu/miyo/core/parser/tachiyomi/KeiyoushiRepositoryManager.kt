package org.koharu.miyo.core.parser.tachiyomi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koharu.miyo.core.parser.PluginFileLoader
import org.koitharu.kotatsu.parsers.util.await
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages the Keiyoushi extension repository — downloading the index,
 * browsing available extensions, and installing APK files.
 *
 * The Keiyoushi repository follows the Tachiyomi extension repo format:
 * - `index.min.json` — JSON array of available extension objects
 * - `apk/` — directory of extension APK files
 * - `icon/` — directory of extension icons
 *
 * Users can add custom repository URLs. Each URL should point to an
 * `index.min.json` file. The base URL for APK/icon downloads is derived
 * by removing `index.min.json` from the index URL.
 *
 * Default repo: https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json
 */
class KeiyoushiRepositoryManager(
	private val context: Context,
	private val httpClient: OkHttpClient,
) {

	private val prefs: SharedPreferences by lazy {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	// ========================================================================
	// Repository URL management
	// ========================================================================

	/**
	 * Get all configured repository index URLs.
	 * Returns the default Keiyoushi repo if none are configured.
	 */
	fun getRepoUrls(): List<String> {
		val saved = prefs.getStringSet(PREF_KEY_REPO_URLS, null)
		if (saved.isNullOrEmpty()) {
			return listOf(DEFAULT_INDEX_URL)
		}
		return saved.sorted()
	}

	/**
	 * Add a repository index URL. Returns false if it already exists or is invalid.
	 */
	fun addRepoUrl(url: String): Boolean {
		val trimmed = url.trim().removeSuffix("/")
		if (!isValidIndexUrl(trimmed)) return false
		val current = getRepoUrls().toMutableSet()
		if (trimmed in current) return false
		current.add(trimmed)
		prefs.edit().putStringSet(PREF_KEY_REPO_URLS, current).apply()
		return true
	}

	/**
	 * Remove a repository index URL. Returns false if it's the last one.
	 */
	fun removeRepoUrl(url: String): Boolean {
		val current = getRepoUrls().toMutableSet()
		if (current.size <= 1) return false
		if (!current.remove(url.trim().removeSuffix("/"))) return false
		prefs.edit().putStringSet(PREF_KEY_REPO_URLS, current).apply()
		return true
	}

	/**
	 * Check if a URL looks like a valid Tachiyomi extension repo index URL.
	 */
	fun isValidIndexUrl(url: String): Boolean {
		val trimmed = url.trim()
		if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
		return trimmed.endsWith("index.min.json") || trimmed.endsWith("index.json")
	}

	/**
	 * Derive the base URL from an index URL by removing the index filename.
	 * e.g. "https://example.com/repo/index.min.json" -> "https://example.com/repo/"
	 */
	fun deriveBaseUrl(indexUrl: String): String {
		val trimmed = indexUrl.trim().removeSuffix("/")
		return when {
			trimmed.endsWith("index.min.json") -> trimmed.removeSuffix("index.min.json")
			trimmed.endsWith("index.json") -> trimmed.removeSuffix("index.json")
			else -> trimmed.removeSuffix("/") + "/"
		}
	}

	// ========================================================================
	// Index fetching & parsing
	// ========================================================================

	/**
	 * Fetch the extension index from a specific repository URL.
	 * Returns the parsed list of extensions or null on failure.
	 */
	suspend fun fetchIndex(repoUrl: String = DEFAULT_INDEX_URL): List<KeiyoushiExtension>? =
		withContext(Dispatchers.IO) {
			try {
				val request = Request.Builder()
					.url(repoUrl.trim())
					.header("User-Agent", "MIYO-App")
					.build()

				// Use the shared host client directly so CloudFlare/cookie/DoH/GZip
				// interceptors are applied. Rebuilding the client (newBuilder().build())
				// would drop every interceptor and break repos behind CloudFlare.
				val response = httpClient.newCall(request).await()

				if (!response.isSuccessful) {
					Log.w(TAG, "Failed to fetch index from $repoUrl: HTTP ${response.code}")
					return@withContext null
				}

				val body = response.body?.string() ?: return@withContext null
				val extensions = json.decodeFromString<List<KeiyoushiExtension>>(body)

				// Cache the index with the repo URL as key
				prefs.edit().putString(cacheKeyFor(repoUrl), body).apply()

				Log.i(TAG, "Fetched Keiyoushi index from $repoUrl: ${extensions.size} extensions")
				extensions
			} catch (e: Exception) {
				Log.e(TAG, "Failed to fetch Keiyoushi index from $repoUrl", e)
				// Try to return cached index
				getCachedIndex(repoUrl)
			}
		}

	/**
	 * Fetch extensions from all configured repositories.
	 */
	suspend fun fetchAllIndexes(): List<KeiyoushiExtension> {
		val repos = getRepoUrls()
		val allExtensions = mutableListOf<KeiyoushiExtension>()
		for (repoUrl in repos) {
			val extensions = fetchIndex(repoUrl)
			if (extensions != null) {
				val baseUrl = deriveBaseUrl(repoUrl)
				allExtensions.addAll(extensions.map { it.withBaseUrl(baseUrl) })
			}
		}
		return allExtensions.sortedBy { it.name.lowercase() }
	}

	/**
	 * Get the cached index for a specific repo URL.
	 */
	fun getCachedIndex(repoUrl: String = DEFAULT_INDEX_URL): List<KeiyoushiExtension>? {
		val cached = prefs.getString(cacheKeyFor(repoUrl), null) ?: return null
		return try {
			json.decodeFromString<List<KeiyoushiExtension>>(cached)
		} catch (e: Exception) {
			Log.w(TAG, "Failed to parse cached index for $repoUrl", e)
			null
		}
	}

	// ========================================================================
	// Extension install / uninstall
	// ========================================================================

	/**
	 * Download and install an extension APK from the repository.
	 *
	 * @param extension The extension to install
	 * @return The installed APK file, or null on failure
	 */
	suspend fun installExtension(extension: KeiyoushiExtension): File? =
		withContext(Dispatchers.IO) {
			try {
				val apkUrl = extension.apkUrl
				val fileName = extension.apk

				val apkDir = keiyoushiApkDir(context)
				val outFile = File(apkDir, fileName)

				// Delete old version if exists
				if (outFile.exists()) {
					outFile.delete()
				}

				// Also clean up any older version of the same package
				cleanupOldVersions(apkDir, extension.pkg)

				// Download the APK
				val request = Request.Builder()
					.url(apkUrl)
					.header("User-Agent", "MIYO-App")
					.build()

				// Use the shared host client (with all interceptors) and a non-blocking await().
				val response = httpClient.newCall(request).await()

				if (!response.isSuccessful) {
					Log.w(TAG, "Failed to download APK: HTTP ${response.code}")
					return@withContext null
				}

				val body = response.body ?: return@withContext null

				// Write to file using PluginFileLoader for atomic writes and size caps
				body.byteStream().use { stream ->
					PluginFileLoader.copyFromStream(outFile, stream)
				}

				// Save metadata for update checking
				saveExtensionMeta(extension)

				Log.i(TAG, "Installed extension: ${extension.name} v${extension.version}")
				outFile
			} catch (e: Exception) {
				Log.e(TAG, "Failed to install extension ${extension.name}", e)
				null
			}
		}

	/**
	 * Delete an installed extension APK.
	 */
	fun uninstallExtension(apkFileName: String) {
		val apkDir = keiyoushiApkDir(context)
		val file = File(apkDir, apkFileName)
		if (file.exists()) {
			file.delete()
			clearExtensionMeta(apkFileName)
		}
	}

	/**
	 * List all installed Keiyoushi extension APKs.
	 */
	fun getInstalledExtensions(): List<File> {
		val apkDir = keiyoushiApkDir(context)
		if (!apkDir.exists()) return emptyList()
		return apkDir.listFiles { it.extension == "apk" }?.toList() ?: emptyList()
	}

	/**
	 * Check if a specific extension (by package name) is already installed.
	 */
	fun isExtensionInstalled(pkgName: String): Boolean {
		val apkDir = keiyoushiApkDir(context)
		if (!apkDir.exists()) return false
		return apkDir.listFiles { it.extension == "apk" }?.any {
			it.nameWithoutExtension.contains(pkgName.substringAfterLast("."))
		} == true
	}

	/**
	 * Get the installed version code for a package name, or null if not installed.
	 */
	fun getInstalledVersionCode(pkgName: String): Int? {
		val meta = readExtensionMeta(pkgName) ?: return null
		return meta.code
	}

	/**
	 * Check if an extension has an update available.
	 */
	fun hasUpdate(extension: KeiyoushiExtension): Boolean {
		val installedCode = getInstalledVersionCode(extension.pkg) ?: return false
		return extension.code > installedCode
	}

	/**
	 * Get the icon URL for an extension.
	 */
	fun getIconUrl(extension: KeiyoushiExtension, baseUrl: String): String {
		return "${baseUrl}icon/${extension.pkg}.png"
	}

	companion object {
		private const val TAG = "KeiyoushiRepo"
		private const val PREFS_NAME = "keiyoushi_repo"
		private const val PREF_KEY_CACHED_INDEX = "cached_index"
		private const val PREF_KEY_REPO_URLS = "repo_urls"
		private const val PREF_KEY_EXTENSION_META = "extension_meta"
		const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/"
		const val DEFAULT_INDEX_URL = "${DEFAULT_BASE_URL}index.min.json"

		fun keiyoushiApkDir(context: Context): File =
			File(context.filesDir, "keiyoushi_extensions").also { it.mkdirs() }

		private fun cacheKeyFor(repoUrl: String): String =
			"${PREF_KEY_CACHED_INDEX}_${repoUrl.hashCode()}"

		private fun cleanupOldVersions(apkDir: File, pkg: String) {
			val slug = pkg.substringAfterLast(".")
			apkDir.listFiles { it.extension == "apk" }?.forEach { file ->
				if (file.nameWithoutExtension.contains(slug) && file.name != "$slug.apk") {
					file.delete()
				}
			}
		}

		private fun metaKeyFor(pkg: String): String = "${PREF_KEY_EXTENSION_META}_${pkg.hashCode()}"
	}

	// ========================================================================
	// Extension metadata persistence (for update checking)
	// ========================================================================

	private fun saveExtensionMeta(extension: KeiyoushiExtension) {
		val meta = ExtensionMeta(
			pkg = extension.pkg,
			code = extension.code,
			version = extension.version,
			apk = extension.apk,
		)
		prefs.edit().putString(metaKeyFor(extension.pkg), json.encodeToString(ExtensionMeta.serializer(), meta)).apply()
	}

	private fun clearExtensionMeta(apkFileName: String) {
		val slug = apkFileName.removeSuffix(".apk")
			.removePrefix("tachiyomi-")
			.substringBefore("-v")
		val allKeys = prefs.all.keys.filter { it.startsWith(PREF_KEY_EXTENSION_META) }
		for (key in allKeys) {
			val raw = prefs.getString(key, null) ?: continue
			try {
				val meta = json.decodeFromString<ExtensionMeta>(raw)
				if (meta.apk == apkFileName || meta.pkg.substringAfterLast(".") == slug) {
					prefs.edit().remove(key).apply()
				}
			} catch (_: Exception) { /* ignore */ }
		}
	}

	private fun readExtensionMeta(pkg: String): ExtensionMeta? {
		val raw = prefs.getString(metaKeyFor(pkg), null) ?: return null
		return try {
			json.decodeFromString<ExtensionMeta>(raw)
		} catch (_: Exception) {
			null
		}
	}
}

// ============================================================================
// Data models for the Keiyoushi repository index
// ============================================================================
//
// The actual index.min.json format from Keiyoushi is a JSON ARRAY:
// [
//   {
//     "name": "Tachiyomi: MangaDex",
//     "pkg": "eu.kanade.tachiyomi.extension.all.mangadex",
//     "apk": "tachiyomi-all.mangadex-v1.4.50.apk",
//     "lang": "all",
//     "code": 50,
//     "version": "1.4.50",
//     "nsfw": 0,
//     "sources": [
//       { "name": "MangaDex", "lang": "all", "id": "...", "baseUrl": "..." }
//     ]
//   }
// ]
//
// APK download URL = {baseUrl}/apk/{apk}
// Icon URL = {baseUrl}/icon/{pkg}.png

@Serializable
data class KeiyoushiExtension(
	val name: String,
	val pkg: String,
	val apk: String,
	val lang: String = "all",
	val code: Int = 0,
	val version: String = "",
	val nsfw: Int = 0,
	val sources: List<KeiyoushiSource> = emptyList(),
	/** Not in JSON - set at runtime after fetching from a specific repo. */
	val baseUrl: String = "",
) {
	/** Display name without the "Tachiyomi: " prefix. */
	val displayName: String get() = name.removePrefix("Tachiyomi: ")

	/** Whether this extension contains NSFW sources. */
	val isNsfw: Boolean get() = nsfw == 1

	/** Full APK download URL. */
	val apkUrl: String get() = "${baseUrl}apk/$apk"

	/** Icon URL. */
	val iconUrl: String get() = "${baseUrl}icon/$pkg.png"

	/** Create a copy with the base URL set for this repo. */
	fun withBaseUrl(url: String): KeiyoushiExtension = copy(baseUrl = url)
}

@Serializable
data class KeiyoushiSource(
	val id: String = "",
	val name: String = "",
	val lang: String = "all",
	val baseUrl: String = "",
)

/** Metadata for an installed extension, used for update checking. */
@Serializable
data class ExtensionMeta(
	val pkg: String,
	val code: Int,
	val version: String,
	val apk: String,
)
