package org.koharu.miyo.core.network.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koharu.miyo.core.network.CloudFlareInterceptor
import org.koharu.miyo.core.network.CommonHeaders
import org.koharu.miyo.core.network.MangaHttpClient
import org.koharu.miyo.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import java.io.IOException
import javax.inject.Inject

class CaptchaSessionVerifier @Inject constructor(
	@MangaHttpClient private val okHttpClient: OkHttpClient,
	private val cookieJar: MutableCookieJar,
) {

	private val verifierClient by lazy(LazyThreadSafetyMode.PUBLICATION) {
		okHttpClient.newBuilder().apply {
			interceptors().removeAll { it is CloudFlareInterceptor }
		}.build()
	}

	suspend fun verify(
		url: String?,
		headers: Map<String, String>,
		sourceName: String?,
	): Result {
		return scan(listOf(url), headers, sourceName).bestResult
	}

	suspend fun scan(
		urls: Collection<String?>,
		headers: Map<String, String>,
		sourceName: String?,
	): ScanReport = runInterruptible(Dispatchers.IO) {
		val candidates = urls.mapNotNull { it?.toHttpUrlOrNull() }
			.distinctBy { "${it.host}:${it.port}${it.encodedPath}" }
		if (candidates.isEmpty()) {
			return@runInterruptible ScanReport(emptyList())
		}
		ScanReport(candidates.map { url ->
			scanUrl(url, headers, sourceName)
		})
	}

	private fun scanUrl(
		httpUrl: HttpUrl,
		headers: Map<String, String>,
		sourceName: String?,
	): UrlResult {
		val cookies = cookieJar.loadForRequest(httpUrl)
		val hasCloudFlareCookie = cookies.any { CloudFlareHelper.isCloudFlareCookie(it.name) }
		val request = Request.Builder()
			.url(httpUrl)
			.get()
			.header(CommonHeaders.ACCEPT, ACCEPT_HTML)
			.header(CommonHeaders.CACHE_CONTROL, "no-cache")
			.apply {
				headers[CommonHeaders.REFERER]?.let {
					header(CommonHeaders.REFERER, it)
				}
				headers[CommonHeaders.USER_AGENT]?.let {
					header(CommonHeaders.USER_AGENT, it)
				}
				sourceName?.let {
					header(CommonHeaders.MANGA_SOURCE, it)
				}
			}
			.build()
		return try {
			verifierClient.newCall(request).execute().use { response ->
				val challengeMarkers = response.challengeMarkers()
				val status = when (CloudFlareHelper.checkResponseForProtection(response)) {
					CloudFlareHelper.PROTECTION_BLOCKED,
					CloudFlareHelper.PROTECTION_CAPTCHA -> Status.NeedsCaptcha

					else -> if (challengeMarkers.isEmpty()) {
						Status.Verified
					} else {
						Status.NeedsCaptcha
					}
				}
				UrlResult(
					url = httpUrl.toString(),
					finalUrl = response.request.url.toString(),
					status = status,
					statusCode = response.code,
					cookieCount = cookies.size,
					hasCloudFlareCookie = hasCloudFlareCookie,
					challengeMarkers = challengeMarkers,
				)
			}
		} catch (e: IOException) {
			UrlResult(
				url = httpUrl.toString(),
				finalUrl = null,
				status = Status.NetworkError,
				statusCode = null,
				cookieCount = cookies.size,
				hasCloudFlareCookie = hasCloudFlareCookie,
				challengeMarkers = emptySet(),
			)
		}
	}

	private fun Response.challengeMarkers(): Set<String> {
		val haystack = buildString {
			append(headers.toString())
			append('\n')
			append(runCatching { peekBody(MAX_SCAN_BYTES).string() }.getOrDefault(""))
		}.lowercase()
		return CHALLENGE_MARKERS.filterTo(mutableSetOf()) {
			haystack.contains(it)
		}
	}

	data class ScanReport(
		val results: List<UrlResult>,
	) {

		val bestResult: Result
			get() = when {
				results.isEmpty() -> Result.UnverifiedNetworkError
				results.any { it.status == Status.NeedsCaptcha } -> Result.NeedsCaptcha
				results.all { it.status == Status.Verified } -> Result.Verified
				else -> Result.UnverifiedNetworkError
			}

		fun resultFor(url: String?): Result {
			val target = url?.toHttpUrlOrNull() ?: return Result.UnverifiedNetworkError
			return results.firstOrNull { result ->
				result.url.toHttpUrlOrNull()?.sameRequestTarget(target) == true
			}?.result ?: Result.UnverifiedNetworkError
		}

		private fun HttpUrl.sameRequestTarget(other: HttpUrl): Boolean {
			return host == other.host && port == other.port && encodedPath == other.encodedPath
		}
	}

	data class UrlResult(
		val url: String,
		val finalUrl: String?,
		val status: Status,
		val statusCode: Int?,
		val cookieCount: Int,
		val hasCloudFlareCookie: Boolean,
		val challengeMarkers: Set<String>,
	) {

		val result: Result
			get() = when (status) {
				Status.Verified -> Result.Verified
				Status.NeedsCaptcha -> Result.NeedsCaptcha
				Status.NetworkError -> Result.UnverifiedNetworkError
			}
	}

	enum class Status {
		Verified,
		NeedsCaptcha,
		NetworkError,
	}

	fun clearCloudFlareCookies(url: HttpUrl) {
		runCatching {
			cookieJar.removeCookies(url) { cookie ->
				CloudFlareHelper.isCloudFlareCookie(cookie.name)
			}
			cookieJar.flush()
		}
	}

	enum class Result {
		Verified,
		NeedsCaptcha,
		UnverifiedNetworkError,
	}

	private companion object {

		private const val MAX_SCAN_BYTES = 192_000L

		private const val ACCEPT_HTML =
			"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

		private val CHALLENGE_MARKERS = arrayOf(
			"just a moment",
			"performing security verification",
			"checking your browser",
			"cf-chl",
			"__cf_chl_tk",
			"cdn-cgi/challenge-platform",
			"turnstile",
			"hcaptcha",
			"recaptcha",
		)
	}
}
