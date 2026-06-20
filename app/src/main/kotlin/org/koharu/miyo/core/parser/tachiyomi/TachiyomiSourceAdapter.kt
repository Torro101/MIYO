package org.koharu.miyo.core.parser.tachiyomi

import android.util.Log
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import okhttp3.Interceptor
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import rx.Observable
import java.util.Locale

/**
 * Adapter that wraps a Tachiyomi [HttpSource] as a Kotatsu [MangaParser].
 *
 * This is the key bridge class that makes Tachiyomi/Keiyoushi extensions work
 * within the MIYO app's Kotatsu-based architecture. It translates all method
 * calls between the two APIs:
 *
 * - Kotatsu `getList()` → Tachiyomi `fetchPopularManga()` / `fetchSearchManga()`
 * - Kotatsu `getDetails()` → Tachiyomi `fetchMangaDetails()`
 * - Kotatsu `getPages()` → Tachiyomi `fetchPageList()`
 * - Kotatsu `getPageUrl()` → Tachiyomi `fetchImageUrl()`
 *
 * All calls are wrapped in try-catch with detailed error reporting to prevent
 * extension bugs from crashing the host app.
 */
class TachiyomiSourceAdapter(
	private val httpSource: HttpSource,
	private val sourceName: String,
	private val apkFileName: String,
	private val mapper: TachiyomiModelMapper = TachiyomiModelMapper,
) : MangaParser {

	/** Unique Kotatsu-compatible source identifier. */
	val mangaSource = KeiyoushiMangaSource(apkFileName, sourceName, httpSource.lang ?: "")

	// MangaParser.source returns MangaParserSource which is an enum in the
	// kotatsu-parsers library. Since we can't add enum constants at runtime,
	// we use MangaSourceRegistry to look up a matching source. For Tachiyomi
	// sources, we return the first enum constant as a placeholder — the actual
	// source identity is carried by mangaSource (KeiyoushiMangaSource).
	override val source: org.koitharu.kotatsu.parsers.model.MangaSource
		get() = mangaSource

	override val availableSortOrders: Set<SortOrder>
		get() = if (httpSource.supportsLatest) setOf(SortOrder.POPULARITY, SortOrder.UPDATED)
			else setOf(SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)



	override val config: MangaSourceConfig
		get() = EmptyMangaSourceConfig

	override val authorizationProvider: org.koitharu.kotatsu.parsers.MangaParserAuthProvider?
		get() = null

	override val configKeyDomain: org.koitharu.kotatsu.parsers.config.ConfigKey.Domain
		get() = org.koitharu.kotatsu.parsers.config.ConfigKey.Domain(httpSource.baseUrl)

	override val domain: String
		get() = httpSource.baseUrl

	// ============================================================================
	// Core MangaParser methods
	// ============================================================================

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return try {
			val page = (offset / ITEMS_PER_PAGE) + 1
			when {
				filter.query != null -> {
					// Search mode
					val query = filter.query
					searchManga(page, query, httpSource.getFilterList())
				}
				order == SortOrder.UPDATED && httpSource.supportsLatest -> {
					// Latest updates
					fetchLatest(page)
				}
				else -> {
					// Popular / default
					fetchPopular(page)
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error in getList for $sourceName", e)
			throw wrapExtensionException(e, "getList")
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		return try {
			val sManga = mapper.toSManga(manga)
			val detailed = awaitObservable(httpSource.fetchMangaDetails(sManga))
			val result = mapper.toManga(detailed, mangaSource, manga.id)

			// Fetch chapters if not already present
			val chapters = awaitObservable(httpSource.fetchChapterList(detailed))
			result.copy(
				chapters = mapper.toMangaChapters(chapters, mangaSource, manga.id),
				description = result.description ?: manga.description,
			)
		} catch (e: Exception) {
			Log.e(TAG, "Error in getDetails for ${manga.title}", e)
			throw wrapExtensionException(e, "getDetails")
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return try {
			val sChapter = mapper.toSChapter(chapter)
			val pages = awaitObservable(httpSource.fetchPageList(sChapter))

			// Resolve image URLs for pages that don't have them yet
			pages.map { page ->
				if (page.imageUrl != null) {
					mapper.toMangaPage(page, mangaSource)
				} else {
					try {
						val imageUrl = awaitObservable(httpSource.fetchImageUrl(page))
						page.imageUrl = imageUrl
						mapper.toMangaPage(page, mangaSource)
					} catch (e: Exception) {
						Log.w(TAG, "Failed to resolve image URL for page ${page.index}", e)
						mapper.toMangaPage(page, mangaSource)
					}
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error in getPages for ${chapter.title}", e)
			throw wrapExtensionException(e, "getPages")
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		// If the page URL is already a direct image URL, return it
		if (page.url.startsWith("http") && isImageUrl(page.url)) {
			return page.url
		}
		// Otherwise, try to fetch the image URL from the page URL
		return try {
			val tachiyomiPage = eu.kanade.tachiyomi.source.model.Page(
				index = page.id.toInt(),
				url = page.url,
				imageUrl = null,
			)
			awaitObservable(httpSource.fetchImageUrl(tachiyomiPage))
		} catch (e: Exception) {
			Log.e(TAG, "Error in getPageUrl for ${page.url}", e)
			page.url
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions()
	}

	override suspend fun getFavicons(): org.koitharu.kotatsu.parsers.model.Favicons {
		return org.koitharu.kotatsu.parsers.model.Favicons(emptyList(), httpSource.baseUrl)
	}

	override fun onCreateConfig(keys: MutableCollection<org.koitharu.kotatsu.parsers.config.ConfigKey<*>>) {
		// No config keys needed for Tachiyomi extensions
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		// fetchRelatedMangaList is not available in the bundled AAR version of HttpSource.
		// Return empty list — related manga is not supported via Tachiyomi extensions.
		return emptyList()
	}

	override fun getRequestHeaders(): Headers {
		return httpSource.headers
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		// Pass through — let OkHttp handle the request normally
		return chain.proceed(chain.request())
	}

	// ============================================================================
	// Internal helpers
	// ============================================================================

	private suspend fun fetchPopular(page: Int): List<Manga> {
		val mangasPage = awaitObservable(httpSource.fetchPopularManga(page))
		val (mangas, _) = mapper.toMangaList(mangasPage, mangaSource)
		return mangas
	}

	private suspend fun fetchLatest(page: Int): List<Manga> {
		val mangasPage = awaitObservable(httpSource.fetchLatestUpdates(page))
		val (mangas, _) = mapper.toMangaList(mangasPage, mangaSource)
		return mangas
	}

	private suspend fun searchManga(page: Int, query: String, filters: FilterList): List<Manga> {
		val mangasPage = awaitObservable(httpSource.fetchSearchManga(page, query, filters))
		val (mangas, _) = mapper.toMangaList(mangasPage, mangaSource)
		return mangas
	}

	/**
	 * Awaits the result of a Tachiyomi Observable (RxJava 1.x).
	 * Converts the RxJava Observable to a Kotlin coroutine-friendly call.
	 */
	private suspend fun <T> awaitObservable(observable: Observable<T>): T {
		return suspendCancellableCoroutine { cont ->
			val subscription = observable.subscribe(
				{ result ->
					if (cont.isActive) cont.resume(result) {}
				},
				{ error ->
					if (cont.isActive) cont.resumeWith(Result.failure(error))
				},
			)
			cont.invokeOnCancellation { subscription.unsubscribe() }
		}
	}

	private fun isImageUrl(url: String): Boolean {
		val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp")
		return imageExtensions.any { url.lowercase(Locale.ROOT).contains(it) }
	}

	private fun wrapExtensionException(e: Exception, method: String): Exception {
		return ExtensionExecutionException(
			sourceName = sourceName,
			apkFileName = apkFileName,
			method = method,
			cause = e,
		)
	}

	companion object {
		private const val TAG = "TachiyomiAdapter"
		private const val ITEMS_PER_PAGE = 25
	}
}

// ============================================================================
// Supporting classes
// ============================================================================

/**
 * A [MangaSource] implementation for Tachiyomi/Keiyoushi extension sources.
 * Uses a compound name format: "apkFileName:sourceName" for uniqueness.
 */
class KeiyoushiMangaSource(
	private val apkFileName: String,
	private val sourceName: String,
	override val locale: String,
) : MangaSource {

	override val name: String = "$apkFileName:$sourceName"

	val sourceNameOnly: String get() = sourceName

	override val title: String get() = sourceName

	override val contentType: ContentType
		get() = ContentType.MANGA

	override val isBroken: Boolean
		get() = false

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is KeiyoushiMangaSource) return false
		return name == other.name
	}

	override fun hashCode(): Int = name.hashCode()

	override fun toString(): String = "KeiyoushiSource($name)"
}

/**
 * Exception thrown when a Tachiyomi extension method fails.
 * Contains metadata about which extension and method caused the failure.
 */
class ExtensionExecutionException(
	val sourceName: String,
	val apkFileName: String,
	val method: String,
	cause: Throwable,
) : Exception("Extension $sourceName ($apkFileName) failed in $method: ${cause.message}", cause)

/**
 * Empty config implementation — Tachiyomi extensions don't use Kotatsu's config system.
 */
private object EmptyMangaSourceConfig : MangaSourceConfig {
	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: org.koitharu.kotatsu.parsers.config.ConfigKey<T>): T {
		return when (key) {
			is org.koitharu.kotatsu.parsers.config.ConfigKey.Domain -> key.defaultValue
			is org.koitharu.kotatsu.parsers.config.ConfigKey.UserAgent -> key.defaultValue
			is org.koitharu.kotatsu.parsers.config.ConfigKey.ShowSuspiciousContent -> key.defaultValue
			is org.koitharu.kotatsu.parsers.config.ConfigKey.SplitByTranslations -> key.defaultValue
			is org.koitharu.kotatsu.parsers.config.ConfigKey.PreferredImageServer -> key.defaultValue
			else -> null
		} as T
	}
}