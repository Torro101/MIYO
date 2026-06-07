package org.koharu.miyo.reader.ui.pager.standard

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koharu.miyo.R
import org.koharu.miyo.core.exceptions.resolve.ExceptionResolver
import org.koharu.miyo.core.model.ZoomMode
import org.koharu.miyo.core.os.NetworkState
import org.koharu.miyo.core.ui.widgets.ZoomControl
import org.koharu.miyo.databinding.ItemPageBinding
import org.koharu.miyo.reader.domain.PageLoader
import org.koharu.miyo.reader.ui.config.ReaderSettings
import org.koharu.miyo.reader.ui.pager.BasePageHolder
import org.koharu.miyo.reader.ui.pager.ReaderPage

open class PageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageBinding>(
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
), ZoomControl.ZoomControlListener, OnApplyWindowInsetsListener {

	override val ssiv = binding.ssiv

	init {
		ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			insets.toWindowInsets()?.let {
				applyRoundedCorners(it)
			}
		}
		return insets
	}

	override fun onConfigChanged(settings: ReaderSettings) {
		super.onConfigChanged(settings)
		binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
	}

	@SuppressLint("SetTextI18n")
	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		binding.textViewNumber.text = (data.index + 1).toString()
	}

	override fun onReady() {
		val ssiv = binding.ssiv
		ssiv.colorFilter = settings.colorFilter?.toColorFilter()

		// Guard against onReady firing with sWidth/sHeight == 0 (image not
		// actually decoded yet, e.g. recycled state). Without this guard the
		// division produces Infinity/NaN and crashes setScaleAndCenter.
		val hasSize = ssiv.sWidth > 0 && ssiv.sHeight > 0 && ssiv.width > 0 && ssiv.height > 0
		if (hasSize) {
			ssiv.maxScale = 2f * maxOf(
				ssiv.width / ssiv.sWidth.toFloat(),
				ssiv.height / ssiv.sHeight.toFloat(),
			)
		}
		when (settings.zoomMode) {
			ZoomMode.FIT_CENTER -> {
				ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				ssiv.resetScaleAndCenter()
			}

			ZoomMode.FIT_HEIGHT -> {
				if (!hasSize) return
				ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				ssiv.minScale = ssiv.height / ssiv.sHeight.toFloat()
				ssiv.setScaleAndCenter(
					ssiv.minScale,
					PointF(0f, ssiv.sHeight / 2f),
				)
			}

			ZoomMode.FIT_WIDTH -> {
				if (!hasSize) return
				ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				ssiv.minScale = ssiv.width / ssiv.sWidth.toFloat()
				ssiv.setScaleAndCenter(
					ssiv.minScale,
					PointF(ssiv.sWidth / 2f, 0f),
				)
			}

			ZoomMode.KEEP_START -> {
				if (!hasSize) return
				ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				ssiv.setScaleAndCenter(
					ssiv.maxScale,
					PointF(0f, 0f),
				)
			}
		}
	}

	override fun onZoomIn() {
		scaleBy(1.2f)
	}

	override fun onZoomOut() {
		scaleBy(0.8f)
	}

	@SuppressLint("RtlHardcoded")
	@RequiresApi(Build.VERSION_CODES.S)
	protected open fun applyRoundedCorners(insets: WindowInsets) {
		binding.textViewNumber.updateLayoutParams<FrameLayout.LayoutParams> {
			val baseMargin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
			val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
			val corner = when {
				absoluteGravity and Gravity.LEFT == Gravity.LEFT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
				}

				absoluteGravity and Gravity.RIGHT == Gravity.RIGHT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
				}

				else -> {
					null
				}
			}
			setMargins(baseMargin + (corner?.radius ?: 0))
		}
	}

	private fun scaleBy(factor: Float) {
		val ssiv = binding.ssiv
		val center = ssiv.getCenter() ?: return
		val newScale = ssiv.scale * factor
		ssiv.animateScaleAndCenter(newScale, center)?.apply {
			withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
			withInterpolator(DecelerateInterpolator())
			start()
		}
	}
}
