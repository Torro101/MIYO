package org.koharu.miyo.reader.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koharu.miyo.R
import org.koharu.miyo.core.nav.AppRouter

class ReaderMenuProvider(
	private val router: AppRouter,
	private val viewModel: ReaderViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_reader, menu)
		menu.findItem(R.id.action_info)?.isVisible = true
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_info -> {
				viewModel.getMangaOrNull()?.let { manga ->
					router.openDetails(manga)
					true
				} ?: false
			}

			else -> false
		}
	}
}
