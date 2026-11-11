package org.koitharu.kotatsu.details.ui.merge

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.TitleNormalizer
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.details.data.MergeSourcesRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class MergeSourcesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	@ApplicationContext private val appContext: Context,
	private val mergeSourcesRepository: MergeSourcesRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mihonExtensionManager: MihonExtensionManager,
	private val settings: AppSettings,
) : BaseViewModel() {

	val primaryManga: Manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	val mergedSources: StateFlow<List<MergedMangaEntity>> = mergeSourcesRepository
		.observeMergedSources(primaryManga.id)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val availableSources: StateFlow<List<MangaSource>> = sourcesRepository.observeEnabledSources()
		.map { infos ->
			val isNovel = primaryManga.source.isNovelSource
			infos.map { it.mangaSource }
				.filter { src ->
					src.isNovelSource == isNovel &&
						src.name != primaryManga.source.name &&
						(!settings.isNsfwContentDisabled || !src.isNsfw())
				}
		}
		.stateIn(
			scope = viewModelScope + Dispatchers.Default,
			started = SharingStarted.Eagerly,
			initialValue = computeCompatibleSources(),
		)

	private val _selectedSourceName = MutableStateFlow<String?>(null)
	val selectedSourceName: StateFlow<String?> = _selectedSourceName.asStateFlow()

	private val _searchQuery = MutableStateFlow(primaryManga.title)
	val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

	private val _searchResults = MutableStateFlow<List<Manga>>(emptyList())
	val searchResults: StateFlow<List<Manga>> = _searchResults.asStateFlow()

	private val _isSearching = MutableStateFlow(false)
	val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

	val onFeedbackMessage = MutableEventFlow<String>()

	private var searchJob: Job? = null

	init {
		submitSearch(primaryManga.title)
	}

	fun updateQuery(query: String) {
		_searchQuery.value = query
	}

	fun selectSourceFilter(sourceName: String?) {
		if (_selectedSourceName.value == sourceName) return
		_selectedSourceName.value = sourceName
		submitSearch(_searchQuery.value)
	}

	fun submitSearch(rawQuery: String = _searchQuery.value) {
		val query = rawQuery.trim()
		_searchQuery.value = rawQuery
		searchJob?.cancel()
		if (query.isBlank()) {
			_searchResults.value = emptyList()
			_isSearching.value = false
			return
		}
		searchJob = launchJob(Dispatchers.Default) {
			_isSearching.value = true
			try {
				val selected = _selectedSourceName.value
				val allCompatible = availableSources.value.ifEmpty { computeCompatibleSources() }
				val targetSources = if (selected != null) {
					allCompatible.filter { it.name == selected }
				} else {
					allCompatible.take(MAX_MULTI_SEARCH_SOURCES)
				}
				if (targetSources.isEmpty()) {
					_searchResults.value = emptyList()
					return@launchJob
				}
				val semaphore = Semaphore(MAX_CONCURRENT_SEARCHES)
				val candidates = supervisorScope {
					targetSources.map { source ->
						async {
							runCatchingCancellable {
								semaphore.withPermit {
									withTimeout(SEARCH_SOURCE_TIMEOUT_MS) {
										searchSingleSource(source, query)
									}
								}
							}.getOrDefault(emptyList())
						}
					}.awaitAll().flatten()
				}
				val filtered = candidates
					.filter { it.id != primaryManga.id && !(it.source.name == primaryManga.source.name && it.url == primaryManga.url) }
					.distinctBy { "${it.source.name}|${it.url}" }
					.sortedByDescending { candidate ->
						maxOf(
							TitleNormalizer.similarity(query, candidate.title),
							TitleNormalizer.similarity(primaryManga.title, candidate.title),
						)
					}
				_searchResults.value = filtered
			} finally {
				_isSearching.value = false
			}
		}
	}

	fun linkCandidate(candidate: Manga) {
		launchJob(Dispatchers.Default) {
			mergeSourcesRepository.linkSource(primaryManga = primaryManga, secondaryManga = candidate)
			val sourceTitle = candidate.source.getTitle(appContext)
			onFeedbackMessage.call(appContext.getString(R.string.merge_source_linked_toast, sourceTitle))
		}
	}

	fun unlinkSource(entity: MergedMangaEntity) {
		launchJob(Dispatchers.Default) {
			mergeSourcesRepository.unlinkSource(entity.id)
			onFeedbackMessage.call(appContext.getString(R.string.merge_source_unlinked_toast))
		}
	}

	fun toggleEnabled(entity: MergedMangaEntity, enabled: Boolean) {
		launchJob(Dispatchers.Default) {
			mergeSourcesRepository.setEnabled(entity.id, enabled)
		}
	}

	fun movePriority(entity: MergedMangaEntity, moveUp: Boolean) {
		launchJob(Dispatchers.Default) {
			mergeSourcesRepository.movePriority(
				primaryMangaId = primaryManga.id,
				id = entity.id,
				moveUp = moveUp,
			)
		}
	}

	private suspend fun searchSingleSource(source: MangaSource, query: String): List<Manga> {
		val resolved = if (source.name.startsWith("MIHON_") && source !is MihonMangaSource) {
			mihonExtensionManager.ensureReady(forceRefresh = false)
			ResolveMangaSource(source.name)
		} else {
			source
		}
		val repo = mangaRepositoryFactory.create(resolved)
		if (!repo.filterCapabilities.isSearchSupported) {
			return emptyList()
		}
		val order = if (SortOrder.RELEVANCE in repo.sortOrders) {
			SortOrder.RELEVANCE
		} else {
			repo.defaultSortOrder
		}
		return repo.getList(
			offset = 0,
			order = order,
			filter = MangaListFilter(query = query),
		).take(MAX_RESULTS_PER_SOURCE)
	}

	private fun computeCompatibleSources(): List<MangaSource> {
		val isNovel = primaryManga.source.isNovelSource
		return sourcesRepository.getEnabledSources().filter { src ->
			src.isNovelSource == isNovel &&
				src.name != primaryManga.source.name &&
				(!settings.isNsfwContentDisabled || !src.isNsfw())
		}
	}

	private companion object {
		const val MAX_CONCURRENT_SEARCHES = 4
		const val MAX_MULTI_SEARCH_SOURCES = 16
		const val MAX_RESULTS_PER_SOURCE = 8
		const val SEARCH_SOURCE_TIMEOUT_MS = 8_000L
	}
}
