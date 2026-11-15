package org.koitharu.kotatsu.favourites.ui.container

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.list.ui.config.ListConfigSection

class FavouritesContainerMenuProvider(
	private val router: AppRouter,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_favourites_container, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		// Ensure Display options appears once inside [⋮] even when 0 categories are active,
		// without ever duplicating MangaListMenuProvider's item when a category page is active.
		if (menu.findItem(R.id.action_list_mode) == null) {
			menu.add(Menu.NONE, R.id.action_list_mode, 40, R.string.list_options)
				.setIcon(R.drawable.ic_list_detailed)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_manage -> {
				router.openFavoriteCategories()
			}

			R.id.action_list_mode -> {
				router.showListConfigSheet(ListConfigSection.General)
			}

			else -> return false
		}
		return true
	}
}
