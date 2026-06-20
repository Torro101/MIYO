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
                // Multiply in Double: sHeight * width is evaluated in Int and can
                // overflow for very tall strips before the division brings the value
                // back into range.
                val totalHeight = (sHeight.toDouble() * width / sWidth.toDouble()).roundToInt()
                // roundToInt() can saturate to Int.MAX_VALUE for absurdly large
                // sHeight; clamp negatives and the saturation sentinel.
                if (totalHeight <= 0 || totalHeight == Int.MAX_VALUE) {
                        return 0
                }
                return (totalHeight - height).coerceAtLeast(0)
        }

        // Saved scroll position preserved across recycle/reload cycles.
        // When SSIV recycles (e.g. from onTrimMemory), scrollPos is saved here
        // so that restoreScrollAfterRecycle() can re-apply it when the image
        // is re-loaded, preventing the page from jumping to the top.
        private var savedScrollOnRecycle: Int? = null

        override fun recycle() {
                savedScrollOnRecycle = scrollPos
                scrollPos = 0
                super.recycle()
        }

        /**
         * After a recycle + reload cycle, restore the scroll position that was
         * saved before recycling. Called from WebtoonHolder.onReady() instead of
         * resetting to 0 or getScrollRange(), giving a seamless experience after
         * memory trim or config changes.
         */
        fun restoreScrollAfterRecycle(): Int? {
                val saved = savedScrollOnRecycle
                savedScrollOnRecycle = null
                return saved
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
                // A down-sampling change only affects tile resolution, not the image
                // geometry. Re-applying scale/center (adjustScale) and re-firing
                // onReady() here re-runs WebtoonHolder.onReady -> scrollTo(...) with
                // possibly stale/transient sWidth/sHeight/width captured mid-relayout,
                // which makes the visible page jump to a wrong (enlarged) scale and can
                // reset the user's scroll position.
                //
                // However, SSIV may internally recalculate minScale/maxScale when
                // downsampling changes, which can leave the scale wrong (causing the
                // page to appear contracted/shrunk). We must restore the correct scale
                // values WITHOUT triggering requestLayout() or re-firing onReady(),
                // both of which cause the enlargement/jump bug.
                if (isReady) {
                        restoreScaleWithoutLayout()
                        clampScrollToRange()
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
                // Defer requestLayout to avoid layout thrashing during SSIV's
                // internal re-initialization. Posting allows the current layout
                // pass to complete before we trigger a new one.
                post { requestLayout() }
        }

        /**
         * Restore the correct minScale/maxScale/minimumScaleType after a
         * downsampling change without triggering requestLayout() or re-firing
         * onReady(). SSIV may reset these values internally when downsampling
         * changes, which causes the page to appear contracted. This method
         * re-applies the fill-width scale and re-centers at the current scroll
         * position, then calls setScaleAndCenter() which only invalidates the
         * display (no layout pass).
         */
        private fun restoreScaleWithoutLayout() {
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
                // Re-apply the current scroll position at the correct scale.
                // setScaleAndCenter() triggers an invalidate (redraw) but NOT a
                // requestLayout(), so the view dimensions remain stable.
                val maxScroll = getScrollRange()
                scrollPos = scrollPos.coerceIn(0, maxScroll)
                ct.set(sWidth / 2f, (height / 2f + scrollPos.toFloat()) / scale)
                setScaleAndCenter(scale, ct)
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
