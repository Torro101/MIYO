package org.koharu.miyo.core.network.cookies

import android.content.Context
import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidCookieJar(
	context: Context,
) : MutableCookieJar {

	private val cookieManager = CookieManager.getInstance()
	private val persistentJar = PreferencesCookieJar(context, persistSessionCookies = true)

	@WorkerThread
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val persistedCookies = persistentJar.loadForRequest(url)
		if (persistedCookies.isNotEmpty()) {
			saveToWebView(url, persistedCookies)
		}
		val webViewCookies = getWebViewCookies(url)
		return (webViewCookies + persistedCookies).distinctBy {
			"${it.name};${it.domain};${it.path}"
		}
	}

	@WorkerThread
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		persistentJar.saveFromResponse(url, cookies)
		saveToWebView(url, cookies)
		flush()
	}

	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		val cookies = loadForRequest(url)
		if (cookies.isEmpty()) {
			return
		}
		val urlString = url.toString()
		for (c in cookies) {
			if (predicate != null && !predicate.test(c)) {
				continue
			}
			val nc = c.newBuilder()
				.expiresAt(System.currentTimeMillis() - 100000)
				.build()
			cookieManager.setCookie(urlString, nc.toString())
		}
		persistentJar.removeCookies(url, predicate)
		flush()
	}

	@WorkerThread
	override fun saveFromWebView(url: HttpUrl): Boolean {
		val cookies = getWebViewCookies(url)
		if (cookies.isEmpty()) {
			return false
		}
		persistentJar.saveFromResponse(url, cookies)
		flush()
		return true
	}

	override fun flush() {
		cookieManager.flush()
		persistentJar.flush()
	}

	override suspend fun clear(): Boolean {
		val removed = suspendCoroutine<Boolean> { continuation ->
			cookieManager.removeAllCookies(continuation::resume)
		}
		persistentJar.clear()
		flush()
		return removed
	}

	private fun saveToWebView(url: HttpUrl, cookies: List<Cookie>) {
		val urlString = url.toString()
		for (cookie in cookies) {
			cookieManager.setCookie(urlString, cookie.toString())
		}
	}

	private fun getWebViewCookies(url: HttpUrl): List<Cookie> {
		val rawCookie = cookieManager.getCookie(url.toString()) ?: return emptyList()
		return rawCookie.split(';').mapNotNull {
			parseWebViewCookie(url, it)
		}
	}

	private fun parseWebViewCookie(url: HttpUrl, rawCookie: String): Cookie? {
		val separator = rawCookie.indexOf('=')
		if (separator <= 0) {
			return null
		}
		val name = rawCookie.substring(0, separator).trim()
		val value = rawCookie.substring(separator + 1).trim()
		if (name.isEmpty()) {
			return null
		}
		val path = if (CloudFlareHelper.isCloudFlareCookie(name)) {
			"/"
		} else {
			url.encodedPath.takeIf { it.startsWith('/') } ?: "/"
		}
		return runCatching {
			Cookie.Builder()
				.name(name)
				.value(value)
				.hostOnlyDomain(url.host)
				.path(path)
				.apply {
					if (url.isHttps) {
						secure()
					}
				}
				.build()
		}.getOrNull()
	}
}
