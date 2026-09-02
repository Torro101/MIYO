package org.koharu.miyo.main.ui.protect

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.koharu.miyo.core.exceptions.WrongPasswordException
import org.koharu.miyo.core.prefs.AppSettings
import org.koharu.miyo.core.security.PasswordHasher
import org.koharu.miyo.core.ui.BaseViewModel
import org.koharu.miyo.core.util.ext.MutableEventFlow
import org.koharu.miyo.core.util.ext.call
import javax.inject.Inject

private const val PASSWORD_COMPARE_DELAY = 1_000L

@HiltViewModel
class ProtectViewModel @Inject constructor(
	private val settings: AppSettings,
	private val protectHelper: AppProtectHelper,
) : BaseViewModel() {

	private var job: Job? = null

	val onUnlockSuccess = MutableEventFlow<Unit>()

	val isBiometricEnabled
		get() = settings.isBiometricProtectionEnabled

	val isNumericPassword
		get() = settings.isAppPasswordNumeric

	fun tryUnlock(password: String) {
		if (job?.isActive == true) {
			return
		}
		job = launchLoadingJob(Dispatchers.Default) {
			val appPasswordHash = settings.appPassword
			if (PasswordHasher.verify(password, appPasswordHash)) {
				if (PasswordHasher.shouldUpgrade(appPasswordHash)) {
					settings.appPassword = PasswordHasher.create(password)
				}
				unlock()
			} else {
				delay(PASSWORD_COMPARE_DELAY)
				throw WrongPasswordException()
			}
		}
	}

	fun unlock() {
		protectHelper.unlock()
		onUnlockSuccess.call(Unit)
	}
}
