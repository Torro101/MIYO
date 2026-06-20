package org.koharu.miyo.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koharu.miyo.R
import org.koharu.miyo.core.exceptions.InteractiveActionRequiredException
import org.koharu.miyo.core.nav.AppRouter
import org.koharu.miyo.core.nav.router
import org.koharu.miyo.core.parser.ParserMangaRepository
import org.koharu.miyo.core.util.ext.getDisplayMessage
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		val url = intent?.dataString
		val navigationGuardTargetUrl = url.takeIf {
			intent?.getBooleanExtra(AppRouter.KEY_IS_INTERACTIVE_ACTION, false) == true
		}
		viewBinding.webView.webViewClient = BrowserClient(this, adBlock, navigationGuardTargetUrl)
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				if (url.isNullOrEmpty()) {
					finishAfterTransition()
				} else {
					onTitleChanged(
						intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
						url,
					)
					prepareWebViewCookies(url)
					viewBinding.webView.loadUrl(url)
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_browser, menu)
		menu.findItem(R.id.action_done)?.isVisible =
			intent?.getBooleanExtra(AppRouter.KEY_IS_INTERACTIVE_ACTION, false) == true
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			setResult(RESULT_CANCELED)
			finishAfterTransition()
			true
		}

		R.id.action_browser -> {
			if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
				Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			true
		}

		R.id.action_done -> {
			persistWebViewCookiesAsync()
			setResult(RESULT_OK)
			finishAfterTransition()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	class Contract : ActivityResultContract<InteractiveActionRequiredException, Boolean>() {
		override fun createIntent(
			context: Context,
			input: InteractiveActionRequiredException
		): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = org.koharu.miyo.core.model.MangaSource(input.sourceName),
			title = null,
		).putExtra(AppRouter.KEY_IS_INTERACTIVE_ACTION, true)

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == RESULT_OK
	}

	companion object {

		const val TAG = "BrowserActivity"
	}
}
