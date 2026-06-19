package org.koharu.miyo.scrobbling.kitsu.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import org.koharu.miyo.R
import org.koharu.miyo.core.ui.BaseActivity
import org.koharu.miyo.core.ui.util.DefaultTextWatcher
import org.koharu.miyo.core.util.ext.consume
import org.koharu.miyo.core.nav.AppRouter
import org.koharu.miyo.databinding.ActivityKitsuAuthBinding
import org.koharu.miyo.scrobbling.common.domain.model.ScrobblerService
import org.koharu.miyo.scrobbling.common.ui.config.ScrobblerAuthHandoff
import org.koharu.miyo.scrobbling.common.ui.config.ScrobblerConfigActivity

class KitsuAuthActivity : BaseActivity<ActivityKitsuAuthBinding>(),
	View.OnClickListener,
	DefaultTextWatcher,
	TextView.OnEditorActionListener {

	private val regexEmail = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", RegexOption.IGNORE_CASE)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityKitsuAuthBinding.inflate(layoutInflater))
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.editEmail.addTextChangedListener(this)
		viewBinding.editEmail.setOnEditorActionListener(this)
		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.editPassword.setOnEditorActionListener(this)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val screenPadding = v.resources.getDimensionPixelOffset(R.dimen.screen_padding)
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		viewBinding.layoutEmail.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left + screenPadding
			rightMargin = barsInsets.right + screenPadding
		}
		viewBinding.layoutPassword.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left + screenPadding
			rightMargin = barsInsets.right + screenPadding
		}
		return insets.consume(v, typeMask)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> finish()
			R.id.button_done -> continueAuth()
		}
	}

	override fun onEditorAction(
		v: TextView,
		actionId: Int,
		event: KeyEvent?
	): Boolean = when (v.id) {
		R.id.edit_email -> {
			viewBinding.editPassword.requestFocus()
			true
		}

		R.id.edit_password -> {
			if (viewBinding.buttonDone.isEnabled) {
				continueAuth()
				true
			} else {
				false
			}
		}

		else -> false
	}

	override fun afterTextChanged(s: Editable?) {
		val email = viewBinding.editEmail.text?.toString()?.trim()
		val password = viewBinding.editPassword.text?.toString()?.trim()
		viewBinding.buttonDone.isEnabled = !email.isNullOrEmpty()
			&& !password.isNullOrEmpty()
			&& regexEmail.matches(email)
			&& password.length >= 3
	}

	private fun continueAuth() {
		val email = viewBinding.editEmail.text?.toString()?.trim().orEmpty()
		val password = viewBinding.editPassword.text?.toString()?.trim().orEmpty()
		val intent = Intent(this, ScrobblerConfigActivity::class.java)
			.putExtra(AppRouter.KEY_ID, ScrobblerService.KITSU.id)
			.putExtra(ScrobblerConfigActivity.EXTRA_AUTH_TOKEN, ScrobblerAuthHandoff.put("$email;$password"))
		startActivity(intent)
		finishAfterTransition()
	}
}
