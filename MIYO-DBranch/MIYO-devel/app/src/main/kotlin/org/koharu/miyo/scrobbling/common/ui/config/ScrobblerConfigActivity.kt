package org.koharu.miyo.scrobbling.common.ui.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koharu.miyo.R
import org.koharu.miyo.core.exceptions.resolve.SnackbarErrorObserver
import org.koharu.miyo.core.nav.AppRouter
import org.koharu.miyo.core.nav.router
import org.koharu.miyo.core.ui.BaseActivity
import org.koharu.miyo.core.ui.list.OnListItemClickListener
import org.koharu.miyo.core.util.ext.consumeAllSystemBarsInsets
import org.koharu.miyo.core.util.ext.observe
import org.koharu.miyo.core.util.ext.observeEvent
import org.koharu.miyo.core.util.ext.showOrHide
import org.koharu.miyo.core.util.ext.systemBarsInsets
import org.koharu.miyo.databinding.ActivityScrobblerConfigBinding
import org.koharu.miyo.list.ui.adapter.TypedListSpacingDecoration
import org.koharu.miyo.scrobbling.common.domain.model.ScrobblerService
import org.koharu.miyo.scrobbling.common.domain.model.ScrobblerUser
import org.koharu.miyo.scrobbling.common.domain.model.ScrobblingInfo
import org.koharu.miyo.scrobbling.common.ui.config.adapter.ScrobblingMangaAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import androidx.appcompat.R as appcompatR

internal object ScrobblerAuthHandoff {

	private val pendingCodes = ConcurrentHashMap<String, PendingCode>()

	fun put(code: String): String {
		pruneExpired()
		val token = UUID.randomUUID().toString()
		pendingCodes[token] = PendingCode(code, System.currentTimeMillis() + TOKEN_TTL)
		return token
	}

	fun put(context: Context, code: String): String {
		val token = put(code)
		val pendingCode = pendingCodes[token] ?: return token
		pruneExpired(context)
		context.handoffPrefs.edit()
			.putString(token, "${pendingCode.expiresAt}\n${pendingCode.code}")
			.apply()
		return token
	}

	fun take(token: String?): String? {
		val pendingCode = token?.let(pendingCodes::remove) ?: return null
		return pendingCode.code.takeIf { pendingCode.expiresAt >= System.currentTimeMillis() }
	}

	fun take(context: Context, token: String?): String? {
		take(token)?.let {
			context.handoffPrefs.edit().remove(token).apply()
			return it
		}
		if (token.isNullOrEmpty()) {
			return null
		}
		val rawValue = context.handoffPrefs.getString(token, null) ?: return null
		context.handoffPrefs.edit().remove(token).apply()
		val expiresAt = rawValue.substringBefore('\n').toLongOrNull() ?: return null
		val code = rawValue.substringAfter('\n', "")
		return code.takeIf { expiresAt >= System.currentTimeMillis() }
	}

	fun take(context: Context, token: String?, expectedCode: String): Boolean {
		return take(context, token) == expectedCode
	}

	private fun pruneExpired() {
		val now = System.currentTimeMillis()
		pendingCodes.entries.removeAll { it.value.expiresAt < now }
	}

	private fun pruneExpired(context: Context) {
		val now = System.currentTimeMillis()
		val prefs = context.handoffPrefs
		val expiredKeys = prefs.all.mapNotNull { (key, value) ->
			val expiresAt = (value as? String)?.substringBefore('\n')?.toLongOrNull()
			key.takeIf { expiresAt != null && expiresAt < now }
		}
		if (expiredKeys.isNotEmpty()) {
			prefs.edit().apply {
				expiredKeys.forEach { remove(it) }
			}.apply()
		}
	}

	private val Context.handoffPrefs
		get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private data class PendingCode(
		val code: String,
		val expiresAt: Long,
	)

	private const val TOKEN_TTL = 10 * 60 * 1000L
	private const val PREFS_NAME = "scrobbler_auth_handoff"
}

@AndroidEntryPoint
class ScrobblerConfigActivity : BaseActivity<ActivityScrobblerConfigBinding>(),
	OnListItemClickListener<ScrobblingInfo>, View.OnClickListener {

	private val viewModel: ScrobblerConfigViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (!hasScrobblerTarget(intent)) {
			finishAfterTransition()
			return
		}
		setContentView(ActivityScrobblerConfigBinding.inflate(layoutInflater))
		setTitle(viewModel.titleResId)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

		val listAdapter = ScrobblingMangaAdapter(this)
		with(viewBinding.recyclerView) {
			adapter = listAdapter
			setHasFixedSize(true)
			val decoration = TypedListSpacingDecoration(context, false)
			addItemDecoration(decoration)
		}
		viewBinding.imageViewAvatar.setOnClickListener(this)

		viewModel.content.observe(this, listAdapter)
		viewModel.user.observe(this, this::onUserChanged)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.onError.observeEvent(
			this,
			SnackbarErrorObserver(viewBinding.recyclerView, null, exceptionResolver, null),
		)
		viewModel.onLoggedOut.observeEvent(this) {
			finishAfterTransition()
		}

		processIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		processIntent(intent)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding.appbar.updatePadding(
			top = barsInsets.top,
			left = barsInsets.left,
			right = barsInsets.right,
		)
		viewBinding.recyclerView.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: ScrobblingInfo, view: View) {
		router.openDetails(item.mangaId)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.imageView_avatar -> showUserDialog()
		}
	}

	private fun hasScrobblerTarget(intent: Intent?): Boolean {
		val serviceId = intent?.getIntExtra(AppRouter.KEY_ID, 0) ?: 0
		if (serviceId != 0 && ScrobblerService.entries.any { it.id == serviceId }) {
			return true
		}
		val uri = intent?.getParcelableExtra<Uri>(AppRouter.KEY_DATA) ?: intent?.data
		return when (uri?.host) {
			HOST_SHIKIMORI_AUTH,
			HOST_ANILIST_AUTH,
			HOST_MAL_AUTH -> true

			else -> false
		}
	}

	private fun processIntent(intent: Intent) {
		val code = if (intent.action == Intent.ACTION_VIEW) {
			val uri = intent.data
			val authCode = uri?.getQueryParameter("code")
			val state = uri?.getQueryParameter("state")
			val serviceName = viewModel.scrobblerService.name
			if (!authCode.isNullOrEmpty() && ScrobblerAuthHandoff.take(this, state, serviceName)) {
				authCode
			} else {
				null
			}
		} else {
			ScrobblerAuthHandoff.take(intent.getStringExtra(EXTRA_AUTH_TOKEN))
		}
		if (!code.isNullOrEmpty()) {
			viewModel.onAuthCodeReceived(code)
		}
	}

	private fun onUserChanged(user: ScrobblerUser?) {
		if (user == null) {
			viewBinding.imageViewAvatar.disposeImage()
			viewBinding.imageViewAvatar.setImageResource(appcompatR.drawable.abc_ic_menu_overflow_material)
			return
		}
		viewBinding.imageViewAvatar.setImageAsync(user.avatar)
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.progressBar.showOrHide(isLoading)
	}

	private fun showUserDialog() {
		MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setMessage(getString(R.string.logged_in_as, viewModel.user.value?.nickname))
			.setNegativeButton(R.string.close, null)
			.setPositiveButton(R.string.logout) { _, _ ->
				viewModel.logout()
			}.show()
	}

	companion object {
		const val EXTRA_AUTH_TOKEN = "auth_token"
		const val HOST_SHIKIMORI_AUTH = "shikimori-auth"
		const val HOST_ANILIST_AUTH = "anilist-auth"
		const val HOST_MAL_AUTH = "mal-auth"
	}
}
