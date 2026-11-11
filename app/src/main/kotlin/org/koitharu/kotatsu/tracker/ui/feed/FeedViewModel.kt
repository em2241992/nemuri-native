package org.koitharu.kotatsu.tracker.ui.feed

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.tracker.domain.FeedDiscoveryUseCase
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedChapterStreamRow
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedDateGroup
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedDiscoveryCardItem
import org.koitharu.kotatsu.tracker.ui.feed.model.ForYouUiState
import org.koitharu.kotatsu.tracker.ui.feed.model.GalleryUiState
import org.koitharu.kotatsu.tracker.ui.feed.model.HubUiState
import org.koitharu.kotatsu.tracker.ui.feed.model.NemuriFeedFilter
import org.koitharu.kotatsu.tracker.ui.feed.model.NemuriFeedMode
import org.koitharu.kotatsu.tracker.ui.feed.model.NemuriHubSection
import org.koitharu.kotatsu.tracker.ui.feed.model.UpdatesStreamUiState
import org.koitharu.kotatsu.tracker.work.TrackWorker
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val INITIAL_LOG_LIMIT = 60
private const val LOG_PAGE_STEP = 40
private const val PREF_KEY_NEMURI_FEED_MODE = "nemuri_feed_mode"

@HiltViewModel
class FeedViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val settings: AppSettings,
	private val db: MangaDatabase,
	private val repository: TrackingRepository,
	private val scheduler: TrackWorker.Scheduler,
	private val favouritesRepository: FavouritesRepository,
	private val historyRepository: HistoryRepository,
	private val discoveryUseCase: FeedDiscoveryUseCase,
	private val sourcesRepository: MangaSourcesRepository,
) : BaseViewModel() {

	private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(appContext) }
	private val limit = MutableStateFlow(INITIAL_LOG_LIMIT)
	private val canLoadMoreLogs = AtomicBoolean(false)

	private val _selectedMode = MutableStateFlow(
		NemuriFeedMode.fromId(
			PreferenceManager.getDefaultSharedPreferences(appContext)
				.getString(PREF_KEY_NEMURI_FEED_MODE, null),
		),
	)
	val selectedMode: StateFlow<NemuriFeedMode> = _selectedMode.asStateFlow()

	private val _selectedFilter = MutableStateFlow(NemuriFeedFilter.ALL)
	val selectedFilter: StateFlow<NemuriFeedFilter> = _selectedFilter.asStateFlow()

	private val _activeHubSection = MutableStateFlow<NemuriHubSection?>(null)
	val activeHubSection: StateFlow<NemuriHubSection?> = _activeHubSection.asStateFlow()

	private val _selectedAffinityTag = MutableStateFlow<String?>(null)

	private val _hubDiscovery = MutableStateFlow<FeedDiscoveryUseCase.HubDiscoveryResult?>(null)
	private val _isHubDiscoveryLoading = MutableStateFlow(false)
	private var hubDiscoveryJob: Job? = null

	private val _forYouDiscovery = MutableStateFlow<FeedDiscoveryUseCase.ForYouDiscoveryResult?>(null)
	private val _isForYouLoading = MutableStateFlow(false)
	private var forYouDiscoveryJob: Job? = null

	val isRunning: StateFlow<Boolean> = scheduler.observeIsRunning()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val libraryMangaIds: StateFlow<Set<Long>> = favouritesRepository
		.observeAll(ListSortOrder.NEWEST, emptySet(), Int.MAX_VALUE)
		.map { list -> list.mapTo(HashSet(list.size)) { it.id } }
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

	val downloadedMangaIds: StateFlow<Set<Long>> = db.getLocalMangaIndexDao()
		.observeAllMangaIds()
		.map { ids -> ids.toHashSet() }
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

	private val historyByMangaId: StateFlow<Map<Long, MangaHistory>> = historyRepository
		.observeAllWithHistory(ListSortOrder.LAST_READ, emptySet(), Int.MAX_VALUE)
		.map { list -> list.associate { it.manga.id to it.history } }
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyMap())

	private val nsfwFilterFlow: Flow<Set<ListFilterOption>> = settings
		.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }
		.map { disableNsfw -> if (disableNsfw) setOf(ListFilterOption.SFW) else emptySet() }
		.distinctUntilChanged()

	private val rawChapterRows: StateFlow<Pair<List<FeedChapterStreamRow>, Boolean>> = combine(
		combine(limit, nsfwFilterFlow, ::Pair).flatMapLatest { (lim, filters) ->
			repository.observeTrackingLog(lim, filters)
		},
		nsfwFilterFlow.flatMapLatest { filters ->
			repository.observeUpdatedManga(60, filters)
		},
		libraryMangaIds,
		downloadedMangaIds,
		historyByMangaId,
	) { logs, updatedManga, favIds, downloadedIds, historyMap ->
		val rows = buildChronologicalStreamRows(
			logs = logs,
			updatedManga = updatedManga,
			favIds = favIds,
			downloadedIds = downloadedIds,
			historyMap = historyMap,
		)
		canLoadMoreLogs.set(logs.size >= limit.value)
		rows to false
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		emptyList<FeedChapterStreamRow>() to true,
	)

	val updatesStreamState: StateFlow<UpdatesStreamUiState> = combine(
		rawChapterRows,
		_selectedFilter,
	) { (allRows, isInitialLoading), filter ->
		val filtered = allRows.filter { row -> row.matchesFilter(filter) }
		val groups = groupRowsByRelativeDate(filtered)
		UpdatesStreamUiState(
			groups = groups,
			totalUnfilteredCount = allRows.size,
			isInitialLoading = isInitialLoading,
		)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		UpdatesStreamUiState(),
	)

	val hubState: StateFlow<HubUiState> = combine(
		rawChapterRows,
		_hubDiscovery,
		_isHubDiscoveryLoading,
		combine(libraryMangaIds, downloadedMangaIds, historyByMangaId, ::Triple),
		_selectedFilter,
	) { (allRows, _), discovery, isLoading, (favIds, downloadedIds, historyMap), filter ->
		val feedUpdateCards = buildUpdateDiscoveryCards(
			rows = allRows,
			deduplicateByManga = true,
			onlyFollowed = false,
		).filter { it.matchesFilter(filter) }
			.take(20)

		val popularCards = discovery?.popular.orEmpty()
			.map { manga ->
				manga.toDiscoveryCard(
					keyPrefix = "hub_pop",
					favIds = favIds,
					downloadedIds = downloadedIds,
					historyMap = historyMap,
				)
			}
			.filter { it.matchesFilter(filter) }

		val latestCards = discovery?.latest.orEmpty()
			.map { manga ->
				manga.toDiscoveryCard(
					keyPrefix = "hub_latest",
					favIds = favIds,
					downloadedIds = downloadedIds,
					historyMap = historyMap,
				)
			}
			.filter { it.matchesFilter(filter) }

		HubUiState(
			feedUpdates = feedUpdateCards,
			popularNewTitles = popularCards,
			latestUploads = latestCards,
			isDiscoveryLoading = isLoading && discovery == null,
			hasEnabledSources = discovery?.hasEnabledSources ?: true,
		)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		HubUiState(isDiscoveryLoading = true),
	)

	val galleryState: StateFlow<GalleryUiState> = combine(
		rawChapterRows,
		_hubDiscovery,
		_isHubDiscoveryLoading,
		combine(libraryMangaIds, downloadedMangaIds, historyByMangaId, ::Triple),
		combine(_activeHubSection, _selectedFilter, ::Pair),
	) { (allRows, isStreamLoading), discovery, isHubLoading, (favIds, downloadedIds, historyMap), (section, filter) ->
		when (section) {
			NemuriHubSection.FEED_UPDATES -> {
				val base = buildUpdateDiscoveryCards(
					rows = allRows,
					deduplicateByManga = true,
					onlyFollowed = false,
				)
				GalleryUiState(
					activeSection = section,
					items = base.filter { it.matchesFilter(filter) },
					isLoading = isStreamLoading,
					totalUnfilteredCount = base.size,
				)
			}

			NemuriHubSection.POPULAR_NEW -> {
				val base = discovery?.popular.orEmpty().map { manga ->
					manga.toDiscoveryCard("gal_pop", favIds, downloadedIds, historyMap)
				}
				GalleryUiState(
					activeSection = section,
					items = base.filter { it.matchesFilter(filter) },
					isLoading = isHubLoading && base.isEmpty(),
					totalUnfilteredCount = base.size,
				)
			}

			NemuriHubSection.LATEST_UPLOADS -> {
				val base = discovery?.latest.orEmpty().map { manga ->
					manga.toDiscoveryCard("gal_latest", favIds, downloadedIds, historyMap)
				}
				GalleryUiState(
					activeSection = section,
					items = base.filter { it.matchesFilter(filter) },
					isLoading = isHubLoading && base.isEmpty(),
					totalUnfilteredCount = base.size,
				)
			}

			null -> {
				val base = buildUpdateDiscoveryCards(
					rows = allRows,
					deduplicateByManga = false,
					onlyFollowed = false,
				)
				GalleryUiState(
					activeSection = null,
					items = base.filter { it.matchesFilter(filter) },
					isLoading = isStreamLoading,
					totalUnfilteredCount = base.size,
				)
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		GalleryUiState(isLoading = true),
	)

	val forYouState: StateFlow<ForYouUiState> = combine(
		_forYouDiscovery,
		_isForYouLoading,
		_selectedAffinityTag,
		combine(libraryMangaIds, downloadedMangaIds, historyByMangaId, ::Triple),
		_selectedFilter,
	) { discovery, isLoading, activeTag, (favIds, downloadedIds, historyMap), filter ->
		val rawItems = discovery?.items.orEmpty()
		val cards = rawItems.mapNotNull { entry ->
			val inLib = entry.manga.id in favIds
			// Exclude titles already in library unless the user explicitly enabled the "My Follows" chip
			if (inLib && filter != NemuriFeedFilter.MY_FOLLOWS) {
				return@mapNotNull null
			}
			if (activeTag != null && entry.matchedTags.none { it.equals(activeTag, ignoreCase = true) }) {
				return@mapNotNull null
			}
			val history = historyMap[entry.manga.id]
			val isUnread = history == null || history.percent < 0.98f
			val isDownloaded = entry.manga.id in downloadedIds
			val card = FeedDiscoveryCardItem(
				key = "for_you_${entry.manga.id}",
				manga = entry.manga,
				subtitleOverride = entry.matchedTags.takeIf { it.isNotEmpty() }?.joinToString(" • "),
				matchedTags = entry.matchedTags,
				inLibrary = inLib,
				isUnread = isUnread,
				isDownloaded = isDownloaded,
			)
			card.takeIf { it.matchesFilter(filter) }
		}
		ForYouUiState(
			affinityTags = discovery?.affinityTags.orEmpty(),
			selectedTag = activeTag,
			items = cards,
			isLoading = isLoading && discovery == null,
			isLibraryAndHistoryEmpty = discovery?.isLibraryAndHistoryEmpty == true && favIds.isEmpty() && historyMap.isEmpty(),
		)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		ForYouUiState(isLoading = true),
	)

	init {
		launchJob(Dispatchers.Default) {
			repository.gc()
		}
		// Trigger initial load if the persisted startup mode is Hub or For You
		when (_selectedMode.value) {
			NemuriFeedMode.HUB -> ensureHubLoaded()
			NemuriFeedMode.FOR_YOU -> ensureForYouLoaded()
			NemuriFeedMode.UPDATES,
			NemuriFeedMode.GALLERY -> Unit
		}
		// Refresh discovery automatically if sources finish loading after Hub was opened empty
		launchJob(Dispatchers.Default) {
			sourcesRepository.observeEnabledSources()
				.map { it.size }
				.distinctUntilChanged()
				.collect { count ->
					if (count > 0) {
						val currentHub = _hubDiscovery.value
						if (_selectedMode.value == NemuriFeedMode.HUB &&
							(currentHub == null || (!currentHub.hasEnabledSources || (currentHub.popular.isEmpty() && currentHub.latest.isEmpty())))
						) {
							refreshHubDiscovery(force = true)
						}
						if (_selectedMode.value == NemuriFeedMode.FOR_YOU &&
							_forYouDiscovery.value?.items.isNullOrEmpty()
						) {
							refreshForYouDiscovery(force = true)
						}
					}
				}
		}
	}

	fun setMode(mode: NemuriFeedMode) {
		if (_selectedMode.value != mode) {
			_selectedMode.value = mode
			prefs.edit { putString(PREF_KEY_NEMURI_FEED_MODE, mode.id) }
		}
		if (mode != NemuriFeedMode.GALLERY) {
			_activeHubSection.value = null
		}
		when (mode) {
			NemuriFeedMode.HUB -> ensureHubLoaded()
			NemuriFeedMode.FOR_YOU -> ensureForYouLoaded()
			NemuriFeedMode.UPDATES,
			NemuriFeedMode.GALLERY -> Unit
		}
	}

	fun setFilter(filter: NemuriFeedFilter) {
		_selectedFilter.value = if (_selectedFilter.value == filter && filter != NemuriFeedFilter.ALL) {
			NemuriFeedFilter.ALL
		} else {
			filter
		}
	}

	fun openHubSection(section: NemuriHubSection) {
		_activeHubSection.value = section
		_selectedMode.value = NemuriFeedMode.GALLERY
		if (section != NemuriHubSection.FEED_UPDATES) {
			ensureHubLoaded()
		}
	}

	fun closeHubSectionToHub() {
		_activeHubSection.value = null
		_selectedMode.value = NemuriFeedMode.HUB
	}

	fun selectAffinityTag(tag: String?) {
		_selectedAffinityTag.value = if (_selectedAffinityTag.value == tag) null else tag
	}

	fun refreshActiveMode() {
		when (_selectedMode.value) {
			NemuriFeedMode.UPDATES -> update()
			NemuriFeedMode.HUB -> {
				update()
				refreshHubDiscovery(force = true)
			}

			NemuriFeedMode.GALLERY -> {
				when (_activeHubSection.value) {
					NemuriHubSection.POPULAR_NEW,
					NemuriHubSection.LATEST_UPLOADS -> refreshHubDiscovery(force = true)
					NemuriHubSection.FEED_UPDATES,
					null -> update()
				}
			}

			NemuriFeedMode.FOR_YOU -> refreshForYouDiscovery(force = true)
		}
	}

	fun ensureHubLoaded() {
		if (_hubDiscovery.value == null && !_isHubDiscoveryLoading.value) {
			refreshHubDiscovery(force = false)
		}
	}

	fun refreshHubDiscovery(force: Boolean) {
		if (_isHubDiscoveryLoading.value && !force) return
		hubDiscoveryJob?.cancel()
		hubDiscoveryJob = launchJob(Dispatchers.IO) {
			_isHubDiscoveryLoading.value = true
			try {
				_hubDiscovery.value = discoveryUseCase.fetchHubDiscovery()
			} finally {
				_isHubDiscoveryLoading.value = false
			}
		}
	}

	fun ensureForYouLoaded() {
		if (_forYouDiscovery.value == null && !_isForYouLoading.value) {
			refreshForYouDiscovery(force = false)
		}
	}

	fun refreshForYouDiscovery(force: Boolean) {
		if (_isForYouLoading.value && !force) return
		forYouDiscoveryJob?.cancel()
		forYouDiscoveryJob = launchJob(Dispatchers.IO) {
			_isForYouLoading.value = true
			try {
				_forYouDiscovery.value = discoveryUseCase.fetchForYouDiscovery()
			} finally {
				_isForYouLoading.value = false
			}
		}
	}

	fun requestMoreUpdates() {
		if (canLoadMoreLogs.compareAndSet(true, false)) {
			limit.value += LOG_PAGE_STEP
		}
	}

	fun clearFeed(clearCounters: Boolean) {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearLogs()
			if (clearCounters) {
				repository.clearCounters()
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
		}
	}

	fun update() {
		scheduler.startNow()
	}

	fun stopUpdate() {
		launchJob(Dispatchers.Default) {
			scheduler.stopNow()
		}
	}

	fun markMangaUpdatesRead(manga: Manga) {
		launchJob(Dispatchers.Default) {
			val handle = repository.markLogsRead(manga.id)
			onActionDone.call(ReversibleAction(R.string.marked_as_read, handle))
		}
	}

	fun removeFeedLog(logId: Long) {
		if (logId <= 0L) return
		launchJob(Dispatchers.Default) {
			val handle = repository.removeLog(logId) ?: return@launchJob
			onActionDone.call(ReversibleAction(R.string.feed_entry_removed, handle))
		}
	}

	private suspend fun buildChronologicalStreamRows(
		logs: List<TrackingLogItem>,
		updatedManga: List<MangaTracking>,
		favIds: Set<Long>,
		downloadedIds: Set<Long>,
		historyMap: Map<Long, MangaHistory>,
	): List<FeedChapterStreamRow> {
		val chapterCache = HashMap<Long, Map<Long, ChapterEntity>>()

		suspend fun chaptersFor(mangaId: Long): Map<Long, ChapterEntity> {
			return chapterCache.getOrPut(mangaId) {
				db.getChaptersDao().findAll(mangaId).associateBy { it.chapterId }
			}
		}

		if (logs.isNotEmpty()) {
			val rows = ArrayList<FeedChapterStreamRow>(logs.size * 2)
			for (log in logs) {
				val manga = log.manga
				val chaptersById = chaptersFor(manga.id)
				val history = historyMap[manga.id]
				val lastReadIndex = history?.chapterId?.let { chaptersById[it]?.index }
				val inLib = manga.id in favIds
				val isDownloaded = manga.id in downloadedIds
				val bucket = calculateTimeAgo(log.createdAt)

				if (log.chapters.isEmpty()) {
					rows += FeedChapterStreamRow(
						key = "log_${log.id}_summary",
						logId = log.id,
						manga = manga,
						chapterId = null,
						chapterTitle = manga.title,
						createdAt = log.createdAt,
						dateBucket = bucket,
						isUnread = log.isNew,
						inLibrary = inLib,
						isDownloaded = isDownloaded,
					)
					continue
				}

				log.chapters.asReversed().forEachIndexed { revIdx, ch ->
					val entity = ch.id?.let { chaptersById[it] }
					val readByHistory = if (entity != null && lastReadIndex != null) {
						entity.index < lastReadIndex || (entity.index == lastReadIndex && history.percent >= 0.98f)
					} else {
						false
					}
					val isUnread = log.isNew && ch.isNew && !readByHistory
					rows += FeedChapterStreamRow(
						key = "log_${log.id}_${ch.id ?: revIdx}_${revIdx}",
						logId = log.id,
						manga = manga,
						chapterId = ch.id,
						chapterTitle = formatChapterLabel(ch.name, entity),
						createdAt = log.createdAt,
						dateBucket = bucket,
						isUnread = isUnread,
						inLibrary = inLib,
						isDownloaded = isDownloaded,
					)
				}
			}
			return rows
		}

		// Supplemental real Room fallback if track_logs hasn't accumulated entries yet
		// while tracked manga already have known new chapters in tracks + chapters tables.
		if (updatedManga.isEmpty()) return emptyList()
		val fallbackRows = ArrayList<FeedChapterStreamRow>()
		for (tracked in updatedManga) {
			if (tracked.newChapters <= 0) continue
			val entities = db.getChaptersDao().findAll(tracked.manga.id)
			if (entities.isEmpty()) continue
			val history = historyMap[tracked.manga.id]
			val lastReadIndex = history?.chapterId?.let { targetId ->
				entities.firstOrNull { it.chapterId == targetId }?.index
			}
			val recentEntities = entities.takeLast(tracked.newChapters.coerceIn(1, 5)).asReversed()
			val baseInstant = tracked.lastChapterDate ?: tracked.lastCheck ?: Instant.now()
			val inLib = tracked.manga.id in favIds
			val isDownloaded = tracked.manga.id in downloadedIds
			for (entity in recentEntities) {
				val chapterInstant = if (entity.uploadDate > 0L) {
					Instant.ofEpochMilli(entity.uploadDate)
				} else {
					baseInstant
				}
				val readByHistory = if (lastReadIndex != null) {
					entity.index < lastReadIndex || (entity.index == lastReadIndex && history.percent >= 0.98f)
				} else {
					false
				}
				fallbackRows += FeedChapterStreamRow(
					key = "track_${tracked.manga.id}_${entity.chapterId}",
					logId = -tracked.manga.id,
					manga = tracked.manga,
					chapterId = entity.chapterId,
					chapterTitle = formatChapterLabel(entity.title, entity),
					createdAt = chapterInstant,
					dateBucket = calculateTimeAgo(chapterInstant),
					isUnread = !readByHistory,
					inLibrary = inLib,
					isDownloaded = isDownloaded,
				)
			}
		}
		fallbackRows.sortByDescending { it.createdAt }
		return fallbackRows
	}

	private fun groupRowsByRelativeDate(rows: List<FeedChapterStreamRow>): List<FeedDateGroup> {
		if (rows.isEmpty()) return emptyList()
		val grouped = LinkedHashMap<String, Pair<DateTimeAgo?, MutableList<FeedChapterStreamRow>>>()
		for (row in rows) {
			// Collapse "JustNow" into "Today" for Neko-style section headers ("Today", "Yesterday", date)
			val headerBucket = when (row.dateBucket) {
				is DateTimeAgo.JustNow -> DateTimeAgo.Today
				else -> row.dateBucket
			}
			val groupKey = headerBucket?.toString() ?: "unknown"
			val pair = grouped.getOrPut(groupKey) { headerBucket to ArrayList() }
			pair.second += row
		}
		return grouped.map { (key, value) ->
			FeedDateGroup(
				key = key,
				dateBucket = value.first,
				rows = value.second,
			)
		}
	}

	private fun buildUpdateDiscoveryCards(
		rows: List<FeedChapterStreamRow>,
		deduplicateByManga: Boolean,
		onlyFollowed: Boolean,
	): List<FeedDiscoveryCardItem> {
		if (rows.isEmpty()) return emptyList()
		val seenMangaIds = HashSet<Long>()
		val result = ArrayList<FeedDiscoveryCardItem>(rows.size)
		for (row in rows) {
			if (onlyFollowed && !row.inLibrary) continue
			if (deduplicateByManga && !seenMangaIds.add(row.manga.id)) continue
			val pill = extractCompactChapterPill(row.chapterTitle)
			result += FeedDiscoveryCardItem(
				key = if (deduplicateByManga) "upd_manga_${row.manga.id}" else "upd_row_${row.key}",
				manga = row.manga,
				subtitleOverride = row.chapterTitle,
				chapterId = row.chapterId,
				chapterPill = pill,
				inLibrary = row.inLibrary,
				isUnread = row.isUnread,
				isDownloaded = row.isDownloaded,
			)
		}
		return result
	}

	private fun Manga.toDiscoveryCard(
		keyPrefix: String,
		favIds: Set<Long>,
		downloadedIds: Set<Long>,
		historyMap: Map<Long, MangaHistory>,
	): FeedDiscoveryCardItem {
		val history = historyMap[id]
		return FeedDiscoveryCardItem(
			key = "${keyPrefix}_${source.name}_$id",
			manga = this,
			inLibrary = id in favIds,
			isUnread = history == null || history.percent < 0.98f,
			isDownloaded = id in downloadedIds,
		)
	}

	private fun FeedChapterStreamRow.matchesFilter(filter: NemuriFeedFilter): Boolean = when (filter) {
		NemuriFeedFilter.ALL -> true
		NemuriFeedFilter.MY_FOLLOWS -> inLibrary
		NemuriFeedFilter.UNREAD_ONLY -> isUnread
		NemuriFeedFilter.DOWNLOADED -> isDownloaded
	}

	private fun FeedDiscoveryCardItem.matchesFilter(filter: NemuriFeedFilter): Boolean = when (filter) {
		NemuriFeedFilter.ALL -> true
		NemuriFeedFilter.MY_FOLLOWS -> inLibrary
		NemuriFeedFilter.UNREAD_ONLY -> isUnread
		NemuriFeedFilter.DOWNLOADED -> isDownloaded
	}

	private fun formatChapterLabel(rawName: String, entity: ChapterEntity?): String {
		val trimmed = rawName.trim()
		if (trimmed.isNotEmpty()) return trimmed
		if (entity != null) {
			if (entity.title.isNotBlank()) return entity.title.trim()
			val numStr = formatChapterNumber(entity.number)
			if (numStr != null) {
				return if (entity.volume > 0) "Vol.${entity.volume} Ch.$numStr" else "Ch.$numStr"
			}
		}
		return "New chapter"
	}

	private fun extractCompactChapterPill(chapterTitle: String): String? {
		val match = CHAPTER_NUMBER_REGEX.find(chapterTitle)
		if (match != null) {
			return "Ch.${match.groupValues[1]}"
		}
		return chapterTitle.takeIf { it.length <= 12 }
	}

	private fun formatChapterNumber(number: Float): String? {
		if (number <= 0f) return null
		val intPart = number.toInt()
		return if (number == intPart.toFloat()) {
			intPart.toString()
		} else {
			String.format(Locale.US, "%.1f", number).trimEnd('0').trimEnd('.')
		}
	}

	private companion object {
		val CHAPTER_NUMBER_REGEX = Regex("""(?:ch(?:apter)?\.?\s*|#)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
	}
}
