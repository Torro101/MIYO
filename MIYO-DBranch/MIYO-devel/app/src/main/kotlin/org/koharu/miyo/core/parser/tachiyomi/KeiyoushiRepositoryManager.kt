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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages the Keiyoushi extension repository — downloading the index,
 * browsing available extensions, and installing APK files.
 *
 * The Keiyoushi repository is hosted on GitHub and provides:
 * - `index.json` — catalog of all available extensions with download URLs
 * - `apk/` — directory of extension APK files
 * - `icon/` — directory of extension icons
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

	/**
	 * Fetch the extension index from the Keiyoushi repository.
	 * Returns the parsed index or null on failure.
	 */
	suspend fun fetchIndex(): KeiyoushiIndex? = withContext(Dispatchers.IO) {
		try {
			val request = Request.Builder()
				.url(INDEX_URL)
				.header("User-Agent", "MIYO-App")
				.build()

			val response = httpClient.newBuilder()
				.connectTimeout(15, TimeUnit.SECONDS)
				.readTimeout(30, TimeUnit.SECONDS)
				.build()
				.newCall(request)
				.execute()

			if (!response.isSuccessful) {
				Log.w(TAG, "Failed to fetch index: HTTP ${response.code}")
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			val index = json.decodeFromString<KeiyoushiIndex>(body)

			// Cache the index
			prefs.edit().putString(PREF_KEY_CACHED_INDEX, body).apply()

			Log.i(TAG, "Fetched Keiyoushi index: ${index.extensions.size} extensions")
			index
		} catch (e: Exception) {
			Log.e(TAG, "Failed to fetch Keiyoushi index", e)
			// Try to return cached index
			getCachedIndex()
		}
	}

	/**
	 * Get the cached index from SharedPreferences.
	 */
	fun getCachedIndex(): KeiyoushiIndex? {
		val cached = prefs.getString(PREF_KEY_CACHED_INDEX, null) ?: return null
		return try {
			json.decodeFromString<KeiyoushiIndex>(cached)
		} catch (e: Exception) {
			Log.w(TAG, "Failed to parse cached index", e)
			null
		}
	}

	/**
	 * Download and install an extension APK from the repository.
	 *
	 * @param extension The extension to install
	 * @return The installed APK file, or null on failure
	 */
	suspend fun installExtension(extension: KeiyoushiExtension): File? = withContext(Dispatchers.IO) {
		try {
			val apkUrl = extension.resources.apkUrl
			val fileName = deriveFileName(extension)

			val apkDir = keiyoushiApkDir(context)
			val outFile = File(apkDir, fileName)

			// Download the APK
			val request = Request.Builder()
				.url(apkUrl)
				.header("User-Agent", "MIYO-App")
				.build()

			val response = httpClient.newBuilder()
				.connectTimeout(15, TimeUnit.SECONDS)
				.readTimeout(60, TimeUnit.SECONDS)
				.build()
				.newCall(request)
				.execute()

			if (!response.isSuccessful) {
				Log.w(TAG, "Failed to download APK: HTTP ${response.code}")
				return@withContext null
			}

			val body = response.body ?: return@withContext null

			// Write to file using PluginFileLoader for atomic writes and size caps
			body.byteStream().use { stream ->
				PluginFileLoader.copyFromStream(outFile, stream)
			}

			// Save metadata
			saveExtensionMeta(extension)

			Log.i(TAG, "Installed extension: ${extension.name}")
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
	 * Check if a specific extension is already installed.
	 */
	fun isExtensionInstalled(packageName: String): Boolean {
		val apkDir = keiyoushiApkDir(context)
		if (!apkDir.exists()) return false
		return apkDir.listFiles { it.extension == "apk" }?.any {
			it.name.contains(packageName.substringAfterLast("."))
		} == true
	}

	/**
	 * Get the directory where Keiyoushi APK files are stored.
	 * Separate from the JAR plugins directory.
	 */
	companion object {
		private const val TAG = "KeiyoushiRepo"
		private const val PREFS_NAME = "keiyoushi_repo"
		private const val PREF_KEY_CACHED_INDEX = "cached_index"
		private const val BASE_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo"
		private const val INDEX_URL = "$BASE_URL/index.min.json"

		fun keiyoushiApkDir(context: Context): File =
			File(context.filesDir, "keiyoushi_extensions").also { it.mkdirs() }

		private fun deriveFileName(extension: KeiyoushiExtension): String {
			val lang = extension.sources.firstOrNull()?.language ?: "all"
			val slug = extension.packageName.substringAfterLast(".")
			return "tachiyomi-${lang}.${slug}-v${extension.versionName}.apk"
		}

		private fun saveExtensionMeta(extension: KeiyoushiExtension) {
			// Save minimal metadata for update checking
		}

		private fun clearExtensionMeta(apkFileName: String) {
			// Clear saved metadata
		}
	}
}

// ============================================================================
// Data models for the Keiyoushi repository index
// ============================================================================

@Serializable
data class KeiyoushiIndex(
	val name: String = "Keiyoushi",
	val badgeLabel: String = "KEI",
	val signingKey: String = "",
	val extensions: List<KeiyoushiExtension> = emptyList(),
)

@Serializable
data class KeiyoushiExtension(
	val name: String,
	val packageName: String,
	val resources: KeiyoushiExtensionResources,
	val extensionLib: String = "1.4",
	val versionCode: Int = 0,
	val versionName: String = "",
	val sources: List<KeiyoushiSource> = emptyList(),
)

@Serializable
data class KeiyoushiExtensionResources(
	val apkUrl: String,
	val iconUrl: String = "",
)

@Serializable
data class KeiyoushiSource(
	val id: String = "",
	val name: String = "",
	val language: String = "all",
	val homeUrl: String = "",
	val contentRating: String? = null,
) {
	val isNsfw: Boolean get() = contentRating == "CONTENT_RATING_PORNOGRAPHIC"
}
