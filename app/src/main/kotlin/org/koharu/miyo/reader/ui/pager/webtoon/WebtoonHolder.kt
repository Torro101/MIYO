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

        // Nullable so that a legitimately saved scroll of 0 (top of the page) is
        // not confused with "no saved scroll" and incorrectly restored to the
        // bottom when itemView.top < 0.
        private var scrollToRestore: Int? = null

        init {
                bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
        }

        override fun onReady() {
                binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
                with(binding.ssiv) {
                        // Priority order for scroll restoration:
                        // 1. Explicitly saved scroll (from chapter restore / state save)
                        // 2. Scroll preserved across recycle/reload (from onTrimMemory)
                        // 3. If the page is partially scrolled off the top of the viewport
                        //    (itemView.top < 0), start at the bottom (getScrollRange()) to
                        //    prevent the nested scroll dispatcher from freezing webtoon scroll.
                        // 4. Otherwise start at the top (0).
                        val savedScroll = scrollToRestore
                        val recycledScroll = restoreScrollAfterRecycle()
                        scrollTo(
                                when {
                                        savedScroll != null -> savedScroll
                                        recycledScroll != null -> recycledScroll
                                        itemView.top < 0 -> getScrollRange()
                                        else -> 0
                                },
                        )
                        scrollToRestore = null
                }
                requestParentScrollSync()
        }

        override fun onImageLoaded() {
                super.onImageLoaded()
                requestParentScrollSync()
        }

        override fun onRecycled() {
                // Drop any pending scroll restoration so it cannot be applied to a
                // different page when this holder is reused after recycling.
                scrollToRestore = null
                super.onRecycled()
        }

        private fun requestParentScrollSync() {
                var parent = itemView.parent
                while (parent != null) {
                        val recycler = parent as? WebtoonRecyclerView
                        if (recycler != null) {
                                recycler.post { recycler.updateChildrenScroll() }
                                break
                        }
                        parent = parent.parent
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
