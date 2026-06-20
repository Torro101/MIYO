package org.koharu.miyo.settings.sources.catalog

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koharu.miyo.R
import org.koharu.miyo.core.model.getSummary
import org.koharu.miyo.core.model.getTitle
import org.koharu.miyo.core.ui.image.FaviconDrawable
import org.koharu.miyo.core.ui.list.OnListItemClickListener
import org.koharu.miyo.core.util.ext.drawableStart
import org.koharu.miyo.core.util.ext.getThemeDimensionPixelOffset
import org.koharu.miyo.core.util.ext.setTextAndVisible
import org.koharu.miyo.databinding.ItemEmptyHintBinding
import org.koharu.miyo.databinding.ItemSourceCatalogBinding
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koharu.miyo.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

fun sourceCatalogItemSourceAD(
	listener: OnListItemClickListener<SourceCatalogItem.Source>,
	onHideToggle: ((MangaSource, Boolean) -> Unit)? = null,
) = adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemLongClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}

	// Helper to update the visibility eye icon
	fun updateVisibilityIcon(hidden: Boolean) {
		binding.imageViewVisibility.setImageResource(
			if (hidden) R.drawable.ic_eye_off else R.drawable.ic_eye
		)
		binding.imageViewVisibility.contentDescription = context.getString(
			if (hidden) R.string.show else R.string.hide_from_main_screen
		)
		binding.imageViewVisibility.setTooltipCompat(context.getString(
			if (hidden) R.string.show else R.string.hide_from_main_screen
		))
		// Dim the item when hidden
		binding.root.alpha = if (hidden) 0.5f else 1.0f
	}

	// Long press: toggle hidden state via eye icon
	var isHidden = false
	binding.imageViewVisibility.setOnClickListener {
		isHidden = !isHidden
		updateVisibilityIcon(isHidden)
		onHideToggle?.invoke(item.source, isHidden)
	}
	binding.root.setOnLongClickListener {
		isHidden = !isHidden
		updateVisibilityIcon(isHidden)
		onHideToggle?.invoke(item.source, isHidden)
		true
	}

	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = item.source.getSummary(context)
		binding.textViewDescription.drawableStart = if (item.source.isBroken) {
			ContextCompat.getDrawable(context, R.drawable.ic_off_small)
		} else {
			null
		}
		FaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyHintBinding>(
	{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}
