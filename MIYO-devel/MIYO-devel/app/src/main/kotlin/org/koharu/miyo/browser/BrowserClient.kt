package org.koharu.miyo.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.WorkerThread
import org.koharu.miyo.core.network.webview.CaptchaNavigationGuard
import org.koharu.miyo.core.network.webview.adblock.AdBlock
import java.io.ByteArrayInputStream

open class BrowserClient(
	private val callback: BrowserCallback,
	private val adBlock: AdBlock?,
	private val navigationGuardTargetUrl: String? = null,
) : WebViewClient() {

	@Volatile
	private var currentPageUrl: String? = null

	/**
	 * https://stackoverflow.com/questions/57414530/illegalstateexception-reasonphrase-cant-be-empty-with-android-webview
	 */

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		currentPageUrl = url
		callback.onLoadingStateChanged(isLoading = false)
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		currentPageUrl = url
		callback.onLoadingStateChanged(isLoading = true)
	}

	@Deprecated("Deprecated in Java")
	override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
		return shouldOverrideUrlLoadingInternal(view, url, isMainFrame = true)
	}

	override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
		return shouldOverrideUrlLoadingInternal(
			view = view,
			url = request?.url?.toString(),
			isMainFrame = request?.isForMainFrame ?: true,
		)
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		currentPageUrl = url
		callback.onTitleChanged(view.title.orEmpty(), url)
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		callback.onHistoryChanged()
	}

	@WorkerThread
	@Deprecated("Deprecated in Java")
	override fun shouldInterceptRequest(
		view: WebView?,
		url: String?
	): WebResourceResponse? = if (url.isNullOrEmpty() || adBlock?.shouldLoadUrl(url, currentPageUrl) ?: true) {
		super.shouldInterceptRequest(view, url)
	} else {
		emptyResponse()
	}

	@WorkerThread
	override fun shouldInterceptRequest(
		view: WebView?,
		request: WebResourceRequest?
	): WebResourceResponse? =
		if (request == null || adBlock?.shouldLoadUrl(request.url.toString(), currentPageUrl) ?: true) {
			super.shouldInterceptRequest(view, request)
		} else {
			emptyResponse()
		}

	private fun emptyResponse(): WebResourceResponse =
		WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))

	private fun shouldOverrideUrlLoadingInternal(view: WebView?, url: String?, isMainFrame: Boolean): Boolean {
		if (isMainFrame && navigationGuardTargetUrl != null &&
			CaptchaNavigationGuard.shouldBlockMainFrameNavigation(url, navigationGuardTargetUrl)
		) {
			callback.onLoadingStateChanged(isLoading = false)
			return true
		}
		if (url.isNullOrBlank() || url.isWebViewSafeUrl()) {
			return false
		}
		val webFallbackUrl = url.toIntentWebFallbackUrl()
		if (webFallbackUrl != null) {
			view?.loadUrl(webFallbackUrl)
			return true
		}
		// Only auto-launch URLs whose scheme is on a small allowlist of
		// user-facing intent targets. Anything else (custom schemes, deep
		// links into other apps) is left to the WebView, which will at
		// least show a "no handler" error rather than silently launching
		// a third-party app on behalf of a JavaScript-driven tap.
		if (url.isExternallyLaunchableScheme()) {
			url.tryOpenExternal(view)
		}
		return true
	}

	private fun String.isExternallyLaunchableScheme(): Boolean {
		return substringBefore(':', "").lowercase() in EXTERNAL_LAUNCHABLE_SCHEMES
	}

	private fun String.isWebViewSafeUrl(): Boolean = when (substringBefore(':', "").lowercase()) {
		"http", "https", "about", "data", "javascript", "blob" -> true
		else -> false
	}

	private fun String.isWebUrl(): Boolean {
		return startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)
	}

	private fun String.toIntentWebFallbackUrl(): String? {
		if (!startsWith("intent:", ignoreCase = true)) {
			return null
		}
		return runCatching {
			val intent = Intent.parseUri(this, Intent.URI_INTENT_SCHEME)
			intent.getStringExtra(BROWSER_FALLBACK_URL)?.takeIf { it.isWebUrl() }
				?: intent.dataString?.takeIf { it.isWebUrl() }
		}.getOrNull()
	}

	private fun String.tryOpenExternal(view: WebView?): Boolean {
		val context = view?.context ?: return false
		return runCatching {
			val intent = if (startsWith("intent:", ignoreCase = true)) {
				Intent.parseUri(this, Intent.URI_INTENT_SCHEME)
			} else {
				Intent(Intent.ACTION_VIEW, Uri.parse(this))
			}.apply {
				addCategory(Intent.CATEGORY_BROWSABLE)
				component = null
				selector = null
			}
			context.startActivity(intent)
		}.isSuccess
	}

	private companion object {

		const val BROWSER_FALLBACK_URL = "browser_fallback_url"

		// Schemes that are safe to hand off to an external app without
		// further confirmation. Anything not on this list is left inside the
		// WebView so a JavaScript-driven tap cannot silently launch a
		// third-party app (intent:, market:, custom deep links, etc.).
		val EXTERNAL_LAUNCHABLE_SCHEMES = setOf(
			"http", "https", "ftp", "ftps",
			"mailto", "tel", "sms", "smsto", "mmsto",
			"geo", "maps",
		)
	}
}
