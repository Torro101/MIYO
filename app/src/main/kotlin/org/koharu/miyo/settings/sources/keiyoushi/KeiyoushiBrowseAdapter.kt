package org.koharu.miyo.settings.sources.keiyoushi

import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koharu.miyo.R
import org.koharu.miyo.core.ui.BaseListAdapter
import org.koharu.miyo.databinding.ItemEmptyHintBinding
import org.koharu.miyo.databinding.ItemKeiyoushiExtensionBinding
import org.koharu.miyo.list.ui.adapter.ListItemType
import org.koharu.miyo.list.ui.model.ListModel

class KeiyoushiBrowseAdapter(
	private val onInstallClick: (KeiyoushiExtensionItem.Available) -> Unit,
	private val onUninstallClick: (KeiyoushiExtensionItem.Available) -> Unit,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.CHAPTER_LIST, extensionItemDelegate(onInstallClick, onUninstallClick))
		addDelegate(ListItemType.HINT_EMPTY, extensionHintDelegate())
	}
}

fun extensionItemDelegate(
	onInstallClick: (KeiyoushiExtensionItem.Available) -> Unit,
	onUninstallClick: (KeiyoushiExtensionItem.Available) -> Unit,
) = adapterDelegateViewBinding<KeiyoushiExtensionItem.Available, ListModel, ItemKeiyoushiExtensionBinding>(
	{ layoutInflater, parent -> ItemKeiyoushiExtensionBinding.inflate(layoutInflater, parent, false) },
) {
	bind {
		val ext = item.extension
		binding.textViewName.text = ext.displayName
		binding.textViewLang.text = ext.lang.uppercase()
		binding.textViewVersion.text = ext.version

		// NSFW badge
		binding.textViewNsfw.isVisible = ext.isNsfw
		binding.textViewNsfw.setText(R.string.keiyoushi_nsfw)

		// Source count
		val sourceCount = ext.sources.size
		binding.textViewSources.text = context.getString(R.string.keiyoushi_source_count, sourceCount)

		// Install button state
		when {
			item.isInstalling -> {
				binding.buttonAction.setText(R.string.keiyoushi_installing)
				binding.buttonAction.isEnabled = false
				binding.buttonAction.setOnClickListener(null)
			}
			item.isInstalled && !item.hasUpdate -> {
				binding.buttonAction.setText(R.string.keiyoushi_uninstall)
				binding.buttonAction.isEnabled = true
				binding.buttonAction.setOnClickListener { onUninstallClick(item) }
			}
			item.hasUpdate -> {
				binding.buttonAction.setText(R.string.keiyoushi_update_available)
				binding.buttonAction.isEnabled = true
				binding.buttonAction.setOnClickListener { onInstallClick(item) }
			}
			else -> {
				binding.buttonAction.setText(R.string.keiyoushi_install)
				binding.buttonAction.isEnabled = true
				binding.buttonAction.setOnClickListener { onInstallClick(item) }
			}
		}

		// Installed indicator
		binding.imageViewInstalled.isVisible = item.isInstalled && !item.hasUpdate
	}
}

fun extensionHintDelegate() = adapterDelegateViewBinding<KeiyoushiExtensionItem.Hint, ListModel, ItemEmptyHintBinding>(
	{ layoutInflater, parent -> ItemEmptyHintBinding.inflate(layoutInflater, parent, false) },
) {
	binding.icon.setImageResource(R.drawable.ic_empty_feed)

	bind {
		binding.textPrimary.text = item.title
		val subtitle = item.subtitle
		if (subtitle != null) {
			binding.textSecondary.text = subtitle
			binding.textSecondary.isVisible = true
		} else {
			binding.textSecondary.isGone = true
		}
	}
}
