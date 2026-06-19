package org.koharu.miyo.reader.ui.pager.doublepage

import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koharu.miyo.core.exceptions.resolve.ExceptionResolver
import org.koharu.miyo.core.os.NetworkState
import org.koharu.miyo.databinding.ItemPageBinding
import org.koharu.miyo.reader.domain.PageLoader
import org.koharu.miyo.reader.ui.config.ReaderSettings
import org.koharu.miyo.reader.ui.pager.ReaderPage
import org.koharu.miyo.reader.ui.pager.standard.PageHolder

class DoublePageHolder(
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

	private val isEven: Boolean
		get() = bindingAdapterPosition and 1 == 0

	init {
		binding.ssiv.panLimit = SubsamplingScaleImageView.PAN_LIMIT_INSIDE
	}

	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		(binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
			.gravity = (if (isEven) Gravity.START else Gravity.END) or Gravity.BOTTOM
	}

	// Note: settings.zoomMode is intentionally not applied in double-page
	// mode. Both pages of a spread must keep an identical scale and stay
	// anchored to the shared inner edge, which is incompatible with the
	// FIT_WIDTH / FIT_HEIGHT / KEEP_START behaviors.
	override fun onReady() {
		val ssiv = binding.ssiv
		ssiv.colorFilter = settings.colorFilter?.toColorFilter()

		// Guard against onReady firing with sWidth/sHeight == 0 (recycled
		// state). Without this guard the divisions produce Infinity/NaN.
		if (ssiv.sWidth <= 0 || ssiv.sHeight <= 0 || ssiv.width <= 0 || ssiv.height <= 0) {
			return
		}
		with(ssiv) {
			maxScale = 2f * maxOf(
				width / sWidth.toFloat(),
				height / sHeight.toFloat(),
			)
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
			setScaleAndCenter(
				minScale,
				PointF(if (isEven) 0f else sWidth.toFloat(), sHeight / 2f),
			)
		}
	}
}
