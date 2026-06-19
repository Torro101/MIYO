package org.koharu.miyo.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.koharu.miyo.core.exceptions.CloudFlareBlockedException
import org.koharu.miyo.core.exceptions.CloudFlareProtectedException
import org.koharu.miyo.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class CloudFlareInterceptor(
	private val cookieJar: MutableCookieJar,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val protectedRequest = response.request
		return when (CloudFlareHelper.checkResponseForProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> {
				clearCloudFlareCookies(protectedRequest.url)
				response.closeThrowing(
					CloudFlareBlockedException(
						url = protectedRequest.url.toString(),
						source = protectedRequest.tag(MangaSource::class.java) ?: request.tag(MangaSource::class.java),
					),
				)
			}

			CloudFlareHelper.PROTECTION_CAPTCHA -> {
				clearCloudFlareCookies(protectedRequest.url)
				response.closeThrowing(
					CloudFlareProtectedException(
						url = protectedRequest.url.toString(),
						source = protectedRequest.tag(MangaSource::class.java) ?: request.tag(MangaSource::class.java),
						headers = protectedRequest.headers,
					),
				)
			}

			else -> response
		}
	}

	private fun clearCloudFlareCookies(url: HttpUrl) {
		runCatching {
			cookieJar.removeCookies(url) { cookie ->
				CloudFlareHelper.isCloudFlareCookie(cookie.name)
			}
			cookieJar.flush()
		}
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}
}
