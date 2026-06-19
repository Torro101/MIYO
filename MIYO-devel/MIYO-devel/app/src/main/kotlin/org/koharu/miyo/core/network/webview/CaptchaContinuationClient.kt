package org.koharu.miyo.core.network.webview

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.koharu.miyo.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation

class CaptchaContinuationClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	continuation: Continuation<Unit>,
	private val onMaybeSolved: (WebView?) -> Unit,
) : ContinuationResumeWebViewClient(continuation) {

	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

	override fun onPageFinished(view: WebView?, url: String?) {
		checkClearance(view)
		onMaybeSolved(view)
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		checkClearance(view)
	}

	@Deprecated("Deprecated in Java")
	override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
		return CaptchaNavigationGuard.shouldBlockMainFrameNavigation(url, targetUrl) ||
			super.shouldOverrideUrlLoading(view, url)
	}

	override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
		val isMainFrame = request?.isForMainFrame ?: true
		if (isMainFrame && CaptchaNavigationGuard.shouldBlockMainFrameNavigation(request?.url?.toString(), targetUrl)) {
			return true
		}
		return super.shouldOverrideUrlLoading(view, request)
	}

	fun resumeAfterVerification(view: WebView?) {
		resumeContinuation(view)
	}

	private fun checkClearance(view: WebView?) {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (clearance != null && clearance != oldClearance) {
			resumeContinuation(view)
		}
	}
}
