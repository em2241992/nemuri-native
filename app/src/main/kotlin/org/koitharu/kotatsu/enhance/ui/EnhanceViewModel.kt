package org.koitharu.kotatsu.enhance.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.db.TABLE_MANGA
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DedupeWinnerRule
import org.koitharu.kotatsu.core.prefs.ReaderUpscalePreset
import org.koitharu.kotatsu.core.prefs.UpscaleComputeMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.enhance.domain.DuplicatePairItem
import org.koitharu.kotatsu.enhance.domain.EnhanceTab
import org.koitharu.kotatsu.enhance.domain.LibraryDedupeEngine
import org.koitharu.kotatsu.enhance.domain.PrioritySourceCandidate
import org.koitharu.kotatsu.enhance.domain.RepairIssueKind
import org.koitharu.kotatsu.enhance.domain.RepairItem
import org.koitharu.kotatsu.enhance.domain.UpscaleSampleItem
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@Immutable
data class EnhanceUiState(
	val selectedTab: EnhanceTab = EnhanceTab.DUPLICATES,
	val isScanningDuplicates: Boolean = true,
	val duplicatePairs: List<DuplicatePairItem> = emptyList(),
	val checkAndSwapOnAdd: Boolean = true,
	val winnerRule: DedupeWinnerRule = DedupeWinnerRule.MOST_CHAPTERS,
	val ignoredPairsCount: Int = 0,
	val isBusyMutatingDuplicates: Boolean = false,
	val priorityCandidates: List<PrioritySourceCandidate> = emptyList(),
	val selectedPriorityKeys: List<String> = emptyList(),
	val prioritySummary: String = "",
	val repairItems: List<RepairItem> = emptyList(),
	val isScanningHealth: Boolean = false,
	val healthScanDone: Int = 0,
	val healthScanTotal: Int = 0,
	val upscalePreset: ReaderUpscalePreset = ReaderUpscalePreset.OFF,
	val previewUpscalePreset: ReaderUpscalePreset = ReaderUpscalePreset.FAST_NPU_1_5X,
	val upscaleComputeMode: UpscaleComputeMode = UpscaleComputeMode.AUTO,
	val is32BitColor: Boolean = false,
	val upscaleSamples: List<UpscaleSampleItem> = emptyList(),
	val selectedSampleId: Long? = null,
	val sampleBitmap: Bitmap? = null,
	val isLoadingSampleBitmap: Boolean = false,
)

@HiltViewModel
class EnhanceViewModel @Inject constructor(
	private val db: MangaDatabase,
	private val dedupeEngine: LibraryDedupeEngine,
	private val favouritesRepository: FavouritesRepository,
	private val migrateUseCase: MigrateUseCase,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val coil: ImageLoader,
	private val settings: AppSettings,
	@ApplicationContext private val context: Context,
) : ViewModel() {

	private val manualPrimaryOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
	private val deepScanIssues = ConcurrentHashMap<Long, RepairItem>()
	private val refreshingRepairIds = MutableStateFlow<Set<Long>>(emptySet())
	private var healthScanJob: Job? = null
	private var sampleBitmapJob: Job? = null

	private val _state = MutableStateFlow(
		EnhanceUiState(
			checkAndSwapOnAdd = settings.isDuplicateCheckEnabled,
			winnerRule = settings.dedupeWinnerRule,
			ignoredPairsCount = settings.ignoredDuplicatePairs.size,
			selectedPriorityKeys = settings.enhanceSourcePriorityOrder,
			upscalePreset = settings.readerUpscalePreset,
			previewUpscalePreset = settings.previewUpscalePreset,
			upscaleComputeMode = settings.readerUpscaleComputeMode,
			is32BitColor = settings.is32BitColorsEnabled,
		),
	)
	val state: StateFlow<EnhanceUiState> = _state.asStateFlow()

	private val _capsuleToastEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
	val capsuleToastEvent: SharedFlow<String> = _capsuleToastEvent.asSharedFlow()

	init {
		observeSettingsSync()
		observeLibraryAndDedupe()
	}

	private fun observeSettingsSync() {
		viewModelScope.launch {
			combine(
				settings.observeAsFlow(AppSettings.KEY_CHECK_DUPLICATES) { isDuplicateCheckEnabled },
				settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE) { isReaderUpscaleEnabled },
				settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE_PRESET) { readerUpscalePreset },
				settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE_COMPUTE_MODE) { readerUpscaleComputeMode },
				settings.observeAsFlow(AppSettings.KEY_32BIT_COLOR) { is32BitColorsEnabled },
			) { checkSwap, _, upscale, computeMode, color32 ->
				QuintSettings(checkSwap, upscale, settings.previewUpscalePreset, computeMode, color32)
			}.collectLatest { s ->
				_state.update { current ->
					current.copy(
						checkAndSwapOnAdd = s.checkSwap,
						upscalePreset = s.upscale,
						previewUpscalePreset = s.previewUpscale,
						upscaleComputeMode = s.computeMode,
						is32BitColor = s.color32,
					)
				}
			}
		}
	}

	private fun observeLibraryAndDedupe() {
		viewModelScope.launch {
			val dbInvalidation = db.invalidationTracker.createFlow(
				TABLE_FAVOURITES,
				TABLE_MANGA,
				TABLE_HISTORY,
				"merged_manga_sources",
				"tracks",
				emitInitialState = true,
			)
			combine(
				dbInvalidation,
				mangaSourcesRepository.observeEnabledSources(),
				settings.observeAsFlow(AppSettings.KEY_ENHANCE_DEDUPE_WINNER_RULE) { dedupeWinnerRule },
				settings.observeAsFlow(AppSettings.KEY_ENHANCE_IGNORED_DUPLICATE_PAIRS) { ignoredDuplicatePairs },
				combine(
					settings.observeAsFlow(AppSettings.KEY_ENHANCE_SOURCE_PRIORITY_ORDER) { enhanceSourcePriorityOrder },
					manualPrimaryOverrides,
					refreshingRepairIds,
				) { prio, overrides, refreshing -> Triple(prio, overrides, refreshing) },
			) { _, _, winnerRule, ignored, (prioOrder, overrides, refreshing) ->
				ScanInputs(winnerRule, ignored, prioOrder, overrides, refreshing)
			}.collectLatest { inputs ->
				val computed = withContext(Dispatchers.IO) {
					val candidates = dedupeEngine.loadAvailablePrioritySources()
					val pairs = dedupeEngine.scanLibraryDuplicates(inputs.overrides)
					val passiveIssues = dedupeEngine.scanPassiveRepairIssues()
					val favManga = favouritesRepository.getAllManga()
					val favIds = favManga.mapTo(HashSet()) { it.id }
					deepScanIssues.keys.retainAll(favIds)

					val mergedRepair = LinkedHashMap<Long, RepairItem>()
					for (p in passiveIssues) {
						mergedRepair[p.manga.id] = p
					}
					for ((id, deep) in deepScanIssues) {
						mergedRepair[id] = deep
					}
					val repairList = mergedRepair.values.map { item ->
						item.copy(isRefreshing = item.manga.id in inputs.refreshing)
					}

					val samples = favManga
						.mapNotNull { m ->
							val cover = (m.largeCoverUrl ?: m.coverUrl)?.takeIf { it.isNotBlank() }
								?: return@mapNotNull null
							UpscaleSampleItem(
								mangaId = m.id,
								title = m.title,
								coverUrl = cover,
								source = m.source,
							)
						}
						.take(8)

					val summary = buildPrioritySummary(candidates, inputs.prioOrder)
					ComputedPayload(
						candidates = candidates,
						pairs = pairs,
						repairList = repairList,
						samples = samples,
						summary = summary,
					)
				}

				val prevSelectedSample = _state.value.selectedSampleId
				val nextSample = computed.samples.firstOrNull { it.mangaId == prevSelectedSample }
					?: computed.samples.firstOrNull()

				_state.update { current ->
					current.copy(
						isScanningDuplicates = false,
						duplicatePairs = computed.pairs,
						winnerRule = inputs.winnerRule,
						ignoredPairsCount = inputs.ignored.size,
						priorityCandidates = computed.candidates,
						selectedPriorityKeys = inputs.prioOrder,
						prioritySummary = computed.summary,
						repairItems = computed.repairList,
						upscaleSamples = computed.samples,
						selectedSampleId = nextSample?.mangaId,
					)
				}

				if (nextSample != null && (nextSample.mangaId != prevSelectedSample || _state.value.sampleBitmap == null)) {
					loadSampleBitmap(nextSample)
				} else if (nextSample == null && _state.value.sampleBitmap != null) {
					_state.update { it.copy(sampleBitmap = null, isLoadingSampleBitmap = false) }
				}
			}
		}
	}

	private fun buildPrioritySummary(
		candidates: List<PrioritySourceCandidate>,
		selectedKeys: List<String>,
	): String {
		if (selectedKeys.isEmpty()) {
			return context.getString(R.string.enhance_priority_summary_default)
		}
		val byKey = candidates.associateBy { it.key }
		val names = selectedKeys.mapNotNull { byKey[it]?.displayName }
		if (names.isEmpty()) {
			return context.getString(R.string.enhance_priority_summary_default)
		}
		val preview = if (names.size <= 3) {
			names.joinToString(" → ")
		} else {
			names.take(3).joinToString(" → ") + " (+${names.size - 3})"
		}
		return context.getString(R.string.enhance_priority_summary_prefix, preview)
	}

	fun selectTab(tab: EnhanceTab) {
		_state.update { it.copy(selectedTab = tab) }
	}

	fun setCheckAndSwapOnAdd(enabled: Boolean) {
		settings.isDuplicateCheckEnabled = enabled
	}

	fun setWinnerRule(rule: DedupeWinnerRule) {
		manualPrimaryOverrides.value = emptyMap()
		settings.dedupeWinnerRule = rule
	}

	fun setManualPrimary(pairKey: String, isRightPrimary: Boolean) {
		manualPrimaryOverrides.update { current ->
			current + (pairKey to isRightPrimary)
		}
	}

	fun ignorePair(pairKey: String) {
		settings.ignoredDuplicatePairs = settings.ignoredDuplicatePairs + pairKey
		emitCapsuleToast(context.getString(R.string.enhance_pair_ignored_toast))
	}

	fun resetIgnoredPairs() {
		settings.ignoredDuplicatePairs = emptySet()
	}

	fun saveSourcePriorityOrder(selectedKeys: List<String>) {
		manualPrimaryOverrides.value = emptyMap()
		settings.enhanceSourcePriorityOrder = selectedKeys
		emitCapsuleToast(context.getString(R.string.enhance_priority_saved_toast, selectedKeys.size))
	}

	fun mergeSinglePair(pair: DuplicatePairItem) {
		if (_state.value.isBusyMutatingDuplicates) return
		viewModelScope.launch {
			_state.update { it.copy(isBusyMutatingDuplicates = true) }
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					dedupeEngine.mergeDuplicatePair(pair)
				}
			}.onSuccess {
				emitCapsuleToast(context.getString(R.string.enhance_merged_pair_toast, pair.primary.manga.title))
			}.onFailure { err ->
				emitCapsuleToast(err.getDisplayMessage(context.resources))
			}
			_state.update { it.copy(isBusyMutatingDuplicates = false) }
		}
	}

	fun mergeAllPairs() {
		val pairsSnapshot = _state.value.duplicatePairs
		if (pairsSnapshot.isEmpty() || _state.value.isBusyMutatingDuplicates) return
		viewModelScope.launch {
			_state.update { it.copy(isBusyMutatingDuplicates = true) }
			var mergedCount = 0
			val mergedMangaIds = HashSet<Long>()
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					for (pair in pairsSnapshot) {
						// If an earlier pair already removed one of these duplicates, skip redundant work
						if (pair.secondary.manga.id in mergedMangaIds || pair.primary.manga.id in mergedMangaIds) {
							continue
						}
						dedupeEngine.mergeDuplicatePair(pair)
						mergedMangaIds += pair.secondary.manga.id
						mergedCount++
					}
				}
			}.onSuccess {
				if (mergedCount > 0) {
					emitCapsuleToast(context.getString(R.string.enhance_merged_all_toast, mergedCount))
				}
			}.onFailure { err ->
				emitCapsuleToast(err.getDisplayMessage(context.resources))
			}
			_state.update { it.copy(isBusyMutatingDuplicates = false) }
		}
	}

	fun migratePair(pair: DuplicatePairItem) {
		if (_state.value.isBusyMutatingDuplicates) return
		viewModelScope.launch {
			_state.update { it.copy(isBusyMutatingDuplicates = true) }
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					migrateUseCase(
						oldManga = pair.secondary.manga,
						newManga = pair.primary.manga,
						migrateProgress = true,
					)
				}
			}.onSuccess {
				emitCapsuleToast(context.getString(R.string.migration_completed))
			}.onFailure { err ->
				emitCapsuleToast(err.getDisplayMessage(context.resources))
			}
			_state.update { it.copy(isBusyMutatingDuplicates = false) }
		}
	}

	fun scanLibraryHealth() {
		if (_state.value.isScanningHealth) return
		healthScanJob?.cancel()
		healthScanJob = viewModelScope.launch {
			val targets = withContext(Dispatchers.IO) {
				favouritesRepository.getAllManga().filterNot { it.source.isLocal }
			}
			if (targets.isEmpty()) {
				emitCapsuleToast(context.getString(R.string.enhance_repair_scan_done_healthy))
				return@launch
			}

			_state.update {
				it.copy(
					isScanningHealth = true,
					healthScanDone = 0,
					healthScanTotal = targets.size,
				)
			}

			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					val passiveById = dedupeEngine.scanPassiveRepairIssues().associateBy { it.manga.id }
					val semaphore = Semaphore(4)
					val doneCounter = AtomicInteger(0)
					deepScanIssues.clear()

					supervisorScope {
						targets.map { manga ->
							async {
								semaphore.withPermit {
									val passive = passiveById[manga.id]
									if (passive != null && passive.issueKind == RepairIssueKind.UNAVAILABLE_SOURCE) {
										deepScanIssues[manga.id] = passive
									} else {
										val sourceTitle = manga.source.getTitle(context).ifBlank { manga.source.name }
										runCatchingCancellable {
											withTimeout(8_000L) {
												mangaRepositoryFactory.create(manga.source).getDetails(manga)
											}
										}.onSuccess { details ->
											val chapters = details.chapters
											val cover = details.largeCoverUrl ?: details.coverUrl
											when {
												chapters.isNullOrEmpty() -> {
													deepScanIssues[manga.id] = RepairItem(
														manga = details,
														sourceTitle = sourceTitle,
														issueKind = RepairIssueKind.ZERO_CHAPTERS,
														reasonText = context.getString(R.string.enhance_issue_zero_chapters),
													)
												}

												cover.isNullOrBlank() -> {
													deepScanIssues[manga.id] = RepairItem(
														manga = details,
														sourceTitle = sourceTitle,
														issueKind = RepairIssueKind.MISSING_COVER,
														reasonText = context.getString(R.string.enhance_issue_missing_cover),
													)
												}

												else -> {
													deepScanIssues.remove(manga.id)
													if ((manga.largeCoverUrl ?: manga.coverUrl).isNullOrBlank()) {
														mangaDataRepository.storeManga(details, replaceExisting = true)
													}
												}
											}
										}.onFailure { err ->
											deepScanIssues[manga.id] = RepairItem(
												manga = manga,
												sourceTitle = sourceTitle,
												issueKind = RepairIssueKind.UNREACHABLE_SOURCE,
												reasonText = context.getString(
													R.string.enhance_issue_unreachable,
													err.getDisplayMessage(context.resources),
												),
											)
										}
									}
									val finished = doneCounter.incrementAndGet()
									_state.update { it.copy(healthScanDone = finished) }
								}
							}
						}.awaitAll()
					}

					val finalPassive = dedupeEngine.scanPassiveRepairIssues()
					val combined = LinkedHashMap<Long, RepairItem>()
					for (p in finalPassive) combined[p.manga.id] = p
					for ((id, d) in deepScanIssues) combined[id] = d
					combined.values.toList()
				}
			}.onSuccess { updatedList ->
				_state.update {
					it.copy(
						isScanningHealth = false,
						repairItems = updatedList,
					)
				}
				val msg = if (updatedList.isEmpty()) {
					context.getString(R.string.enhance_repair_scan_done_healthy)
				} else {
					context.getString(R.string.enhance_repair_scan_done_issues, updatedList.size)
				}
				emitCapsuleToast(msg)
			}.onFailure { err ->
				_state.update { it.copy(isScanningHealth = false) }
				emitCapsuleToast(err.getDisplayMessage(context.resources))
			}
		}
	}

	fun refreshRepairItem(item: RepairItem) {
		val mangaId = item.manga.id
		if (mangaId in refreshingRepairIds.value) return
		viewModelScope.launch {
			refreshingRepairIds.update { it + mangaId }
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					val details = withTimeout(10_000L) {
						mangaRepositoryFactory.create(item.manga.source).getDetails(item.manga)
					}
					mangaDataRepository.storeManga(details, replaceExisting = true)
					details
				}
			}.onSuccess { details ->
				val cover = details.largeCoverUrl ?: details.coverUrl
				if (!details.chapters.isNullOrEmpty() && !cover.isNullOrBlank()) {
					deepScanIssues.remove(mangaId)
				} else if (details.chapters.isNullOrEmpty()) {
					deepScanIssues[mangaId] = item.copy(
						manga = details,
						issueKind = RepairIssueKind.ZERO_CHAPTERS,
						reasonText = context.getString(R.string.enhance_issue_zero_chapters),
						isRefreshing = false,
					)
				}
				emitCapsuleToast(context.getString(R.string.enhance_repair_refreshed_toast, details.title))
			}.onFailure { err ->
				deepScanIssues[mangaId] = item.copy(
					issueKind = RepairIssueKind.UNREACHABLE_SOURCE,
					reasonText = context.getString(
						R.string.enhance_issue_unreachable,
						err.getDisplayMessage(context.resources),
					),
					isRefreshing = false,
				)
				emitCapsuleToast(err.getDisplayMessage(context.resources))
			}
			refreshingRepairIds.update { it - mangaId }
		}
	}

	fun selectUpscalePreset(preset: ReaderUpscalePreset) {
		settings.readerUpscalePreset = preset
	}

	fun selectUpscaleComputeMode(mode: UpscaleComputeMode) {
		settings.readerUpscaleComputeMode = mode
	}

	fun setUpscaleActive(active: Boolean) {
		settings.isReaderUpscaleEnabled = active
	}

	fun set32BitColor(enabled: Boolean) {
		settings.is32BitColorsEnabled = enabled
	}

	fun selectUpscaleSample(sample: UpscaleSampleItem) {
		if (_state.value.selectedSampleId == sample.mangaId && _state.value.sampleBitmap != null) return
		_state.update { it.copy(selectedSampleId = sample.mangaId) }
		loadSampleBitmap(sample)
	}

	private fun loadSampleBitmap(sample: UpscaleSampleItem) {
		sampleBitmapJob?.cancel()
		sampleBitmapJob = viewModelScope.launch {
			_state.update { it.copy(isLoadingSampleBitmap = true) }
			val bmp = withContext(Dispatchers.IO) {
				runCatchingCancellable {
					val request = ImageRequest.Builder(context)
						.data(sample.coverUrl)
						.allowHardware(false)
						.allowConversionToBitmap(true)
						.mangaSourceExtra(sample.source)
						.build()
					coil.execute(request).toBitmapOrNull()
				}.getOrNull()
			}
			_state.update {
				it.copy(
					sampleBitmap = bmp,
					isLoadingSampleBitmap = false,
				)
			}
		}
	}

	private fun emitCapsuleToast(message: String) {
		if (message.isNotBlank()) {
			_capsuleToastEvent.tryEmit(message)
		}
	}

	private data class QuintSettings(
		val checkSwap: Boolean,
		val upscale: ReaderUpscalePreset,
		val previewUpscale: ReaderUpscalePreset,
		val computeMode: UpscaleComputeMode,
		val color32: Boolean,
	)

	private data class ScanInputs(
		val winnerRule: DedupeWinnerRule,
		val ignored: Set<String>,
		val prioOrder: List<String>,
		val overrides: Map<String, Boolean>,
		val refreshing: Set<Long>,
	)

	private data class ComputedPayload(
		val candidates: List<PrioritySourceCandidate>,
		val pairs: List<DuplicatePairItem>,
		val repairList: List<RepairItem>,
		val samples: List<UpscaleSampleItem>,
		val summary: String,
	)
}
