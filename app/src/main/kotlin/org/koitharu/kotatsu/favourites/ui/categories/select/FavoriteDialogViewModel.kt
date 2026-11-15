package org.koitharu.kotatsu.favourites.ui.categories.select

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.ids
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject

data class FavoriteSheetUiState(
	val isLoading: Boolean = true,
	val isAlreadyInLibrary: Boolean = false,
	val customCategories: List<MangaCategoryItem> = emptyList(),
	val isSaving: Boolean = false,
) {
	val checkedCustomCount: Int
		get() = customCategories.count { it.checkedState != MaterialCheckBox.STATE_UNCHECKED }
}

@HiltViewModel
class FavoriteDialogViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val favouritesRepository: FavouritesRepository,
	settings: AppSettings,
) : BaseViewModel() {

	val manga = savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
		it.manga
	}

	private val stagedOverrides = MutableStateFlow<Map<Long, Int>>(emptyMap())
	private val isSavingFlow = MutableStateFlow(false)
	private val _isAlreadyInLibrary = MutableStateFlow(false)
	val isAlreadyInLibrary: StateFlow<Boolean> = _isAlreadyInLibrary.asStateFlow()

	val onDismissSheet = MutableEventFlow<Unit>()

	init {
		launchJob(Dispatchers.Default) {
			_isAlreadyInLibrary.value = manga.any { favouritesRepository.isFavorite(it.id) }
		}
	}

	val uiState: StateFlow<FavoriteSheetUiState> = combine(
		favouritesRepository.observeCategories(),
		stagedOverrides,
		_isAlreadyInLibrary,
		isSavingFlow,
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
	) { categories, overrides, inLib, saving, tracker ->
		val customItems = buildCustomCategoryItems(categories, overrides, tracker)
		FavoriteSheetUiState(
			isLoading = false,
			isAlreadyInLibrary = inLib,
			customCategories = customItems,
			isSaving = saving,
		)
	}.withErrorHandling()
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			FavoriteSheetUiState(isLoading = true),
		)

	fun toggleCategory(categoryId: Long, currentState: Int) {
		val nextState = if (currentState == MaterialCheckBox.STATE_CHECKED) {
			MaterialCheckBox.STATE_UNCHECKED
		} else {
			MaterialCheckBox.STATE_CHECKED
		}
		stagedOverrides.update { prev -> prev + (categoryId to nextState) }
	}

	fun createNewCategory(rawTitle: String) {
		val cleanTitle = rawTitle.trim()
		if (cleanTitle.isEmpty()) return
		launchJob(Dispatchers.Default) {
			if (favouritesRepository.isDefaultCategoryTitle(cleanTitle)) {
				favouritesRepository.getOrCreateDefaultCategory("Default")
				return@launchJob
			}
			val created = favouritesRepository.createCategory(
				title = cleanTitle,
				sortOrder = ListSortOrder.NEWEST,
				isTrackerEnabled = true,
				isNewChaptersDownloadEnabled = false,
				isVisibleOnShelf = true,
			)
			stagedOverrides.update { prev -> prev + (created.id to MaterialCheckBox.STATE_CHECKED) }
		}
	}

	fun confirmSelection() {
		if (isSavingFlow.value) return
		isSavingFlow.value = true
		launchJob(Dispatchers.Default) {
			try {
				val state = uiState.value
				val allCategories = favouritesRepository.observeCategories()
				val defaultCategory = favouritesRepository.getOrCreateDefaultCategory("Default")
				val mangaIds = manga.ids()

				if (state.checkedCustomCount == 0) {
					// Keep in Default category and clear custom assignments
					favouritesRepository.addToCategory(defaultCategory.id, manga)
					for (item in state.customCategories) {
						favouritesRepository.removeFromCategory(item.category.id, mangaIds)
					}
				} else {
					// Apply checked custom categories and remove from Default shelf
					for (item in state.customCategories) {
						when (item.checkedState) {
							MaterialCheckBox.STATE_CHECKED -> {
								favouritesRepository.addToCategory(item.category.id, manga)
							}
							MaterialCheckBox.STATE_UNCHECKED -> {
								favouritesRepository.removeFromCategory(item.category.id, mangaIds)
							}
						}
					}
					favouritesRepository.removeFromCategory(defaultCategory.id, mangaIds)
				}
				onDismissSheet.call(Unit)
			} finally {
				isSavingFlow.value = false
			}
		}
	}

	fun removeFromLibrary() {
		if (isSavingFlow.value) return
		isSavingFlow.value = true
		launchJob(Dispatchers.Default) {
			try {
				favouritesRepository.removeFromFavourites(manga.ids())
				onDismissSheet.call(Unit)
			} finally {
				isSavingFlow.value = false
			}
		}
	}

	private suspend fun buildCustomCategoryItems(
		categories: List<FavouriteCategory>,
		overrides: Map<Long, Int>,
		tracker: Boolean,
	): List<MangaCategoryItem> {
		val customCats = categories.filterNot { favouritesRepository.isDefaultCategoryTitle(it.title) }
		if (customCats.isEmpty()) {
			return emptyList()
		}
		val cats = MutableLongObjectMap<MutableLongSet>(customCats.size)
		customCats.forEach { cats[it.id] = MutableLongSet(manga.size) }
		for (m in manga) {
			val ids = favouritesRepository.getCategoriesIds(m.id)
			ids.forEach { id -> cats[id]?.add(m.id) }
		}
		return customCats.map { cat ->
			val dbState = when (cats[cat.id]?.size ?: 0) {
				0 -> MaterialCheckBox.STATE_UNCHECKED
				manga.size -> MaterialCheckBox.STATE_CHECKED
				else -> MaterialCheckBox.STATE_INDETERMINATE
			}
			MangaCategoryItem(
				category = cat,
				checkedState = overrides[cat.id] ?: dbState,
				isTrackerEnabled = tracker,
			)
		}
	}
}
