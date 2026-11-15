package org.koitharu.kotatsu.enhance.domain

import android.content.Context
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DedupeWinnerRule
import org.koitharu.kotatsu.core.util.TitleNormalizer
import org.koitharu.kotatsu.details.data.MergeSourcesRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.langCode
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Reusable
class LibraryDedupeEngine @Inject constructor(
	private val db: MangaDatabase,
	private val favouritesRepository: FavouritesRepository,
	private val mergeSourcesRepository: MergeSourcesRepository,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val mihonExtensionManager: MihonExtensionManager,
	private val lnPluginManager: LnPluginManager,
	private val kotatsuSourceMap: KotatsuSourceMap,
	private val settings: AppSettings,
	@ApplicationContext private val context: Context,
) {

	/**
	 * Builds the real list of installed/enabled sources for the Mihon `Selected / Available`
	 * Source Priority Selector. Zero hardcoded or mock sources.
	 */
	suspend fun loadAvailablePrioritySources(): List<PrioritySourceCandidate> {
		mihonExtensionManager.ensureReady()
		lnPluginManager.initialize()
		val enabled = mangaSourcesRepository.getEnabledSources()
		val seenKeys = HashSet<String>(enabled.size)
		val result = ArrayList<PrioritySourceCandidate>(enabled.size)
		for (raw in enabled) {
			val unwrapped = if (raw is MangaSourceInfo) raw.mangaSource else raw
			if (unwrapped.isLocal) continue
			val key = canonicalSourceKey(unwrapped)
			if (!seenKeys.add(key)) continue
			val title = unwrapped.getTitle(context).ifBlank { unwrapped.name }
			val lang = when (unwrapped) {
				is MihonMangaSource -> unwrapped.language.uppercase(Locale.ROOT).ifBlank { "ALL" }
				is LnMangaSource -> unwrapped.plugin.langCode.uppercase(Locale.ROOT).ifBlank { "EN" }
				else -> "EXT"
			}
			result += PrioritySourceCandidate(
				key = key,
				source = unwrapped,
				displayName = title,
				langBadge = lang,
			)
		}
		return result
	}

	/**
	 * Scans the user's real Room `favourites` library for fuzzy/normalized cross-source duplicates
	 * (`>= 0.82f` similarity or shared tracker link), excluding ignored pairs and pairs already
	 * linked in `merged_manga_sources`.
	 */
	suspend fun scanLibraryDuplicates(
		manualPrimaryOverrides: Map<String, Boolean> = emptyMap(),
	): List<DuplicatePairItem> {
		mihonExtensionManager.ensureReady()
		lnPluginManager.initialize()

		val allFavourites = favouritesRepository.getAllManga()
		if (allFavourites.size < 2) return emptyList()

		val installedMihonIds = mihonExtensionManager.getMihonMangaSources()
			.mapTo(HashSet()) { it.sourceId }
		val installedLnIds = lnPluginManager.getAll()
			.mapTo(HashSet()) { it.pluginId }
		val enabledSourceKeys = mangaSourcesRepository.getEnabledSources()
			.mapTo(HashSet()) { canonicalSourceKey(if (it is MangaSourceInfo) it.mangaSource else it) }

		val priorityOrder = settings.enhanceSourcePriorityOrder
		val priorityIndexByKey = HashMap<String, Int>(priorityOrder.size * 2)
		priorityOrder.forEachIndexed { idx, key ->
			priorityIndexByKey.putIfAbsent(key, idx)
		}

		val chaptersDao = db.getChaptersDao()
		val historyDao = db.getHistoryDao()
		val tracksDao = db.getTracksDao()
		val scrobblingDao = db.getScrobblingDao()
		val mergedLinks = db.getMergedMangaDao().findAll()
		val alreadyMergedPairs = HashSet<String>(mergedLinks.size)
		for (link in mergedLinks) {
			alreadyMergedPairs += buildPairKey(link.primaryMangaId, link.mergedMangaId)
		}

		val ignoredPairs = settings.ignoredDuplicatePairs
		val winnerRule = settings.dedupeWinnerRule

		val snapshots = ArrayList<EntryWithTitles>(allFavourites.size)
		for (manga in allFavourites) {
			val chaptersCount = max(chaptersDao.count(manga.id), manga.chapters?.size ?: 0)
			val progress = historyDao.find(manga.id)?.percent ?: PROGRESS_NONE
			val categories = favouritesRepository.getCategoriesIds(manga.id)
			val trackError = tracksDao.find(manga.id)?.lastError
			val key = canonicalSourceKey(manga.source)
			val titleKey = "title:${manga.source.getTitle(context).trim().lowercase(Locale.ROOT)}"
			val pIndex = priorityIndexByKey[key]
				?: priorityIndexByKey[manga.source.name]
				?: priorityIndexByKey[titleKey]
				?: Int.MAX_VALUE
			val healthy = isSourceHealthy(
				source = manga.source,
				canonicalKey = key,
				installedMihonIds = installedMihonIds,
				installedLnIds = installedLnIds,
				enabledSourceKeys = enabledSourceKeys,
				trackError = trackError,
			)
			val cleanRating = if (manga.rating != RATING_UNKNOWN && manga.rating > 0f) manga.rating else 0f
			val snapshot = LibraryEntrySnapshot(
				manga = manga,
				sourceTitle = manga.source.getTitle(context).ifBlank { manga.source.name },
				sourceKey = key,
				chaptersCount = chaptersCount,
				readProgress = progress,
				categoryIds = categories,
				isHealthySource = healthy,
				rating = cleanRating,
				priorityIndex = pIndex,
			)
			val candidateTitles = buildList(manga.altTitles.size + 1) {
				if (manga.title.isNotBlank()) add(manga.title)
				for (alt in manga.altTitles) {
					if (alt.isNotBlank()) add(alt)
				}
			}
			val linkedIds = runCatching { scrobblingDao.findLinkedMangaIds(manga.id).toSet() }
				.getOrDefault(emptySet())
			snapshots += EntryWithTitles(
				snapshot = snapshot,
				titles = candidateTitles,
				normalizedPrimary = TitleNormalizer.normalize(manga.title),
				trackerLinkedIds = linkedIds,
			)
		}

		val detected = ArrayList<DuplicatePairItem>()
		for (i in 0 until snapshots.size) {
			val a = snapshots[i]
			for (j in i + 1 until snapshots.size) {
				val b = snapshots[j]
				// Never treat novel + comic as duplicates, and only dedupe across distinct entries/sources
				if (a.snapshot.manga.isEpub != b.snapshot.manga.isEpub) continue
				if (a.snapshot.manga.id == b.snapshot.manga.id) continue
				if (a.snapshot.manga.source.name == b.snapshot.manga.source.name &&
					a.normalizedPrimary != b.normalizedPrimary
				) {
					continue
				}

				val pairKey = buildPairKey(a.snapshot.manga.id, b.snapshot.manga.id)
				if (pairKey in ignoredPairs || pairKey in alreadyMergedPairs) continue

				val sharedTracker = b.snapshot.manga.id in a.trackerLinkedIds ||
					a.snapshot.manga.id in b.trackerLinkedIds
				val similarity = if (sharedTracker) {
					1.0f
				} else {
					computeMaxTitleSimilarity(a, b)
				}

				if (similarity >= SIMILARITY_THRESHOLD) {
					// Order left/right deterministically by manga.id so manual overrides stay stable
					val (leftSnap, rightSnap) = if (a.snapshot.manga.id < b.snapshot.manga.id) {
						a.snapshot to b.snapshot
					} else {
						b.snapshot to a.snapshot
					}
					val isRightWinner = manualPrimaryOverrides[pairKey]
						?: pickIsRightPrimary(leftSnap, rightSnap, winnerRule)
					detected += DuplicatePairItem(
						pairKey = pairKey,
						left = leftSnap,
						right = rightSnap,
						similarity = similarity,
						isRightPrimary = isRightWinner,
					)
				}
			}
		}

		return detected.sortedWith(
			compareByDescending<DuplicatePairItem> { it.similarity }
				.thenBy { it.primary.manga.title.lowercase(Locale.ROOT) },
		)
	}

	/**
	 * Performs a passive Room/extension health audit of the user's library (broken/uninstalled
	 * extensions, missing cover URL, or stored tracker error).
	 */
	suspend fun scanPassiveRepairIssues(): List<RepairItem> {
		mihonExtensionManager.ensureReady()
		lnPluginManager.initialize()
		val allFavourites = favouritesRepository.getAllManga()
		if (allFavourites.isEmpty()) return emptyList()

		val installedMihonIds = mihonExtensionManager.getMihonMangaSources()
			.mapTo(HashSet()) { it.sourceId }
		val installedLnIds = lnPluginManager.getAll()
			.mapTo(HashSet()) { it.pluginId }
		val enabledSourceKeys = mangaSourcesRepository.getEnabledSources()
			.mapTo(HashSet()) { canonicalSourceKey(if (it is MangaSourceInfo) it.mangaSource else it) }
		val tracksDao = db.getTracksDao()

		val issues = ArrayList<RepairItem>()
		for (manga in allFavourites) {
			if (manga.source.isLocal) continue
			val sourceTitle = manga.source.getTitle(context).ifBlank { manga.source.name }
			val key = canonicalSourceKey(manga.source)
			val represented = isSourceRepresented(manga.source, key, installedMihonIds, installedLnIds, enabledSourceKeys)
			if (!represented) {
				issues += RepairItem(
					manga = manga,
					sourceTitle = sourceTitle,
					issueKind = RepairIssueKind.UNAVAILABLE_SOURCE,
					reasonText = context.getString(R.string.enhance_issue_uninstalled_source),
				)
				continue
			}
			val cover = manga.largeCoverUrl ?: manga.coverUrl
			if (cover.isNullOrBlank()) {
				issues += RepairItem(
					manga = manga,
					sourceTitle = sourceTitle,
					issueKind = RepairIssueKind.MISSING_COVER,
					reasonText = context.getString(R.string.enhance_issue_missing_cover),
				)
				continue
			}
			val trackError = tracksDao.find(manga.id)?.lastError
			if (!trackError.isNullOrBlank()) {
				issues += RepairItem(
					manga = manga,
					sourceTitle = sourceTitle,
					issueKind = RepairIssueKind.UNREACHABLE_SOURCE,
					reasonText = context.getString(R.string.enhance_issue_unreachable, trackError),
				)
			}
		}
		return issues
	}

	/**
	 * Merges [pair]'s secondary entry into its `★ Primary` winner:
	 * 1. Links `secondary.manga` into `primary.manga` in `merged_manga_sources`
	 * 2. Unions favourite shelves so no category membership is lost
	 * 3. Carries forward higher read progress into `history` if secondary was read further
	 * 4. Removes the redundant secondary duplicate from `favourites`
	 */
	suspend fun mergeDuplicatePair(pair: DuplicatePairItem) {
		val primary = pair.primary
		val secondary = pair.secondary

		mergeSourcesRepository.linkSource(
			primaryManga = primary.manga,
			secondaryManga = secondary.manga,
		)

		val extraCategories = secondary.categoryIds - primary.categoryIds
		for (categoryId in extraCategories) {
			favouritesRepository.addToCategory(categoryId, listOf(primary.manga))
		}

		val primaryProg = primary.readProgress.coerceAtLeast(0f)
		val secondaryProg = secondary.readProgress.coerceAtLeast(0f)
		if (secondaryProg > primaryProg) {
			carryForwardProgress(primary.manga, secondary.manga)
		}

		if (secondary.manga.id != primary.manga.id) {
			favouritesRepository.removeFromFavourites(listOf(secondary.manga.id))
		}
	}

	private suspend fun carryForwardProgress(primaryManga: Manga, secondaryManga: Manga) {
		val historyDao = db.getHistoryDao()
		val secondaryHistory = historyDao.find(secondaryManga.id) ?: return
		val primaryHistory = historyDao.find(primaryManga.id)
		if (primaryHistory != null && primaryHistory.percent >= secondaryHistory.percent) {
			return
		}

		val chaptersDao = db.getChaptersDao()
		val primaryChapters = chaptersDao.findAll(primaryManga.id)
		if (primaryChapters.isEmpty()) return

		val secondaryChapters = chaptersDao.findAll(secondaryManga.id)
		val secCurrent = secondaryChapters.firstOrNull { it.chapterId == secondaryHistory.chapterId }

		val matchedPrimaryChapter = if (secCurrent != null && secCurrent.number > 0f) {
			primaryChapters.firstOrNull { ch -> abs(ch.number - secCurrent.number) < 0.01f }
		} else {
			null
		} ?: run {
			val idx = ((primaryChapters.lastIndex) * secondaryHistory.percent.coerceIn(0f, 1f)).toInt()
				.coerceIn(0, primaryChapters.lastIndex)
			primaryChapters[idx]
		}

		val now = System.currentTimeMillis()
		historyDao.upsert(
			HistoryEntity(
				mangaId = primaryManga.id,
				createdAt = primaryHistory?.createdAt ?: secondaryHistory.createdAt,
				updatedAt = max(now, secondaryHistory.updatedAt),
				chapterId = matchedPrimaryChapter.chapterId,
				page = secondaryHistory.page,
				scroll = secondaryHistory.scroll,
				percent = secondaryHistory.percent,
				deletedAt = 0L,
				chaptersCount = primaryChapters.size,
			),
		)
	}

	fun canonicalSourceKey(source: MangaSource): String = when (source) {
		is MangaSourceInfo -> canonicalSourceKey(source.mangaSource)
		is MihonMangaSource -> "mihon:${source.pkgName}:${source.catalogueSource.name}"
		is LnMangaSource -> "ln:${source.pluginId}"
		else -> {
			val rawName = source.name
			val mihonId = rawName
				.takeIf { it.startsWith("MIHON_") }
				?.removePrefix("MIHON_")
				?.substringBefore(':')
				?.toLongOrNull()
			val matchedMihon = if (mihonId != null) {
				mihonExtensionManager.getMihonMangaSources().firstOrNull { it.sourceId == mihonId }
			} else {
				mihonExtensionManager.getMihonMangaSources().firstOrNull { it.name == rawName }
			}
			if (matchedMihon != null) {
				"mihon:${matchedMihon.pkgName}:${matchedMihon.catalogueSource.name}"
			} else {
				val matchedLn = lnPluginManager.getAll().firstOrNull { it.name == rawName }
				if (matchedLn != null) {
					"ln:${matchedLn.pluginId}"
				} else {
					rawName
				}
			}
		}
	}

	private suspend fun isSourceRepresented(
		source: MangaSource,
		canonicalKey: String,
		installedMihonIds: Set<Long>,
		installedLnIds: Set<String>,
		enabledSourceKeys: Set<String>,
	): Boolean {
		if (source.isLocal) return true
		if (canonicalKey in enabledSourceKeys) return true
		val rawName = source.name
		if (rawName.startsWith("MIHON_")) {
			val id = rawName.removePrefix("MIHON_").substringBefore(':').toLongOrNull()
			return id != null && id in installedMihonIds
		}
		if (rawName.startsWith("LN_")) {
			val pluginId = rawName.removePrefix("LN_")
			return pluginId in installedLnIds
		}
		val legacy = kotatsuSourceMap.resolve(rawName)
		return legacy != null && legacy.sourceId in installedMihonIds
	}

	private suspend fun isSourceHealthy(
		source: MangaSource,
		canonicalKey: String,
		installedMihonIds: Set<Long>,
		installedLnIds: Set<String>,
		enabledSourceKeys: Set<String>,
		trackError: String?,
	): Boolean {
		if (!trackError.isNullOrBlank()) return false
		return isSourceRepresented(source, canonicalKey, installedMihonIds, installedLnIds, enabledSourceKeys)
	}

	private fun computeMaxTitleSimilarity(a: EntryWithTitles, b: EntryWithTitles): Float {
		if (a.normalizedPrimary.length >= 3 && a.normalizedPrimary == b.normalizedPrimary) {
			return 1.0f
		}
		var best = 0f
		for (ta in a.titles) {
			for (tb in b.titles) {
				val sim = TitleNormalizer.fuzzyTitleSimilarity(ta, tb)
				if (sim > best) {
					best = sim
					if (best >= 0.995f) return 1.0f
				}
			}
		}
		return best
	}

	private fun pickIsRightPrimary(
		left: LibraryEntrySnapshot,
		right: LibraryEntrySnapshot,
		rule: DedupeWinnerRule,
	): Boolean {
		val cmp = when (rule) {
			DedupeWinnerRule.MOST_CHAPTERS -> right.chaptersCount.compareTo(left.chaptersCount)
			DedupeWinnerRule.FURTHEST_READ -> right.readProgress.coerceAtLeast(0f)
				.compareTo(left.readProgress.coerceAtLeast(0f))

			DedupeWinnerRule.HEALTHY_SOURCE -> right.isHealthySource.compareTo(left.isHealthySource)
			DedupeWinnerRule.HIGHEST_RATING -> right.rating.compareTo(left.rating)
		}
		if (cmp != 0) return cmp > 0

		// Tie-breaker 1: Mihon Source Priority Order (smaller index wins!)
		val prioCmp = left.priorityIndex.compareTo(right.priorityIndex)
		if (prioCmp != 0) return prioCmp > 0 // right has smaller priorityIndex -> right wins

		// Tie-breaker 2: Healthy source
		val healthCmp = right.isHealthySource.compareTo(left.isHealthySource)
		if (healthCmp != 0) return healthCmp > 0

		// Tie-breaker 3: Most chapters
		val chapCmp = right.chaptersCount.compareTo(left.chaptersCount)
		if (chapCmp != 0) return chapCmp > 0

		// Tie-breaker 4: Furthest read
		return right.readProgress.coerceAtLeast(0f) > left.readProgress.coerceAtLeast(0f)
	}

	private fun buildPairKey(idA: Long, idB: Long): String {
		val lo = min(idA, idB)
		val hi = max(idA, idB)
		return "$lo:$hi"
	}

	private class EntryWithTitles(
		val snapshot: LibraryEntrySnapshot,
		val titles: List<String>,
		val normalizedPrimary: String,
		val trackerLinkedIds: Set<Long>,
	)

	companion object {
		const val SIMILARITY_THRESHOLD = 0.82f
	}
}
