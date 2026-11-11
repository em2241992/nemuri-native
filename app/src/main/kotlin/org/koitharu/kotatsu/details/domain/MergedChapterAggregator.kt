package org.koitharu.kotatsu.details.domain

import coil3.request.CachePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.logs.breadcrumb
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.FreshMangaDetailsRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.data.MergeSourcesRepository
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MergedChapterAggregator @Inject constructor(
	private val mergeSourcesRepository: MergeSourcesRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mihonExtensionManager: MihonExtensionManager,
) {

	/**
	 * Concurrently fetches [primarySeed] and all enabled `MergedMangaEntity` sources inside
	 * `supervisorScope` with a strict per-source 8-second timeout and `runCatchingCancellable`.
	 *
	 * - An offline, timed-out, or Cloudflare-blocked secondary source NEVER crashes or blocks refresh.
	 * - If the primary source itself fails while at least one secondary source succeeds (or cached
	 *   primary chapters exist), the merged chapter list still resolves so reading is uninterrupted.
	 */
	suspend fun loadMergedDetails(
		primarySeed: Manga,
		force: Boolean,
		bypassCache: Boolean = false,
		primaryLoader: suspend () -> Result<Manga>,
	): Result<Manga> {
		val enabledLinks = mergeSourcesRepository.getEnabledMergedSources(primarySeed.id)
		if (enabledLinks.isEmpty()) {
			return primaryLoader()
		}

		return supervisorScope {
			val primaryDeferred = async {
				runCatchingCancellable {
					withTimeout(SOURCE_TIMEOUT_MS) {
						primaryLoader().getOrThrow()
					}
				}
			}

			val secondaryDeferreds = enabledLinks.map { link ->
				async {
					val result = runCatchingCancellable {
						withTimeout(SOURCE_TIMEOUT_MS) {
							fetchSecondaryDetails(link, force = force, bypassCache = bypassCache)
						}
					}.onFailure { error ->
						breadcrumb(TAG) {
							"Secondary merge source ${link.mergedSourceName} (${link.mergedTitle}) failed/timed out: $error"
						}
					}
					link to result.getOrNull()
				}
			}

			val primaryResult = primaryDeferred.await()
			val secondaryResults = secondaryDeferreds.awaitAll()

			// Warm secondary manga + chapter memos in DB without failing if persistence hits an edge case
			for ((_, secondaryDetails) in secondaryResults) {
				if (secondaryDetails != null) {
					runCatchingCancellable {
						mangaDataRepository.storeManga(
							secondaryDetails,
							replaceExisting = true,
							stripAppliedOverride = false,
							detailsFetched = true,
						)
					}
				}
			}

			val loadedSecondary = secondaryResults.mapNotNull { (link, details) ->
				details?.let { link to it }
			}

			val primaryLoaded = primaryResult.getOrNull()
			if (primaryLoaded == null && loadedSecondary.isEmpty() && primarySeed.chapters.isNullOrEmpty()) {
				return@supervisorScope primaryResult
			}

			val baseManga = primaryLoaded ?: primarySeed
			val mergedChapters = mergeChapterLists(
				baseManga = baseManga,
				secondarySources = loadedSecondary,
			)
			Result.success(baseManga.copy(chapters = mergedChapters))
		}
	}

	/**
	 * Pure canonical deduplication + gap-fill helper (also directly unit-testable).
	 * Respects source priority (`baseManga` first, then `secondarySources` in `priorityOrder` ASC).
	 */
	fun mergeChapterLists(
		baseManga: Manga,
		secondarySources: List<Pair<MergedMangaEntity, Manga>>,
	): List<MangaChapter> = mergeChapterListsStatic(baseManga, secondarySources)

	private suspend fun fetchSecondaryDetails(
		link: MergedMangaEntity,
		force: Boolean,
		bypassCache: Boolean,
	): Manga {
		val seed = mergeSourcesRepository.toSeedManga(link)
		return try {
			loadSingleSource(seed, force = force, bypassCache = bypassCache, refreshExtensions = false)
		} catch (error: UnsupportedSourceException) {
			if (seed.source.isExternalSource()) {
				loadSingleSource(seed, force = force, bypassCache = bypassCache, refreshExtensions = true)
			} else {
				throw error
			}
		}
	}

	private suspend fun loadSingleSource(
		seed: Manga,
		force: Boolean,
		bypassCache: Boolean,
		refreshExtensions: Boolean,
	): Manga {
		val resolvedSeed = if (seed.source.name.startsWith("MIHON_")) {
			mihonExtensionManager.ensureReady(forceRefresh = refreshExtensions || seed.source !is MihonMangaSource)
			val resolvedSource = ResolveMangaSource(seed.source.name)
			seed.copy(source = resolvedSource)
		} else {
			seed
		}
		val repo = mangaRepositoryFactory.create(resolvedSeed.source)
		return when {
			bypassCache && repo is FreshMangaDetailsRepository -> repo.getFreshDetails(resolvedSeed)
			repo is CachingMangaRepository -> repo.getDetails(
				resolvedSeed,
				if (force || bypassCache) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED,
			)
			else -> repo.getDetails(resolvedSeed)
		}
	}

	private data class OrderedChapter(
		val chapter: MangaChapter,
		val hasCanonicalNumber: Boolean,
		val sortNumber: Float,
		val sourcePriority: Int,
		val originalIndex: Int,
	)

	companion object {
		private const val TAG = "MergedChapterAggregator"
		const val SOURCE_TIMEOUT_MS = 8_000L

		fun mergeChapterListsStatic(
			baseManga: Manga,
			secondarySources: List<Pair<MergedMangaEntity, Manga>>,
		): List<MangaChapter> {
			val baseChapters = baseManga.chapters.orEmpty()
			if (secondarySources.isEmpty()) {
				return baseChapters
			}

			val primaryBranches = baseChapters.mapTo(HashSet()) { it.branch }
			val defaultTargetBranch = if (baseChapters.isNotEmpty()) {
				baseManga.getPreferredBranch(null)
			} else {
				secondarySources.firstNotNullOfOrNull { (_, secManga) ->
					secManga.getPreferredBranch(null)
				}
			}

			val seenKeys = HashSet<Pair<String?, String>>()
			val collectedByBranch = LinkedHashMap<String?, MutableList<OrderedChapter>>()

			fun appendChapter(chapter: MangaChapter, effectiveBranch: String?, sourcePriority: Int) {
				val canonicalNum = NekoChapterCanonicalizer.parseChapterNumber(
					mangaTitle = baseManga.title,
					chapterTitle = chapter.title,
					chapterNumber = chapter.number,
				)
				val canonicalKey = NekoChapterCanonicalizer.toCanonicalKey(
					mangaTitle = baseManga.title,
					chapterTitle = chapter.title,
					chapterNumber = chapter.number,
					fallbackId = chapter.url,
				)
				if (!seenKeys.add(effectiveBranch to canonicalKey)) {
					return
				}
				val normalizedChapter = if (chapter.branch != effectiveBranch || (chapter.number < 0f && canonicalNum >= 0f)) {
					chapter.copy(
						branch = effectiveBranch,
						number = if (chapter.number < 0f && canonicalNum >= 0f) canonicalNum else chapter.number,
					)
				} else {
					chapter
				}
				val bucket = collectedByBranch.getOrPut(effectiveBranch) { ArrayList() }
				val prevSortNum = bucket.lastOrNull()?.sortNumber ?: 0f
				val sortNum = if (canonicalNum >= 0f) canonicalNum else (prevSortNum + 0.0001f)
				bucket.add(
					OrderedChapter(
						chapter = normalizedChapter,
						hasCanonicalNumber = canonicalNum >= 0f,
						sortNumber = sortNum,
						sourcePriority = sourcePriority,
						originalIndex = bucket.size,
					),
				)
			}

			// Priority -1: Primary source chapters
			for (chapter in baseChapters) {
				appendChapter(chapter = chapter, effectiveBranch = chapter.branch, sourcePriority = -1)
			}

			// Priority 0..N: Linked secondary sources in ascending priorityOrder
			val orderedSecondaries = secondarySources.sortedWith(
				compareBy<Pair<MergedMangaEntity, Manga>> { it.first.priorityOrder }.thenBy { it.first.id },
			)
			for ((link, secManga) in orderedSecondaries) {
				val secPreferredBranch = secManga.getPreferredBranch(null)
				val secChapters = secManga.getChapters(secPreferredBranch).ifEmpty {
					secManga.chapters.orEmpty()
				}
				for (secChapter in secChapters) {
					val effectiveBranch = when {
						baseChapters.isEmpty() -> defaultTargetBranch
						secChapter.branch in primaryBranches -> secChapter.branch
						else -> defaultTargetBranch
					}
					appendChapter(
						chapter = secChapter,
						effectiveBranch = effectiveBranch,
						sourcePriority = link.priorityOrder,
					)
				}
			}

			val unified = ArrayList<MangaChapter>(seenKeys.size)
			for ((_, bucket) in collectedByBranch) {
				bucket.sortWith(
					compareBy<OrderedChapter> { it.sortNumber }
						.thenBy { it.sourcePriority }
						.thenBy { it.originalIndex },
				)
				bucket.mapTo(unified) { it.chapter }
			}
			return unified
		}
	}
}
