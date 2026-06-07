package org.koharu.miyo.reader.domain

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.toBitmap
import com.davemorrissey.labs.subscaleview.ImageSource
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.source
import okio.use
import org.jetbrains.annotations.Blocking
import org.koharu.miyo.core.LocalizedAppContext
import org.koharu.miyo.core.exceptions.UnsupportedFileException
import org.koharu.miyo.core.image.BitmapDecoderCompat
import org.koharu.miyo.core.model.LocalMangaSource
import org.koharu.miyo.core.nativeio.NativeImageProbe
import org.koharu.miyo.core.network.CommonHeaders
import org.koharu.miyo.core.network.MangaHttpClient
import org.koharu.miyo.core.network.imageproxy.ImageProxyInterceptor
import org.koharu.miyo.core.parser.CachingMangaRepository
import org.koharu.miyo.core.parser.MangaRepository
import org.koharu.miyo.core.prefs.AppSettings
import org.koharu.miyo.core.ui.image.TrimTransformation
import org.koharu.miyo.core.util.FileSize
import org.koharu.miyo.core.util.MimeTypes
import org.koharu.miyo.core.util.ext.URI_SCHEME_ZIP
import org.koharu.miyo.core.util.ext.cancelChildrenAndJoin
import org.koharu.miyo.core.util.ext.compressToPNG
import org.koharu.miyo.core.util.ext.ensureRamAtLeast
import org.koharu.miyo.core.util.ext.ensureSuccess
import org.koharu.miyo.core.util.ext.getCompletionResultOrNull
import org.koharu.miyo.core.util.ext.isFileUri
import org.koharu.miyo.core.util.ext.isImage
import org.koharu.miyo.core.util.ext.isNotEmpty
import org.koharu.miyo.core.util.ext.isPowerSaveMode
import org.koharu.miyo.core.util.ext.isZipUri
import org.koharu.miyo.core.util.ext.lifecycleScope
import org.koharu.miyo.core.util.ext.mangaSourceExtra
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koharu.miyo.core.util.ext.ramAvailable
import org.koharu.miyo.core.util.ext.toMimeType
import org.koharu.miyo.core.util.ext.toMimeTypeOrNull
import org.koharu.miyo.core.util.ext.use
import org.koharu.miyo.core.util.ext.withProgress
import org.koharu.miyo.core.util.progress.ProgressDeferred
import org.koharu.miyo.download.ui.worker.DownloadSlowdownDispatcher
import org.koharu.miyo.local.data.LocalStorageCache
import org.koharu.miyo.local.data.PageCache
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koharu.miyo.reader.ui.pager.ReaderPage
import java.io.File
import java.io.FileNotFoundException
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ActivityRetainedScoped
class PageLoader @Inject constructor(
	@LocalizedAppContext private val context: Context,
	lifecycle: ActivityRetainedLifecycle,
	@MangaHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	private val coil: ImageLoader,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val downloadSlowdownDispatcher: DownloadSlowdownDispatcher,
	private val nativeImageProbe: NativeImageProbe,
	private val imageEnhancementProcessor: ImageEnhancementProcessor,
) {

	val loaderScope = lifecycle.lifecycleScope + InternalErrorHandler() + Dispatchers.Default

	private val tasks = HashMap<PageTaskKey, ProgressDeferred<Uri, Float>>()
	private val semaphore = Semaphore(3)
	private val convertLock = Mutex()
	private val prefetchLock = Mutex()

	@Volatile
	private var repository: MangaRepository? = null
	private val prefetchQueue = LinkedList<MangaPage>()
	private val counter = AtomicInteger(0)
	private val edgeDetector = EdgeDetector(context)

	@Volatile
	private var lastPageDecodeBytes = 0L

	fun isPrefetchApplicable(pages: List<ReaderPage>): Boolean {
		val repo = repository
		val hasLocalPages = repo?.source == LocalMangaSource || pages.any { it.isLocalPage() }
		val canPrefetchSource = repo is CachingMangaRepository || hasLocalPages
		return canPrefetchSource
			&& (hasLocalPages || settings.isPagesPreloadEnabled)
			&& !context.isPowerSaveMode()
			&& !isLowRam()
	}

	@AnyThread
	fun prefetch(pages: List<ReaderPage>) = loaderScope.launch {
		var hasPendingPrefetch = false
		prefetchLock.withLock {
			val queueLimit = getPrefetchQueueLimit()
			if (queueLimit <= 0) {
				prefetchQueue.clear()
				return@withLock
			}
			for (page in pages.asReversed()) {
				val key = page.toMangaPage().taskKey()
				val hasTask = synchronized(tasks) {
					tasks.containsKey(key)
				}
				if (hasTask) {
					continue
				}
				prefetchQueue.offerFirst(page.toMangaPage())
				if (prefetchQueue.size > queueLimit) {
					prefetchQueue.pollLast()
				}
			}
			hasPendingPrefetch = prefetchQueue.isNotEmpty()
		}
		if (hasPendingPrefetch && counter.get() == 0) {
			onIdle()
		}
	}

	suspend fun loadPreview(page: MangaPage): ImageSource? {
		val preview = page.preview
		if (preview.isNullOrEmpty()) {
			return null
		}
		val request = ImageRequest.Builder(context)
			.data(preview)
			.mangaSourceExtra(page.source)
			.transformations(TrimTransformation())
			.build()
		return coil.execute(request).image?.toImageSource()
	}

	fun peekPreviewSource(preview: String?): ImageSource? {
		if (preview.isNullOrEmpty()) {
			return null
		}
		coil.memoryCache?.let { cache ->
			val key = MemoryCache.Key(preview)
			cache[key]?.image?.let {
				return if (it is BitmapImage) {
					ImageSource.cachedBitmap(it.toBitmap())
				} else {
					ImageSource.bitmap(it.toBitmap())
				}
			}
		}
		coil.diskCache?.let { cache ->
			cache.openSnapshot(preview)?.use { snapshot ->
				return ImageSource.file(snapshot.data.toFile())
			}
		}
		return null
	}

	fun loadPageAsync(page: MangaPage, force: Boolean): ProgressDeferred<Uri, Float> {
		val key = page.taskKey()
		var task = synchronized(tasks) {
			tasks[key]
		}?.takeIf { it.isValid() }
		if (force) {
			task?.cancel()
		} else if (task?.isCancelled == false) {
			return task
		}
		task = loadPageAsyncImpl(page, skipCache = force, isPrefetch = false)
		synchronized(tasks) {
			tasks[key] = task
		}
		return task
	}

	suspend fun loadPage(page: MangaPage, force: Boolean): Uri {
		return loadPageAsync(page, force).await()
	}

	@CheckResult
	suspend fun materializeZipEntry(uri: Uri): Uri {
		check(uri.isZipUri()) { "Expected ZIP uri: $uri" }
		val cacheKey = rawZipCacheKey(uri)
		cache.get(cacheKey)?.let { file ->
			if (file.isUsablePageCache()) {
				return file.toUri()
			}
			cache.remove(cacheKey)
		}
		return withContext(Dispatchers.IO) {
			ZipFile(uri.schemeSpecificPart).use { zip ->
				val entryName = uri.fragment ?: throw FileNotFoundException(uri.toString())
				val entry = zip.getEntry(entryName) ?: throw FileNotFoundException("$uri#$entryName")
				zip.getInputStream(entry).source().use { source ->
					val file = cache.set(cacheKey, source, MimeTypes.getMimeTypeFromExtension(entry.name))
					if (file.isUsablePageCache()) {
						file.toUri()
					} else {
						cache.remove(cacheKey)
						throw UnsupportedFileException("Unsupported or corrupt image file")
					}
				}
			}
		}
	}

	@CheckResult
	suspend fun convertBimap(uri: Uri): Uri = convertLock.withLock {
		if (uri.isZipUri()) {
			val cacheKey = convertedZipCacheKey(uri)
			cache.get(cacheKey)?.let { file ->
				if (file.isUsablePageCache()) {
					return@withLock file.toUri()
				}
				cache.remove(cacheKey)
			}
			runInterruptible(Dispatchers.IO) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entryName = uri.fragment ?: throw FileNotFoundException(uri.toString())
					val entry = zip.getEntry(entryName) ?: throw FileNotFoundException("$uri#$entryName")
					context.ensureRamAtLeast(estimateDecodeBytes(entry.size.coerceAtLeast(0L)))
					zip.getInputStream(entry).use {
						BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
					}
				}
			}.use { image ->
				cache.set(cacheKey, image).toUri()
			}
		} else {
			// For a plain file URI, write the converted PNG to a NEW cache
			// entry (not back to the source) so the original is preserved and
			// the conversion works even when the source lives in read-only
			// storage (e.g. SAF tree the user only granted read access to).
			val cacheKey = convertedFileCacheKey(uri)
			cache.get(cacheKey)?.let { file ->
				if (file.isUsablePageCache()) {
					return@withLock file.toUri()
				}
				cache.remove(cacheKey)
			}
			val source = uri.toFile()
			runInterruptible(Dispatchers.IO) {
				context.ensureRamAtLeast(source.estimateDecodeMemoryBytes())
				BitmapDecoderCompat.decode(source)
			}.use { image ->
				cache.set(cacheKey, image).toUri()
			}
		}
	}

	suspend fun getTrimmedBounds(uri: Uri): Rect? = runCatchingCancellable {
		edgeDetector.getBounds(ImageSource.uri(uri))
	}.onFailure { error ->
		error.printStackTraceDebug()
	}.getOrNull()

	suspend fun enhanceForReader(page: MangaPage, displayUri: Uri): Uri {
		return imageEnhancementProcessor.enhanceForReader(displayUri, page.taskKey().toString())
	}

	suspend fun getPageUrl(page: MangaPage): String {
		return getRepository(page.source).getPageUrl(page)
	}

	suspend fun invalidate(clearCache: Boolean) {
		synchronized(tasks) {
			tasks.clear()
		}
		loaderScope.cancelChildrenAndJoin()
		if (clearCache) {
			cache.clear()
		}
	}

	private fun onIdle() = loaderScope.launch {
		prefetchLock.withLock {
			while (prefetchQueue.isNotEmpty()) {
				val page = prefetchQueue.pollFirst() ?: return@launch
				synchronized(tasks) {
					tasks[page.taskKey()] = loadPageAsyncImpl(page, skipCache = false, isPrefetch = true)
				}
			}
		}
	}

	private fun loadPageAsyncImpl(
		page: MangaPage,
		skipCache: Boolean,
		isPrefetch: Boolean,
	): ProgressDeferred<Uri, Float> {
		val progress = MutableStateFlow(PROGRESS_UNDEFINED)
		val deferred = loaderScope.async {
			counter.incrementAndGet()
			try {
				loadPageImpl(
					page = page,
					progress = progress,
					isPrefetch = isPrefetch,
					skipCache = skipCache,
				)
			} finally {
				if (counter.decrementAndGet() == 0) {
					onIdle()
				}
			}
		}
		return ProgressDeferred(deferred, progress)
	}

	@Synchronized
	private fun getRepository(source: MangaSource): MangaRepository {
		val result = repository
		return if (result != null && result.source == source) {
			result
		} else {
			mangaRepositoryFactory.create(source).also { repository = it }
		}
	}

	private suspend fun loadPageImpl(
		page: MangaPage,
		progress: MutableStateFlow<Float>,
		isPrefetch: Boolean,
		skipCache: Boolean,
	): Uri = semaphore.withPermit {
		val pageUrl = resolvePageUrl(page)
		check(pageUrl.isNotBlank()) { "Cannot obtain full image url for $page" }
		if (!skipCache) {
			cache.get(pageUrl)?.let { file ->
				if (file.isUsablePageCache()) {
					recordLoadedPageFootprint(file)
					return file.toUri()
				}
				cache.remove(pageUrl)
			}
		}
		val uri = pageUrl.toUri()
		return when {
			uri.isZipUri() -> {
				val normalizedUri = if (uri.scheme == URI_SCHEME_ZIP) {
					uri
				} else { // legacy uri
					uri.buildUpon().scheme(URI_SCHEME_ZIP).build()
				}
				materializeZipEntry(normalizedUri).also {
					recordLoadedPageFootprint(it.toFile())
				}
			}

			uri.isFileUri() -> uri.also {
				if (!it.toFile().isUsablePageCache()) {
					throw UnsupportedFileException("Unsupported or corrupt image file")
				}
				recordLoadedPageFootprint(it.toFile())
			}
			else -> {
				if (isPrefetch) {
					val slowdownDelayMs = downloadSlowdownDispatcher.getDelayMs(page.source)
					if (slowdownDelayMs > 0L) {
						delay(slowdownDelayMs)
					}
				}
				val request = createPageRequest(pageUrl, page.source)
				imageProxyInterceptor.interceptPageRequest(request, okHttp).ensureSuccess().use { response ->
					response.requireBody().withProgress(progress).use {
						cache.set(pageUrl, it.source(), it.contentType()?.toMimeType())
					}
				}.also { file ->
					if (!file.isUsablePageCache()) {
						cache.remove(pageUrl)
						throw UnsupportedFileException("Unsupported or corrupt image file")
					}
					recordLoadedPageFootprint(file)
				}.toUri()
			}
		}
	}

	private suspend fun resolvePageUrl(page: MangaPage): String {
		val directUri = page.url.toUri()
		return if (directUri.isFileUri() || directUri.isZipUri()) {
			page.url
		} else {
			getPageUrl(page)
		}
	}

	private fun ReaderPage.isLocalPage(): Boolean {
		val uri = url.toUri()
		return source == LocalMangaSource || uri.isFileUri() || uri.isZipUri()
	}

	private fun isLowRam(): Boolean {
		return isLowRam(context.ramAvailable)
	}

	private fun getPrefetchQueueLimit(): Int {
		val availableRam = context.ramAvailable
		if (context.isPowerSaveMode() || isLowRam(availableRam)) {
			return 0
		}
		val baseLimit = when {
			availableRam >= FileSize.MEGABYTES.convert(PREFETCH_HIGH_RAM_MB, FileSize.BYTES) -> PREFETCH_LIMIT_HIGH
			availableRam >= FileSize.MEGABYTES.convert(PREFETCH_DEFAULT_RAM_MB, FileSize.BYTES) -> PREFETCH_LIMIT_DEFAULT
			else -> PREFETCH_LIMIT_LOW
		}
		return when {
			lastPageDecodeBytes >= megabytes(PREFETCH_HUGE_PAGE_MB) -> minOf(baseLimit, PREFETCH_LIMIT_HUGE_PAGE)
			lastPageDecodeBytes >= megabytes(PREFETCH_LARGE_PAGE_MB) -> minOf(baseLimit, PREFETCH_LIMIT_LARGE_PAGE)
			else -> baseLimit
		}
	}

	private fun isLowRam(availableRam: Long): Boolean {
		return availableRam <= FileSize.MEGABYTES.convert(PREFETCH_MIN_RAM_MB, FileSize.BYTES)
	}

	private fun recordLoadedPageFootprint(file: File) {
		val estimate = file.estimateDecodeMemoryBytes()
		if (estimate > 0L) {
			lastPageDecodeBytes = estimate
		}
	}

	private fun File.isUsablePageCache(): Boolean {
		return runCatchingCancellable {
			if (!isFile || length() == 0L) {
				return@runCatchingCancellable false
			}
			val nativeInfo = if (nativeImageProbe.isAvailable) {
				nativeImageProbe.probe(this)
			} else {
				null
			}
			if (nativeInfo?.isCorrupt == true) {
				return@runCatchingCancellable false
			}
			val detectedType = nativeInfo?.mimeType?.toMimeTypeOrNull()
				?: MimeTypes.probeMimeType(this)
				?: BitmapDecoderCompat.probeMimeType(this)
			detectedType?.isImage == true
		}.getOrDefault(false)
	}

	private fun File.estimateDecodeMemoryBytes(): Long {
		val nativeEstimate = if (nativeImageProbe.isAvailable) {
			nativeImageProbe.probe(this)?.estimatedMemoryBytes
		} else {
			null
		}
		return nativeEstimate?.takeIf { it > 0L } ?: estimateDecodeBytes(length())
	}

	private fun estimateDecodeBytes(encodedBytes: Long): Long {
		val normalizedBytes = encodedBytes.coerceAtLeast(0L)
		val scaledBytes = if (normalizedBytes > Long.MAX_VALUE / IMAGE_DECODE_FALLBACK_MULTIPLIER) {
			Long.MAX_VALUE
		} else {
			normalizedBytes * IMAGE_DECODE_FALLBACK_MULTIPLIER
		}
		return scaledBytes
			.coerceAtLeast(megabytes(IMAGE_DECODE_MIN_MB))
			.coerceAtMost(megabytes(IMAGE_DECODE_MAX_MB))
	}

	private fun Image.toImageSource(): ImageSource = if (this is BitmapImage) {
		ImageSource.cachedBitmap(toBitmap())
	} else {
		ImageSource.bitmap(toBitmap())
	}

	private fun Deferred<Uri>.isValid(): Boolean {
		return getCompletionResultOrNull()?.map { uri ->
			uri.exists() && uri.isTargetNotEmpty()
		}?.getOrDefault(false) != false
	}

	private data class PageTaskKey(
		val sourceName: String,
		val pageId: Long,
		val url: String,
	)

	private fun MangaPage.taskKey(): PageTaskKey = PageTaskKey(
		sourceName = source.name,
		pageId = id,
		url = url,
	)

	private class InternalErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
		CoroutineExceptionHandler {

		override fun handleException(context: CoroutineContext, exception: Throwable) {
			exception.printStackTraceDebug()
		}
	}

	companion object {

		private const val PROGRESS_UNDEFINED = -1f
		private const val PREFETCH_LIMIT_LOW = 3
		private const val PREFETCH_LIMIT_DEFAULT = 6
		private const val PREFETCH_LIMIT_HIGH = 10
		private const val PREFETCH_MIN_RAM_MB = 80L
		private const val PREFETCH_DEFAULT_RAM_MB = 160L
		private const val PREFETCH_HIGH_RAM_MB = 512L
		private const val PREFETCH_LARGE_PAGE_MB = 32L
		private const val PREFETCH_HUGE_PAGE_MB = 64L
		private const val PREFETCH_LIMIT_LARGE_PAGE = 2
		private const val PREFETCH_LIMIT_HUGE_PAGE = 1
		private const val IMAGE_DECODE_FALLBACK_MULTIPLIER = 2L
		private const val IMAGE_DECODE_MIN_MB = 8L
		private const val IMAGE_DECODE_MAX_MB = 256L

		private fun megabytes(value: Long): Long {
			return FileSize.MEGABYTES.convert(value, FileSize.BYTES)
		}

		private fun rawZipCacheKey(uri: Uri) = "zip-entry:$uri"

		private fun convertedZipCacheKey(uri: Uri) = "zip-converted:$uri"

		private fun convertedFileCacheKey(uri: Uri) = "file-converted:$uri"

		fun createPageRequest(pageUrl: String, mangaSource: MangaSource) = Request.Builder()
			.url(pageUrl)
			.get()
			.header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.tag(MangaSource::class.java, mangaSource)
			.build()


		@Blocking
		private fun Uri.exists(): Boolean = when {
			isFileUri() -> toFile().exists()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { it.getEntry(fragment) != null }
			}

			else -> false
		}

		@Blocking
		private fun Uri.isTargetNotEmpty(): Boolean = when {
			isFileUri() -> toFile().isNotEmpty()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { (it.getEntry(fragment)?.size ?: 0L) != 0L }
			}

			else -> false
		}
	}
}
