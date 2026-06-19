package org.koharu.miyo.scrobbling.discord.ui

import android.graphics.Bitmap
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koharu.miyo.browser.BrowserCallback
import org.koharu.miyo.browser.BrowserClient
import org.koitharu.kotatsu.parsers.util.removeSurrounding

class DiscordTokenWebClient(private val callback: Callback) : BrowserClient(callback, null) {

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		if (view != null && url.isDiscordHost()) {
			checkToken(view)
		}
	}

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		if (url.isDiscordHost()) {
			checkToken(webView)
		}
	}

	private fun checkToken(view: WebView) {
		view.evaluateJavascript("window.localStorage.token") { result ->
			val token = result
				?.replace("\\\"", "")
				?.removeSurrounding('"')
				?.takeUnless { it == "null" }
			if (!token.isNullOrEmpty()) {
				callback.onTokenObtained(token)
			}
		}
	}

	private fun String?.isDiscordHost(): Boolean {
		val host = this?.toHttpUrlOrNull()?.host ?: return false
		return host.equals(DISCORD_HOST, ignoreCase = true) || host.endsWith(".$DISCORD_HOST", ignoreCase = true)
	}

	interface Callback : BrowserCallback {

		fun onTokenObtained(token: String)
	}

	private companion object {

		const val DISCORD_HOST = "discord.com"
	}
}
