package org.koharu.miyo.reader.ui.pager.vm

import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.IOException
import org.koharu.miyo.core.exceptions.resolve.ExceptionResolver
import org.koharu.miyo.core.model.LocalMangaSource
import org.koharu.miyo.core.os.NetworkState
import org.koharu.miyo.core.util.ext.isFileUri
import org.koharu.miyo.core.util.ext.isZipUri
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koharu.miyo.core.util.ext.throttle
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koharu.miyo.reader.domain.PageLoader
import org.koharu.miyo.reader.ui.config.ReaderSettings

class PageViewModel(
        private val loader: PageLoader,
        private val lifecycleOwner: LifecycleOwner,
        val settingsProducer: ReaderSettings.Producer,
        private val networkState: NetworkState,
        private val exceptionResolver: ExceptionResolver,
        private val isWebtoon: Boolean,
) : DefaultOnImageEventListener {

        // Tie coroutine launches to the holder's lifecycle scope. Note that the
        // holder lifecycle is driven by the parent (fragment / activity), so this
        // scope does NOT cancel on RecyclerView recycle — it only cancels when the
        // parent fragment is destroyed. Per-holder cancellation is handled by
        // job?.cancel() in onRecycle() / evictFromMemory() below.
        private val scope: CoroutineScope = lifecycleOwner.lifecycleScope
        private var job: Job? = null
        private var cachedBounds: Rect? = null
        private var currentPage: MangaPage? = null
        private var originalDisplayUri: Uri? = null
        private var lastProgress = PROGRESS_INITIAL

        val state = MutableStateFlow<PageState>(PageState.Empty)

        fun isLoading() = job?.isActive == true

        fun onBind(page: MangaPage): Boolean {
                if (currentPage.isSamePageAs(page) && state.value !is PageState.Empty) {
                        return false
                }
                currentPage = page
                lastProgress = PROGRESS_INITIAL
                val prevJob = job
                job = scope.launch(Dispatchers.Default) {
                        prevJob?.cancelAndJoin()
                        doLoad(page, force = false)
                }
                return true
        }

        fun retry(page: MangaPage, isFromUser: Boolean) {
                currentPage = page
                lastProgress = PROGRESS_INITIAL
                val prevJob = job
                job = scope.launch {
                        prevJob?.cancelAndJoin()
                        val e = (state.value as? PageState.Error)?.error
                        if (e != null && ExceptionResolver.canResolve(e)) {
                                if (isFromUser) {
                                        exceptionResolver.resolve(e)
                                }
                        }
                        withContext(Dispatchers.Default) {
                                doLoad(page, force = true)
                        }
                }
        }

        fun showErrorDetails(url: String?) {
                val e = (state.value as? PageState.Error)?.error ?: return
                exceptionResolver.showErrorDetails(e, url)
        }

        fun onRecycle() {
                state.value = PageState.Empty
                cachedBounds = null
                currentPage = null
                originalDisplayUri = null
                lastProgress = PROGRESS_INITIAL
                job?.cancel()
        }

        fun evictFromMemory() {
                state.value = PageState.Empty
                cachedBounds = null
                currentPage = null
                originalDisplayUri = null
                lastProgress = PROGRESS_INITIAL
                job?.cancel()
        }

        override fun onImageLoaded() {
                state.update { currentState ->
                        if (currentState is PageState.Loaded) {
                                PageState.Shown(currentState.source, currentState.isConverted)
                        } else {
                                currentState
                        }
                }
        }

        fun restoreShownImage(): Boolean {
                val shownState = state.value as? PageState.Shown ?: return false
                state.value = PageState.Loaded(shownState.source, shownState.isConverted)
                return true
        }

        override fun onImageLoadError(e: Throwable) {
                e.printStackTraceDebug()

                state.update { currentState ->
                        val source: ImageSource
                        val isConverted: Boolean
                        when (currentState) {
                                is PageState.Loaded -> {
                                        source = currentState.source
                                        isConverted = currentState.isConverted
                                }

                                is PageState.Shown -> {
                                        source = currentState.source
                                        isConverted = currentState.isConverted
                                }

                                else -> return@update currentState
                        }
                        val uri = (source as? ImageSource.Uri)?.uri
                        if (tryRestoreOriginalDisplay(uri)) {
                                return@update PageState.Loading(null, lastProgress)
                        }
                        val sourceArchiveUri = currentPage?.url?.toUri()?.takeIf { it.isZipUri() }
                        val conversionUri = sourceArchiveUri ?: uri
                        if (!isConverted && conversionUri != null && (conversionUri.isZipUri() || e is IOException)) {
                                tryConvert(conversionUri, e.asException())
                                PageState.Converting()
                        } else {
                                PageState.Error(e)
                        }
                }
        }

        private fun tryRestoreOriginalDisplay(currentUri: Uri?): Boolean {
                val fallbackUri = originalDisplayUri ?: return false
                if (currentUri == null || currentUri == fallbackUri) {
                        return false
                }
                val prevJob = job
                val newJob = scope.launch(Dispatchers.Default) {
                        prevJob?.join()
                        state.value = PageState.Loading(null, lastProgress)
                        try {
                                cachedBounds = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
                                        loader.getTrimmedBounds(fallbackUri)
                                } else {
                                        null
                                }
                                state.value = PageState.Loaded(fallbackUri.toImageSource(cachedBounds), isConverted = false)
                        } catch (ce: CancellationException) {
                                throw ce
                        } catch (e: Throwable) {
                                e.printStackTraceDebug()
                                state.value = PageState.Error(e)
                        }
                }
                job = newJob
                return true
        }

        private fun Throwable.asException(): Exception = this as? Exception ?: IOException(this)

        private fun tryConvert(uri: Uri, e: Exception) {
                val prevJob = job
                // Capture the current page at call time so a concurrent onBind() to a
                // different page cannot cause the conversion result to be applied to
                // the wrong page (which would show the converted image but with the
                // wrong page metadata).
                val pageAtConvertTime = currentPage
                val newJob = scope.launch(Dispatchers.Default) {
                        prevJob?.join()
                        // If the page changed while waiting for prevJob, abort conversion —
                        // the new page has its own load in flight.
                        if (currentPage != pageAtConvertTime) return@launch
                        state.value = PageState.Converting()
                        try {
                                val newUri = loader.convertBimap(uri)
                                // Re-check after the suspend call — the page may have changed
                                // during convertBimap().
                                if (currentPage != pageAtConvertTime) return@launch
                                cachedBounds = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
                                        loader.getTrimmedBounds(newUri)
                                } else {
                                        null
                                }
                                state.value = PageState.Loaded(newUri.toImageSource(cachedBounds), isConverted = true)
                        } catch (ce: CancellationException) {
                                throw ce
                        } catch (e2: Throwable) {
                                e2.printStackTraceDebug()
                                // Report the conversion failure as the primary error, keeping
                                // the original load error attached for diagnostics.
                                e2.addSuppressed(e)
                                state.value = PageState.Error(e2)
                        }
                }
                job = newJob
        }

        @WorkerThread
        private suspend fun doLoad(data: MangaPage, force: Boolean) = coroutineScope {
                state.value = PageState.Loading(null, lastProgress)
                val previewJob = launch {
                        val preview = loader.loadPreview(data) ?: return@launch
                        state.update {
                                if (it is PageState.Loading) it.copy(preview = preview) else it
                        }
                }
                try {
                        val task = loader.loadPageAsync(data, force)
                        val progressObserver = observeProgress(this, task.progressAsFlow())
                        val uri = task.await()
                        progressObserver.cancelAndJoin()
                        previewJob.cancel()
                        lastProgress = PROGRESS_COMPLETE
                        state.update {
                                if (it is PageState.Loading) it.copy(progress = PROGRESS_COMPLETE) else it
                        }
                        val originalUri = if (uri.isZipUri()) {
                                loader.materializeZipEntry(uri)
                        } else {
                                uri
                        }
                        originalDisplayUri = originalUri
                        val displayUri = loader.enhanceForReader(data, originalUri, isWebtoon)
                        cachedBounds = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
                                loader.getTrimmedBounds(displayUri)
                        } else {
                                null
                        }
                        state.value = PageState.Loaded(displayUri.toImageSource(cachedBounds), isConverted = false)
                } catch (e: CancellationException) {
                        throw e
                } catch (e: Throwable) {
                        e.printStackTraceDebug()
                        // Cancel the preview job on error — without this, the preview
                        // load continues running in the background even after the page
                        // load has failed, wasting bandwidth and potentially causing
                        // the preview to overwrite the Error state with a Loading state.
                        previewJob.cancel()
                        state.value = PageState.Error(e)
                        if (e is IOException && data.requiresNetwork() && !networkState.value) {
                                networkState.awaitForConnection()
                                retry(data, isFromUser = false)
                        }
                }
        }

        private fun observeProgress(scope: CoroutineScope, progress: Flow<Float>) = progress
                .throttle(250)
                .onEach {
                        if (it < 0f) {
                                return@onEach
                        }
                        val progressValue = (100 * it).toInt().coerceIn(PROGRESS_INITIAL, PROGRESS_COMPLETE)
                        lastProgress = progressValue
                        state.update { currentState ->
                                if (currentState is PageState.Loading) {
                                        currentState.copy(progress = progressValue)
                                } else {
                                        currentState
                                }
                        }
                }.launchIn(scope)

        private fun Uri.toImageSource(bounds: Rect?): ImageSource {
                val source = ImageSource.uri(this)
                return if (bounds != null) {
                        source.region(bounds)
                } else {
                        source
                }
        }

        private fun MangaPage?.isSamePageAs(other: MangaPage): Boolean {
                val current = this ?: return false
                return current.id == other.id &&
                        current.url == other.url &&
                        current.source == other.source
        }

        private fun MangaPage.requiresNetwork(): Boolean {
                val uri = url.toUri()
                return source != LocalMangaSource && !uri.isFileUri() && !uri.isZipUri()
        }

        private companion object {

                const val PROGRESS_INITIAL = 0
                const val PROGRESS_COMPLETE = 100
        }
}
