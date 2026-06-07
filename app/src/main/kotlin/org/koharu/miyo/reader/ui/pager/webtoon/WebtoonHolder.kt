package org.koharu.miyo.reader.ui.pager.webtoon

import android.view.View
import androidx.lifecycle.LifecycleOwner
import org.koharu.miyo.core.exceptions.resolve.ExceptionResolver
import org.koharu.miyo.core.os.NetworkState
import org.koharu.miyo.databinding.ItemPageWebtoonBinding
import org.koharu.miyo.reader.domain.PageLoader
import org.koharu.miyo.reader.ui.config.ReaderSettings
import org.koharu.miyo.reader.ui.pager.BasePageHolder

class WebtoonHolder(
	owner: LifecycleOwner,
	binding: ItemPageWebtoonBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageWebtoonBinding>(
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
) {

	override val ssiv = binding.ssiv

	private var scrollToRestore = 0

	init {
		bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
	}

	override fun onReady() {
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		with(binding.ssiv) {
			// Always prefer the saved scroll position when it is non-zero;
			// itemView.top is usually 0 for a freshly bound holder at the top
			// of the RecyclerView, so the old `else -> 0` branch overwrote
			// the saved scroll for nearly every page. scrollTo() itself
			// short-circuits while !isReady, so we don't need to gate here.
			scrollTo(if (scrollToRestore != 0) scrollToRestore else 0)
			scrollToRestore = 0
		}
	}

	fun getScrollY() = binding.ssiv.getScroll()

	fun restoreScroll(scroll: Int) {
		if (binding.ssiv.isReady) {
			binding.ssiv.scrollTo(scroll)
		} else {
			scrollToRestore = scroll
		}
	}
}
