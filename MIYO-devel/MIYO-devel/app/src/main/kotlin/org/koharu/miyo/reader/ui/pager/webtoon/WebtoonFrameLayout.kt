package org.koharu.miyo.reader.ui.pager.webtoon

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import org.koharu.miyo.R

class WebtoonFrameLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

	private var _target: WebtoonImageView? = null

	// Nullable: findViewById may return null while the holder is being
	// created or recycled. Callers must handle the null case instead of
	// crashing the scroll dispatch with an NPE.
	val target: WebtoonImageView?
		get() {
			val cached = _target
			// Stale-cache defense: onDetachedFromWindow below clears _target on
			// recycle, but verify parent + attachment in case this frame is
			// re-bound to a different WebtoonImageView before detach.
			if (cached != null && cached.parent === this && cached.isAttachedToWindow) {
				return cached
			}
			val resolved = findViewById<WebtoonImageView?>(R.id.ssiv)
			_target = resolved
			return resolved
		}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		// Drop the cached target when this frame is recycled by RecyclerView,
		// otherwise the next bind may dispatch scroll to a stale view.
		_target = null
	}

	fun dispatchVerticalScroll(dy: Int): Int {
		if (dy == 0) {
			return 0
		}
		val ssiv = target ?: return 0
		val oldScroll = ssiv.getScroll()
		ssiv.scrollBy(dy)
		return ssiv.getScroll() - oldScroll
	}
}
