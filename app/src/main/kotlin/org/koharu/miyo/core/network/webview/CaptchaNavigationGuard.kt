package org.koharu.miyo.core.network.webview

import android.content.Intent
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object CaptchaNavigationGuard {

	fun shouldBlockMainFrameNavigation(url: String?, targetUrl: String): Boolean {
		val rawUrl = url ?: return false
		if (rawUrl.isSafeNonHttpWebViewUrl()) {
			return false
		}
		val destination = rawUrl.navigationDestinationForHostCheck() ?: return true
		val httpUrl = destination.toHttpUrlOrNull() ?: return true
		val targetHost = targetUrl.toHttpUrlOrNull()?.host ?: return true
		return !httpUrl.host.matchesHost(targetHost) && !httpUrl.host.isTrustedCaptchaHost()
	}

	private fun String.isSafeNonHttpWebViewUrl(): Boolean = when (substringBefore(':', "").lowercase()) {
		"about", "data", "javascript", "blob" -> true
		else -> false
	}

	private fun String.navigationDestinationForHostCheck(): String? {
		if (!startsWith("intent:", ignoreCase = true)) {
			return this
		}
		return runCatching {
			val intent = Intent.parseUri(this, Intent.URI_INTENT_SCHEME)
			intent.getStringExtra(BROWSER_FALLBACK_URL) ?: intent.dataString
		}.getOrNull() ?: rawIntentWebUrl()
	}

	private fun String.rawIntentWebUrl(): String? {
		val target = substringAfter(':').substringBefore("#Intent;")
		val scheme = substringAfter("#Intent;", "")
			.split(';')
			.firstOrNull { it.startsWith("scheme=", ignoreCase = true) }
			?.substringAfter('=')
			?.takeIf { it.isNotBlank() }
			?: "https"
		return when {
			target.startsWith("//") -> "$scheme:$target"
			else -> "$scheme://$target"
		}.takeIf {
			it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
		}
	}

	private fun String.isTrustedCaptchaHost(): Boolean {
		return TRUSTED_CAPTCHA_HOSTS.any { matchesHost(it) }
	}

	private fun String.matchesHost(baseHost: String): Boolean {
		return equals(baseHost, ignoreCase = true) || endsWith(".$baseHost", ignoreCase = true)
	}

	private const val BROWSER_FALLBACK_URL = "browser_fallback_url"

	private val TRUSTED_CAPTCHA_HOSTS = setOf(
		"challenges.cloudflare.com",
		"captcha.cloudflare.com",
		"hcaptcha.com",
		"newassets.hcaptcha.com",
		"js.hcaptcha.com",
		"assets.hcaptcha.com",
		"google.com",
		"gstatic.com",
		"recaptcha.net",
	)
}
