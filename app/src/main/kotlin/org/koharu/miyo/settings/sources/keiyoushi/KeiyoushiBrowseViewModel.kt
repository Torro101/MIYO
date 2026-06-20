package org.koharu.miyo.settings.sources.keiyoushi

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.koharu.miyo.R
import org.koharu.miyo.core.network.BaseHttpClient
import org.koharu.miyo.core.parser.DynamicParserManager
import org.koharu.miyo.core.parser.PluginFileLoader
import org.koharu.miyo.core.parser.tachiyomi.KeiyoushiExtension
import org.koharu.miyo.core.parser.tachiyomi.KeiyoushiRepositoryManager
import org.koharu.miyo.core.ui.BaseViewModel
import org.koharu.miyo.list.ui.model.ListModel
import org.koharu.miyo.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class KeiyoushiBrowseViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) : BaseViewModel() {

	private val repoManager = KeiyoushiRepositoryManager(context, okHttpClient)

	private val searchQuery = MutableStateFlow<String?>(null)
	private val langFilter = MutableStateFlow<String?>(null)

	/** Cached list of all extensions from the last successful fetch. */
	private var cachedExtensions: List<KeiyoushiExtension> = emptyList()

	private val _content = MutableStateFlow<List<ListModel>>(listOf(LoadingState))
	val content: StateFlow<List<ListModel>> get() = _content

	val repoUrls: List<String> get() = repoManager.getRepoUrls()

	init {
		loadExtensions()
	}

	fun loadExtensions() {
		launchJob(Dispatchers.Default) {
			_content.value = listOf(LoadingState)
			val extensions = repoManager.fetchAllIndexes()
			if (extensions.isEmpty()) {
				// Try cached data
				val cached = getCachedExtensions()
				if (cached.isNotEmpty()) {
					cachedExtensions = cached
					publishExtensions(cached)
				} else {
					_content.value = listOf(
						KeiyoushiExtensionItem.Hint(
							title = context.getString(R.string.keiyoushi_no_extensions_found),
						),
					)
				}
				return@launchJob
			}
			cachedExtensions = extensions
			publishExtensions(extensions)
		}
	}

	fun installExtension(extension: KeiyoushiExtension) {
		launchJob(Dispatchers.Default) {
			// Update UI to show installing state
			updateItem(extension.pkg) { item ->
				if (item is KeiyoushiExtensionItem.Available) {
					item.copy(isInstalling = true)
				} else item
			}

			val result = repoManager.installExtension(extension)
			if (result != null) {
				// Reload all parsers so the new extension is available
				DynamicParserManager.loadParsersFromDirectory(context, PluginFileLoader.pluginsDir(context))
			}

			// Re-publish from cache to reflect install state
			publishExtensions(cachedExtensions)
		}
	}

	fun uninstallExtension(extension: KeiyoushiExtension) {
		launchJob(Dispatchers.Default) {
			// Find the installed APK filename
			val apkDir = KeiyoushiRepositoryManager.keiyoushiApkDir(context)
			val slug = extension.pkg.substringAfterLast(".")
			val installedFile = apkDir.listFiles()?.find {
				it.extension == "apk" && it.nameWithoutExtension.contains(slug)
			}

			if (installedFile != null) {
				repoManager.uninstallExtension(installedFile.name)
				DynamicParserManager.loadParsersFromDirectory(context, PluginFileLoader.pluginsDir(context))
			}

			// Re-publish from cache
			publishExtensions(cachedExtensions)
		}
	}

	fun addRepoUrl(url: String): Boolean {
		val success = repoManager.addRepoUrl(url)
		if (success) {
			loadExtensions()
		}
		return success
	}

	fun removeRepoUrl(url: String): Boolean {
		val success = repoManager.removeRepoUrl(url)
		if (success) {
			loadExtensions()
		}
		return success
	}

	fun setSearchQuery(query: String?) {
		searchQuery.value = query?.trim()
		// Re-publish from cache (no network fetch)
		if (cachedExtensions.isNotEmpty()) {
			publishExtensions(cachedExtensions)
		}
	}

	fun setLangFilter(lang: String?) {
		langFilter.value = lang
		if (cachedExtensions.isNotEmpty()) {
			publishExtensions(cachedExtensions)
		}
	}

	fun getAvailableLanguages(): List<String> {
		return cachedExtensions.map { it.lang }.distinct().sorted()
	}

	private fun publishExtensions(extensions: List<KeiyoushiExtension>) {
		val q = searchQuery.value
		val lang = langFilter.value

		val filtered = extensions.filter { ext ->
			(q.isNullOrBlank() || ext.displayName.contains(q, ignoreCase = true) ||
				ext.pkg.contains(q, ignoreCase = true)) &&
				(lang.isNullOrBlank() || ext.lang == lang)
		}

		val items = filtered.map { ext ->
			KeiyoushiExtensionItem.Available(
				extension = ext,
				isInstalled = repoManager.isExtensionInstalled(ext.pkg),
				hasUpdate = repoManager.hasUpdate(ext),
				isInstalling = false,
			)
		}

		if (items.isEmpty()) {
			_content.value = listOf(
				KeiyoushiExtensionItem.Hint(
					title = if (q.isNullOrBlank())
						context.getString(R.string.keiyoushi_no_extensions_found)
					else
						context.getString(R.string.nothing_found),
				),
			)
		} else {
			_content.value = items
		}
	}

	private fun updateItem(pkg: String, transform: (ListModel) -> ListModel) {
		val current = content.value.toMutableList()
		val index = current.indexOfFirst {
			it is KeiyoushiExtensionItem.Available && it.extension.pkg == pkg
		}
		if (index >= 0) {
			current[index] = transform(current[index])
			(content as? MutableStateFlow)?.value = current
		}
	}

	private fun getCachedExtensions(): List<KeiyoushiExtension> {
		val repos = repoManager.getRepoUrls()
		val allExtensions = mutableListOf<KeiyoushiExtension>()
		for (repoUrl in repos) {
			val cached = repoManager.getCachedIndex(repoUrl)
			if (cached != null) {
				val baseUrl = repoManager.deriveBaseUrl(repoUrl)
				allExtensions.addAll(cached.map { it.withBaseUrl(baseUrl) })
			}
		}
		return allExtensions.sortedBy { it.name.lowercase() }
	}
}
