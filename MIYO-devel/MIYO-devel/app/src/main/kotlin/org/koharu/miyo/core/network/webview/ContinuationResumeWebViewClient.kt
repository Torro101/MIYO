package org.koharu.miyo.core.network.webview

import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

open class ContinuationResumeWebViewClient(
	private val continuation: Continuation<Unit>,
) : WebViewClient() {

	private val resumed = AtomicBoolean(false)

	override fun onPageFinished(view: WebView?, url: String?) {
		resumeContinuation(view)
	}

	protected fun resumeContinuation(view: WebView?) {
		if (!resumed.compareAndSet(false, true)) {
			return
		}
		if (continuation is CancellableContinuation && !continuation.isActive) {
			return
		}
		view?.webViewClient = WebViewClient()
		continuation.resume(Unit)
	}
}
