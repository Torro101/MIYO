package org.koharu.miyo.reader.ui.pager.reversed

import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koharu.miyo.core.exceptions.resolve.ExceptionResolver
import org.koharu.miyo.core.model.ZoomMode
import org.koharu.miyo.core.os.NetworkState
import org.koharu.miyo.databinding.ItemPageBinding
import org.koharu.miyo.reader.domain.PageLoader
import org.koharu.miyo.reader.ui.config.ReaderSettings
import org.koharu.miyo.reader.ui.pager.standard.PageHolder

class ReversedPageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : PageHolder(
	owner = owner,
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
) {

	init {
		(binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
			.gravity = Gravity.START or Gravity.BOTTOM
	}

	override fun onReady() {
		val ssiv = binding.ssiv
		ssiv.colorFilter = settings.colorFilter?.toColorFilter()

		// Guard against onReady firing with sWidth/sHeight == 0 (recycled
		// state). Without this guard the divisions produce Infinity/NaN.
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
					PointF(ssiv.sWidth.toFloat(), ssiv.sHeight / 2f),
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
					PointF(ssiv.sWidth.toFloat(), 0f),
				)
			}
		}
	}
}
