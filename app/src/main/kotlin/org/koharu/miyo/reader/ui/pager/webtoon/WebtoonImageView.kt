package org.koharu.miyo.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import androidx.core.view.ancestors
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koharu.miyo.core.util.ext.resolveDp
import kotlin.math.roundToInt

class WebtoonImageView @JvmOverloads constructor(
	context: Context,
	attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr) {

	private val ct = PointF()

	private var scrollPos = 0
	private var debugPaint: Paint? = null

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (isDebugDrawingEnabled) {
			drawDebug(canvas)
		}
	}

	fun scrollBy(delta: Int) {
		if (!isReady || sWidth == 0 || sHeight == 0) {
			return
		}
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			return
		}
		val newScroll = scrollPos + delta
		scrollToInternal(newScroll.coerceIn(0, maxScroll))
	}

	fun scrollTo(y: Int) {
		if (!isReady || sWidth == 0 || sHeight == 0) {
			// Defer: caller will retry once the image is decoded.
			return
		}
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			scrollToInternal(0)
			return
		}
		scrollToInternal(y.coerceIn(0, maxScroll))
	}

	fun getScroll() = scrollPos

	fun getScrollRange(): Int {
		if (!isReady || sWidth == 0 || sHeight == 0 || width == 0) {
			return 0
		}
		val totalHeight = (sHeight * width / sWidth.toFloat()).roundToInt()
		// roundToInt() can saturate to Int.MAX_VALUE for absurdly large
		// sHeight; clamp negatives and the saturation sentinel.
		if (totalHeight <= 0 || totalHeight == Int.MAX_VALUE) {
			return 0
		}
		return (totalHeight - height).coerceAtLeast(0)
	}

	override fun recycle() {
		scrollPos = 0
		super.recycle()
	}

	override fun getSuggestedMinimumHeight(): Int {
		var desiredHeight = super.getSuggestedMinimumHeight()
		if (sHeight == 0) {
			val parentHeight = parentHeight()
			if (desiredHeight < parentHeight) {
				desiredHeight = parentHeight
			}
		}
		return desiredHeight
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
		val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
		val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
		val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
		val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
		val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
		var desiredWidth = parentWidth
		var desiredHeight = parentHeight
		if (sWidth > 0 && sHeight > 0) {
			if (resizeWidth && resizeHeight) {
				desiredWidth = sWidth
				desiredHeight = sHeight
			} else if (resizeHeight) {
				desiredHeight = (sHeight.toDouble() / sWidth.toDouble() * desiredWidth).toInt()
			} else if (resizeWidth) {
				desiredWidth = (sWidth.toDouble() / sHeight.toDouble() * desiredHeight).toInt()
			}
		}
		desiredWidth = desiredWidth.coerceAtLeast(suggestedMinimumWidth)
		desiredHeight = desiredHeight.coerceAtLeast(suggestedMinimumHeight).coerceAtMost(parentHeight())
		setMeasuredDimension(desiredWidth, desiredHeight)
	}

	override fun onDownSamplingChanged() {
		super.onDownSamplingChanged()
		if (isReady) {
			adjustScale()
			onImageEventListener.onReady()
		}
	}

	override fun onReady() {
		super.onReady()
		adjustScale()
		clampScrollToRange()
	}

	override fun onImageLoaded() {
		super.onImageLoaded()
		clampScrollToRange()
	}

	private fun scrollToInternal(pos: Int) {
		// Callers must ensure sWidth/sHeight > 0 before invoking this. We
		// guard again here so any future caller cannot produce NaN/Infinity
		// for minScale and crash setScaleAndCenter.
		if (sWidth == 0 || sHeight == 0 || width == 0 || height == 0) {
			return
		}
		val scale = width / sWidth.toFloat()
		if (!scale.isFinite() || scale <= 0f) {
			return
		}
		minScale = scale
		maxScale = scale
		scrollPos = pos
		ct.set(sWidth / 2f, (height / 2f + pos.toFloat()) / scale)
		setScaleAndCenter(scale, ct)
	}

	private fun clampScrollToRange() {
		if (!isReady) {
			return
		}
		val maxScroll = getScrollRange()
		if (scrollPos > maxScroll) {
			scrollToInternal(maxScroll)
		}
	}

	private fun adjustScale() {
		if (sWidth == 0 || sHeight == 0 || width == 0 || height == 0) {
			return
		}
		val scale = width / sWidth.toFloat()
		if (!scale.isFinite() || scale <= 0f) {
			return
		}
		minScale = scale
		maxScale = scale
		minimumScaleType = SCALE_TYPE_CUSTOM
		requestLayout()
	}

	private fun parentHeight(): Int {
		return ancestors.firstNotNullOfOrNull { it as? RecyclerView }?.height ?: 0
	}

	private fun drawDebug(canvas: Canvas) {
		val paint = debugPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.RED
			strokeWidth = context.resources.resolveDp(2f)
			textAlign = Paint.Align.LEFT
			textSize = context.resources.resolveDp(14f)
			debugPaint = this
		}
		paint.style = Paint.Style.STROKE
		canvas.drawRect(1f, 1f, width.toFloat() - 1f, height.toFloat() - 1f, paint)
		paint.style = Paint.Style.FILL
		canvas.drawText("${getScroll()} / ${getScrollRange()}", 100f, 100f, paint)
	}
}
