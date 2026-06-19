package org.koharu.miyo.scrobbling.common.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.koharu.miyo.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koharu.miyo.scrobbling.common.domain.model.ScrobblerService
import org.koharu.miyo.scrobbling.common.domain.model.ScrobblerUser
import org.koharu.miyo.scrobbling.common.ui.config.ScrobblerAuthHandoff
import org.koharu.miyo.scrobbling.kitsu.ui.KitsuAuthActivity
import javax.inject.Inject

class ScrobblerAuthHelper @Inject constructor(
	private val repositoriesMap: ScrobblerRepositoryMap,
) {

	fun isAuthorized(scrobbler: ScrobblerService) = repositoriesMap[scrobbler].isAuthorized

	fun getCachedUser(scrobbler: ScrobblerService): ScrobblerUser? {
		return repositoriesMap[scrobbler].cachedUser
	}

	suspend fun getUser(scrobbler: ScrobblerService): ScrobblerUser {
		return repositoriesMap[scrobbler].loadUser()
	}

	@SuppressLint("UnsafeImplicitIntentLaunch")
	fun startAuth(context: Context, scrobbler: ScrobblerService) = runCatching {
		if (scrobbler == ScrobblerService.KITSU) {
			launchKitsuAuth(context)
		} else {
			val repository = repositoriesMap[scrobbler]
			val state = ScrobblerAuthHandoff.put(context, scrobbler.name)
			val intent = Intent(Intent.ACTION_VIEW)
			intent.data = repository.oauthUrl.toUri()
				.buildUpon()
				.appendQueryParameter("state", state)
				.build()
			context.startActivity(intent)
		}
	}

	private fun launchKitsuAuth(context: Context) {
		context.startActivity(Intent(context, KitsuAuthActivity::class.java))
	}
}
