package org.koharu.miyo.core.network.webview

import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koharu.miyo.core.exceptions.CloudFlareException
import org.koharu.miyo.core.exceptions.CloudFlareProtectedException
import org.koharu.miyo.core.network.CommonHeaders
import org.koharu.miyo.core.network.cookies.MutableCookieJar
import org.koharu.miyo.core.network.proxy.ProxyProvider
import org.koharu.miyo.core.parser.MangaRepository
import org.koharu.miyo.core.parser.ParserMangaRepository
import org.koharu.miyo.core.util.ext.configureForParser
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val captchaSessionVerifier: CaptchaSessionVerifier,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	val defaultUserAgent: String? by lazy {
		try {
			WebSettings.getDefaultUserAgent(context)
		} catch (e: AndroidRuntimeException) {
			e.printStackTraceDebug()
			// Probably WebView is not available
			null
		}
	}

	suspend fun evaluateJs(baseUrl: String?, script: String): String? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				if (!baseUrl.isNullOrEmpty()) {
					suspendCoroutine { cont ->
						webView.webViewClient = ContinuationResumeWebViewClient(cont)
						webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
					}
				}
				suspendCoroutine { cont ->
					webView.evaluateJavascript(script) { result ->
						cont.resume(result?.takeUnless { it == "null" })
					}
				}
			} finally {
				webView.reset()
			}
		}
	}

	suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean = mutex.withLock {
		runCatchingCancellable {
			withContext(Dispatchers.Main.immediate) {
				val webView = obtainWebView()
				try {
					exception.requestedUserAgent()?.let {
						webView.settings.userAgentString = it
					}
					seedCookies(exception)
					withTimeout(timeout) {
						suspendCancellableCoroutine { cont ->
							var verifierJob: Job? = null
							var client: CaptchaContinuationClient? = null
							cont.invokeOnCancellation {
								verifierJob?.cancel()
							}
							client = CaptchaContinuationClient(
								cookieJar = cookieJar,
								targetUrl = exception.url,
								continuation = cont,
								onMaybeSolved = { view ->
									if (cont.isActive && verifierJob?.isActive != true) {
										verifierJob = launch {
											val isVerified = runCatchingCancellable {
												persistCookies(exception)
												captchaSessionVerifier.verify(
													url = exception.url,
													headers = exception.verificationHeaders(),
													sourceName = exception.source.name,
												) == CaptchaSessionVerifier.Result.Verified
											}.onFailure { e ->
												e.printStackTraceDebug()
											}.getOrDefault(false)
											if (isVerified) {
												withContext(Dispatchers.Main.immediate) {
													if (cont.isActive) {
														client?.resumeAfterVerification(view ?: webView)
													}
												}
											}
										}
									}
								},
							)
							webView.webViewClient = checkNotNull(client)
							webView.loadUrl(exception.url, exception.initialHeaders())
						}
						persistCookies(exception)
						check(
							captchaSessionVerifier.verify(
								url = exception.url,
								headers = exception.verificationHeaders(),
								sourceName = exception.source.name,
							) == CaptchaSessionVerifier.Result.Verified,
						)
					}
				} finally {
					webView.reset()
				}
			}
		}.onFailure { e ->
			exception.addSuppressed(e)
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun obtainWebView(): WebView {
		webViewCached?.get()?.let {
			return it
		}
		return withContext(Dispatchers.Main.immediate) {
			webViewCached?.get()?.let {
				return@withContext it
			}
			WebView(context).also {
				it.configureForParser(null)
				webViewCached = WeakReference(it)
				proxyProvider.applyWebViewConfig()
				it.onResume()
				it.resumeTimers()
			}
		}
	}

	private fun MangaSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserMangaRepository
		return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
	}

	private fun CloudFlareException.requestedUserAgent(): String? {
		return (this as? CloudFlareProtectedException)?.headers?.get(CommonHeaders.USER_AGENT)
			?: source.getUserAgent()
	}

	private fun CloudFlareException.initialHeaders(): Map<String, String> {
		val referer = (this as? CloudFlareProtectedException)?.headers?.get(CommonHeaders.REFERER)
		return if (referer.isNullOrEmpty()) {
			emptyMap()
		} else {
			mapOf(CommonHeaders.REFERER to referer)
		}
	}

	private fun CloudFlareException.verificationHeaders(): Map<String, String> = buildMap {
		(this@verificationHeaders as? CloudFlareProtectedException)?.headers?.get(CommonHeaders.REFERER)?.let {
			put(CommonHeaders.REFERER, it)
		}
		requestedUserAgent()?.let {
			put(CommonHeaders.USER_AGENT, it)
		}
	}

	private suspend fun seedCookies(exception: CloudFlareException) = runInterruptible(Dispatchers.Default) {
		val urls = buildList {
			exception.url.toHttpUrlOrNull()?.let(::add)
			(exception as? CloudFlareProtectedException)
				?.headers
				?.get(CommonHeaders.REFERER)
				?.toHttpUrlOrNull()
				?.let(::add)
		}.distinctBy { it.cookiePersistenceKey() }
		for (url in urls) {
			cookieJar.loadForRequest(url)
		}
		cookieJar.flush()
	}

	private fun okhttp3.HttpUrl.cookiePersistenceKey(): String {
		return "$host:$port$encodedPath"
	}

	private suspend fun persistCookies(exception: CloudFlareException) {
		val urls = buildList {
			exception.url.toHttpUrlOrNull()?.let(::add)
			(exception as? CloudFlareProtectedException)
				?.headers
				?.get(CommonHeaders.REFERER)
				?.toHttpUrlOrNull()
				?.let(::add)
		}.distinctBy { it.cookiePersistenceKey() }
		if (urls.isEmpty()) {
			return
		}
		runInterruptible(Dispatchers.Default) {
			for (url in urls) {
				cookieJar.saveFromWebView(url)
			}
			cookieJar.flush()
		}
	}

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}
}
