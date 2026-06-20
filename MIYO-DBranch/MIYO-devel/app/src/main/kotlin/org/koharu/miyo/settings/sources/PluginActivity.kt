package org.koharu.miyo.settings.sources

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koharu.miyo.R
import org.koharu.miyo.core.nav.AppRouter
import org.koharu.miyo.core.parser.DynamicParserManager
import org.koharu.miyo.core.parser.PluginFileLoader
import org.koharu.miyo.core.parser.tachiyomi.KeiyoushiRepositoryManager
import org.koharu.miyo.core.util.ext.getParcelableExtraCompat
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import java.io.File
import java.util.Locale

class PluginActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) {
			finish()
			return
		}
		val uri = intent.extractInputUri()
		if (uri == null) {
			finishWithResult(false, getString(R.string.load_failed))
			return
		}

		val fileName = resolveOriginalName(uri)
		val isApk = fileName.lowercase(Locale.ROOT).endsWith(".apk")
		val isJar = fileName.lowercase(Locale.ROOT).endsWith(".jar")

		when {
			isApk -> {
				// APK files are Keiyoushi/Tachiyomi extensions
				lifecycleScope.launch {
					val result = withContext(Dispatchers.IO) {
						runCatching {
							importApk(uri, fileName)
						}.fold(
							onSuccess = { ImportResult.Success },
							onFailure = { e ->
								e.printStackTraceDebug()
								ImportResult.Failure(e.message ?: "Unknown error")
							},
						)
					}
					when (result) {
						is ImportResult.Success -> {
							finishWithResult(true, null)
						}
						is ImportResult.Failure -> {
							finishWithResult(false, result.message)
						}
					}
				}
			}
			isJar -> {
				// JAR files are Kotatsu plugins
				lifecycleScope.launch {
					val result = withContext(Dispatchers.IO) {
						runCatching {
							importJar(uri)
						}.fold(
							onSuccess = { ImportResult.Success },
							onFailure = { e ->
								e.printStackTraceDebug()
								ImportResult.Failure(e.message ?: "Unknown error")
							},
						)
					}
					when (result) {
						is ImportResult.Success -> {
							// Check if any JARs failed to load after the import
							val errors = DynamicParserManager.getLoadErrors()
							if (errors.isNotEmpty()) {
								val msg = errors.values.joinToString("\n") {
									"${it.jarName}: ${it.reason}"
								}
								withContext(Dispatchers.Main) {
									finishWithResult(true, getString(R.string.load_success) + "\n\nWarnings:\n$msg")
								}
							} else {
								finishWithResult(true, null)
							}
						}
						is ImportResult.Failure -> {
							finishWithResult(false, result.message)
						}
					}
				}
			}
			else -> {
				if (!isSupported(uri)) {
					showUnsupportedFormatDialog(fileName)
					return
				}
				// Treat as JAR by default
				lifecycleScope.launch {
					val result = withContext(Dispatchers.IO) {
						runCatching { importJar(uri) }.fold(
							onSuccess = { ImportResult.Success },
							onFailure = { e -> ImportResult.Failure(e.message ?: "Unknown error") },
						)
					}
					when (result) {
						is ImportResult.Success -> finishWithResult(true, null)
						is ImportResult.Failure -> finishWithResult(false, result.message)
					}
				}
			}
		}
	}

	private fun importJar(uri: Uri) {
		val pluginsDir = PluginFileLoader.pluginsDir(this)
		val outFile = File(pluginsDir, resolveOutputFileName(uri))
		PluginFileLoader.copyFromUri(this, uri, outFile)
		DynamicParserManager.loadParsersFromDirectory(this, pluginsDir)
	}

	/**
	 * Import a Keiyoushi/Tachiyomi APK extension.
	 */
	private fun importApk(uri: Uri, fileName: String) {
		val apkDir = KeiyoushiRepositoryManager.keiyoushiApkDir(this)
		val sanitized = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_PLUGIN_NAME_LENGTH)
		val outFile = File(apkDir, sanitized)
		PluginFileLoader.copyFromUri(this, uri, outFile)
		// Reload all plugins (JAR + APK)
		DynamicParserManager.loadParsersFromDirectory(this, PluginFileLoader.pluginsDir(this))
	}

	private fun resolveOutputFileName(uri: Uri): String {
		val originalName = resolveOriginalName(uri)
		val sanitized = originalName
			.replace(Regex("[^A-Za-z0-9._-]"), "_")
			.take(MAX_PLUGIN_NAME_LENGTH)
			.trim('.', '_')
			.ifBlank { "plugin_${System.currentTimeMillis()}.jar" }
		return if (sanitized.lowercase(Locale.ROOT).endsWith(".jar")) sanitized else "$sanitized.jar"
	}

	private fun resolveOriginalName(uri: Uri): String {
		return DocumentFile.fromSingleUri(this, uri)?.name
			?: uri.lastPathSegment?.substringAfterLast('/')
			?: "plugin_${System.currentTimeMillis()}.jar"
	}

	private fun isSupported(uri: Uri): Boolean {
		val type = intent.type?.lowercase(Locale.ROOT)
		if (type != null && type != OCTET_STREAM_MIME_TYPE && type in SUPPORTED_MIME_TYPES) {
			return true
		}
		return hasJarExtension(uri) || hasApkExtension(uri)
	}

	private fun hasJarExtension(uri: Uri): Boolean {
		val name = resolveOriginalName(uri)
		return name.lowercase(Locale.ROOT).endsWith(".jar")
	}

	private fun hasApkExtension(uri: Uri): Boolean {
		val name = resolveOriginalName(uri)
		return name.lowercase(Locale.ROOT).endsWith(".apk")
	}

	/**
	 * Show a dialog explaining that the plugin format is not supported.
	 */
	private fun showUnsupportedFormatDialog(fileName: String) {
		AlertDialog.Builder(this)
			.setTitle(R.string.unsupported_plugin_format)
			.setMessage(
				getString(
					R.string.unsupported_plugin_format_message,
					fileName,
				),
			)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				finish()
			}
			.setOnDismissListener { finish() }
			.show()
	}

	private fun finishWithResult(isSuccess: Boolean, message: String? = null) {
		val text = message
			?: if (isSuccess) getString(R.string.load_success) else getString(R.string.load_failed)
		Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
		startActivity(
			AppRouter.sourcesSettingsIntent(this)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
		finish()
	}

	private fun Intent.extractInputUri(): Uri? = when (action) {
		Intent.ACTION_VIEW -> data
		Intent.ACTION_SEND -> getParcelableExtraCompat(Intent.EXTRA_STREAM)
		else -> data ?: getParcelableExtraCompat(Intent.EXTRA_STREAM)
	}

	private sealed class ImportResult {
		data object Success : ImportResult()
		data class Failure(val message: String) : ImportResult()
	}

	private companion object {
		const val OCTET_STREAM_MIME_TYPE = "application/octet-stream"
		const val MAX_PLUGIN_NAME_LENGTH = 96
		val SUPPORTED_MIME_TYPES = setOf(
			"application/java-archive",
			"application/x-java-archive",
			"application/vnd.android.package-archive",
			OCTET_STREAM_MIME_TYPE,
		)
	}
}
