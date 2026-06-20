package org.koharu.miyo.settings.sources.keiyoushi

import org.koharu.miyo.core.parser.tachiyomi.KeiyoushiExtension
import org.koharu.miyo.list.ui.model.ListModel

sealed interface KeiyoushiExtensionItem : ListModel {

	data class Available(
		val extension: KeiyoushiExtension,
		val isInstalled: Boolean,
		val hasUpdate: Boolean,
		val isInstalling: Boolean = false,
	) : KeiyoushiExtensionItem {

		override fun areItemsTheSame(other: ListModel): Boolean =
			other is Available && extension.pkg == other.extension.pkg

		override fun areContentsTheSame(other: ListModel): Boolean =
			other is Available && extension == other.extension &&
				isInstalled == other.isInstalled &&
				hasUpdate == other.hasUpdate &&
				isInstalling == other.isInstalling
	}

	data class RepoHeader(
		val url: String,
		val extensionCount: Int,
	) : KeiyoushiExtensionItem {

		override fun areItemsTheSame(other: ListModel): Boolean =
			other is RepoHeader && url == other.url
	}

	data class Hint(
		val title: String,
		val subtitle: String? = null,
	) : KeiyoushiExtensionItem {

		override fun areItemsTheSame(other: ListModel): Boolean =
			other is Hint && title == other.title
	}
}
