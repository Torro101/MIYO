package org.koharu.miyo.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koharu.miyo.R
import org.koharu.miyo.browser.BaseBrowserActivity
import org.koharu.miyo.core.exceptions.CloudFlareProtectedException
import org.koharu.miyo.core.exceptions.resolve.CaptchaHandler
import org.koharu.miyo.core.model.MangaSource
import org.koharu.miyo.core.nav.AppRouter
import org.koharu.miyo.core.network.CommonHeaders
import org.koharu.miyo.core.network.cookies.MutableCookieJar
import org.koharu.miyo.core.network.webview.CaptchaSessionVerifier
import org.koharu.miyo.core.parser.ParserMangaRepository
import org.koharu.miyo.core.util.ext.getDisplayMessage
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {

	private var pendingResult = RESULT_CANCELED
	private var isCompletingSuccessfully = false
	private lateinit var cloudFlareClient: CloudFlareClient

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaSessionVerifier: CaptchaSessionVerifier

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		cloudFlareClient = CloudFlareClient(cookieJar, this, adBlock, url)
		viewBinding.webView.webViewClient = cloudFlareClient
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				onTitleChanged(getString(R.string.loading_), url)
				prepareWebViewCookies(url)
				cloudFlareClient.resetClearanceBaseline()
				viewBinding.webView.loadUrl(url, getInitialHeaders())
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		R.id.action_done -> {
			onCheckPassed()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		cookieJar.flush()
		setResult(pendingResult)
		super.finish()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	override fun onPageLoaded() {
		viewBinding.progressBar.isInvisible = true
		persistVisibleCookiesAsync()
	}

	override fun onCheckPassed() {
		if (isCompletingSuccessfully) {
			return
		}
		isCompletingSuccessfully = true
		lifecycleScope.launch {
			runCatchingCancellable {
				persistVisibleCookies()
			}.onFailure {
				it.printStackTraceDebug()
			}
			val scanResult = runCatchingCancellable {
				captchaSessionVerifier.scan(
					urls = currentCaptchaCookieUrls().map { it.toString() },
					headers = getSessionVerificationHeaders(),
					sourceName = intent?.getStringExtra(AppRouter.KEY_SOURCE),
				).resultFor(intent?.dataString)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(CaptchaSessionVerifier.Result.UnverifiedNetworkError)
			if (scanResult != CaptchaSessionVerifier.Result.Verified) {
				pendingResult = RESULT_CANCELED
				isCompletingSuccessfully = false
				val message = when (scanResult) {
					CaptchaSessionVerifier.Result.UnverifiedNetworkError -> R.string.network_error
					else -> R.string.captcha_required_message
				}
				Snackbar.make(viewBinding.webView, message, Snackbar.LENGTH_LONG).show()
				return@launch
			}
			pendingResult = RESULT_OK
			viewBinding.webView.stopLoading()
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(MangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	override fun onPause() {
		persistVisibleCookiesAsync()
		super.onPause()
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			viewBinding.webView.stopLoading()
			yield()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				prepareWebViewCookies(targetUrl.toString())
				cloudFlareClient.resetClearanceBaseline()
				viewBinding.webView.loadUrl(targetUrl.toString(), getInitialHeaders())
			}
		}
	}

	private fun getInitialHeaders(): Map<String, String> {
		val referer = intent?.getStringExtra(AppRouter.KEY_REFERER)
		return if (referer.isNullOrEmpty()) {
			emptyMap()
		} else {
			mapOf(CommonHeaders.REFERER to referer)
		}
	}

	private fun getSessionVerificationHeaders(): Map<String, String> = buildMap {
		intent?.getStringExtra(AppRouter.KEY_REFERER)?.let {
			put(CommonHeaders.REFERER, it)
		}
		intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.let {
			put(CommonHeaders.USER_AGENT, it)
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
		cookieJar.flush()
	}

	private fun persistVisibleCookiesAsync() {
		val urls = currentCaptchaCookieUrls()
		if (urls.isEmpty()) {
			return
		}
		lifecycleScope.launch(Dispatchers.Default) {
			saveCookies(urls)
		}
	}

	private suspend fun persistVisibleCookies() {
		val urls = currentCaptchaCookieUrls()
		if (urls.isEmpty()) {
			return
		}
		runInterruptible(Dispatchers.Default) {
			saveCookies(urls)
		}
	}

	private fun saveCookies(urls: Collection<HttpUrl>) {
		for (url in urls) {
			cookieJar.saveFromWebView(url)
		}
		cookieJar.flush()
	}

	private fun currentCaptchaCookieUrls(): List<HttpUrl> {
		return listOfNotNull(
			viewBinding.webView.url?.toHttpUrlOrNull(),
			intent?.dataString?.toHttpUrlOrNull(),
			intent?.getStringExtra(AppRouter.KEY_REFERER)?.toHttpUrlOrNull(),
		).distinctBy { "${it.host}:${it.port}${it.encodedPath}" }
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
	}
}
