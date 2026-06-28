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

        /**
         * Track the last view-width-to-source-width ratio we applied.
         * Used to detect when a re-apply is needed (e.g. after SSIV internally
         * resets scale during downsampling changes) while avoiding redundant work.
         */
        private var lastAppliedScale = 0f

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
                lastAppliedScale = 0f
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

        /**
         * When downsampling changes, SSIV fires this callback. The critical
         * insight is that sWidth/sHeight may NOT yet reflect the new downsampled
         * dimensions at the time this fires — they can be stale.
         *
         * If we set scale = viewWidth / staleSWidth here, we'll compute the
         * WRONG scale (too large if going from downsampled→full-res, too small
         * if going from full-res→downsampled). This was the root cause of both
         * the "page shrinks" and "page expands" bugs.
         *
         * The correct approach: do NOT set scale here. Instead, just save the
         * current scroll position. SSIV will fire onReady() after the re-decode
         * completes, at which point sWidth/sHeight are correct and adjustScale()
         * will compute the right scale.
         *
         * However, we must prevent SSIV from internally resetting minScale/
         * maxScale to wrong values during the re-decode. We do this by
         * re-applying our SCALE_TYPE_CUSTOM + minScale/maxScale using the
         * CURRENT (still-valid) dimensions, without calling setScaleAndCenter()
         * (which would apply a wrong display scale with stale sWidth).
         */
        override fun onDownSamplingChanged() {
                super.onDownSamplingChanged()
                if (isReady && sWidth > 0 && width > 0) {
                        // Preserve the scale constraints so SSIV doesn't reset them
                        // during the re-decode. Use the CURRENT sWidth which is still
                        // valid for the current display state.
                        val scale = width / sWidth.toFloat()
                        if (scale.isFinite() && scale > 0f) {
                                minScale = scale
                                maxScale = scale
                                minimumScaleType = SCALE_TYPE_CUSTOM
                        }
                        // Do NOT call setScaleAndCenter() here — the current scale is
                        // still correct for the current source dimensions. The re-decode
                        // will trigger onReady() where we'll recalculate with the new
                        // dimensions.
                        clampScrollToRange()
                }
        }

        override fun onReady() {
                super.onReady()
                applyFillWidthScale(requestLayout = true)
                clampScrollToRange()
        }

        override fun onImageLoaded() {
                super.onImageLoaded()
                // After image dimensions are known, re-apply scale if needed.
                // This handles the case where onReady() was called with incomplete
                // state and the scale wasn't applied correctly.
                if (isReady && sWidth > 0 && width > 0) {
                        val targetScale = width / sWidth.toFloat()
                        if (targetScale.isFinite() && targetScale > 0f && lastAppliedScale != targetScale) {
                                applyFillWidthScale(requestLayout = false)
                        }
                }
                clampScrollToRange()
        }

        /**
         * Core scale application method. Sets the fill-width scale, applies it
         * via setScaleAndCenter(), and optionally triggers requestLayout().
         *
         * This is the SINGLE place where scale is set. Both onReady() and
         * onDownSamplingChanged() (indirectly) use this method.
         *
         * @param requestLayout Whether to trigger a layout pass after setting
         *   the scale. True on initial load (view height needs updating), false
         *   on downsampling changes (height stays the same because aspect ratio
         *   is preserved).
         */
        private fun applyFillWidthScale(requestLayout: Boolean = true) {
                if (sWidth == 0 || sHeight == 0 || width == 0 || height == 0) {
                        return
                }
                val scale = width / sWidth.toFloat()
                if (!scale.isFinite() || scale <= 0f) {
                        return
                }

                // Set scale constraints
                minScale = scale
                maxScale = scale
                minimumScaleType = SCALE_TYPE_CUSTOM

                // Apply the scale immediately. This is critical — without this call,
                // SSIV's internally-set scale (from super.onReady() or its own
                // recalculation) persists, which can be wrong and cause the image to
                // appear expanded or contracted for visible frames until a layout pass
                // corrects it.
                val maxScroll = getScrollRange()
                // Only clamp scrollPos if maxScroll > 0. When maxScroll == 0 (page
                // shorter than viewport), clamping to 0 is a no-op but is semantically
                // correct. However, if the image was re-decoded with different
                // dimensions (downsampling change), the old scrollPos may be stale
                // and larger than the new maxScroll — clamping it here prevents
                // setScaleAndCenter from centering outside the source image.
                scrollPos = if (maxScroll > 0) scrollPos.coerceIn(0, maxScroll) else 0
                ct.set(sWidth / 2f, (height / 2f + scrollPos.toFloat()) / scale)
                // Guard against NaN/Infinity center — a corrupt sHeight could produce
                // a bogus scale that slips through the checks above.
                if (ct.y.isNaN() || ct.y.isInfinite()) {
                        ct.set(sWidth / 2f, sHeight / 2f)
                }
                setScaleAndCenter(scale, ct)

                lastAppliedScale = scale

                if (requestLayout) {
                        // Defer requestLayout to avoid layout thrashing during SSIV's
                        // internal re-initialization. The scale has already been applied
                        // above, so the display is correct immediately; the layout pass
                        // only updates the view's measured height to match the new aspect
                        // ratio.
                        post { requestLayout() }
                }
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
                // Clamp pos to the valid scroll range so a stale or out-of-bounds
                // saved scroll cannot push the center Y outside the source image,
                // which would make setScaleAndCenter() throw or render the page
                // off-screen.
                val maxScroll = getScrollRange()
                val clampedPos = pos.coerceIn(0, maxScroll)
                minScale = scale
                maxScale = scale
                minimumScaleType = SCALE_TYPE_CUSTOM
                scrollPos = clampedPos
                ct.set(sWidth / 2f, (height / 2f + clampedPos.toFloat()) / scale)
                // Guard the center Y against NaN/Infinity one more time before
                // passing it to SSIV — a corrupt sHeight could produce a bogus
                // scale that slips through the checks above.
                if (ct.y.isNaN() || ct.y.isInfinite()) {
                        ct.set(sWidth / 2f, sHeight / 2f)
                }
                setScaleAndCenter(scale, ct)
                lastAppliedScale = scale
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
