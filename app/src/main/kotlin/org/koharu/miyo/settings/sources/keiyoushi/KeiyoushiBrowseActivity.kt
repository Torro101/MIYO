package org.koharu.miyo.settings.sources.keiyoushi

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koharu.miyo.R
import org.koharu.miyo.core.ui.BaseActivity
import org.koharu.miyo.core.parser.tachiyomi.KeiyoushiRepositoryManager
import org.koharu.miyo.core.ui.dialog.buildAlertDialog
import org.koharu.miyo.core.ui.dialog.setEditText
import org.koharu.miyo.core.ui.util.FadingAppbarMediator
import org.koharu.miyo.core.util.ext.observe
import org.koharu.miyo.databinding.ActivityKeiyoushiBrowseBinding
import org.koharu.miyo.list.ui.adapter.TypedListSpacingDecoration
import org.koharu.miyo.main.ui.owners.AppBarOwner

@AndroidEntryPoint
class KeiyoushiBrowseActivity : BaseActivity<ActivityKeiyoushiBrowseBinding>(),
	AppBarOwner,
	MenuProvider,
	MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<KeiyoushiBrowseViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityKeiyoushiBrowseBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

		val adapter = KeiyoushiBrowseAdapter(
			onInstallClick = { item -> viewModel.installExtension(item.extension) },
			onUninstallClick = { item -> confirmUninstall(item) },
		)

		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			layoutManager = LinearLayoutManager(context)
			this.adapter = adapter
		}

		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()

		viewModel.content.observe(this, adapter)

		viewBinding.swipeRefreshLayout.setOnRefreshListener {
			viewModel.loadExtensions()
			viewBinding.swipeRefreshLayout.isRefreshing = false
		}

		addMenuProvider(this)
	}

	override fun onCreateMenu(menu: Menu, menuInflater: android.view.MenuInflater) {
		menuInflater.inflate(R.menu.menu_keiyoushi_browse, menu)
		val searchMenuItem = menu.findItem(R.id.action_search)
		searchMenuItem.setOnActionExpandListener(this)
		val searchView = searchMenuItem.actionView as SearchView
		searchView.setOnQueryTextListener(this)
		searchView.setIconifiedByDefault(false)
		searchView.queryHint = getString(R.string.search)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_add_repo -> {
				showAddRepoDialog()
				true
			}
			R.id.action_manage_repos -> {
				showManageReposDialog()
				true
			}
			else -> false
		}
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		appBar.setExpanded(true, true)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		(item.actionView as SearchView).setQuery("", false)
		viewModel.setSearchQuery(null)
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.setSearchQuery(newText?.trim())
		return true
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	fun showAddRepoDialog() {
		var input: android.widget.EditText? = null
		val dialog = buildAlertDialog(this) {
			setTitle(R.string.keiyoushi_add_repo)
			setMessage(getString(R.string.keiyoushi_add_repo_summary))
			input = setEditText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, singleLine = true)
			input?.hint = KeiyoushiRepositoryManager.DEFAULT_INDEX_URL
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(android.R.string.ok) { _, _ ->
				val url = input?.text?.toString()?.trim() ?: return@setPositiveButton
				if (!viewModel.addRepoUrl(url)) {
					Toast.makeText(
						this@KeiyoushiBrowseActivity,
						getString(R.string.keiyoushi_invalid_repo_url),
						Toast.LENGTH_SHORT,
					).show()
				}
			}
		}
		dialog.show()
	}

	fun showManageReposDialog() {
		val repos = viewModel.repoUrls
		val items = repos.map { url ->
			url.removeSuffix("/index.min.json").removeSuffix("/index.json").removeSuffix("/")
		}.toTypedArray()

		val dialog = buildAlertDialog(this) {
			setTitle(R.string.keiyoushi_manage_repos)
			setItems(items) { _, _ -> }
			setNegativeButton(android.R.string.cancel, null)
			setNeutralButton(R.string.keiyoushi_add_repo) { _, _ ->
				showAddRepoDialog()
			}
		}
		dialog.show()
	}

	private fun confirmUninstall(item: KeiyoushiExtensionItem.Available) {
		buildAlertDialog(this) {
			setTitle(R.string.keiyoushi_uninstall)
			setMessage(getString(R.string.keiyoushi_confirm_uninstall, item.extension.displayName))
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.delete) { _, _ ->
				viewModel.uninstallExtension(item.extension)
			}
		}.show()
	}
}
