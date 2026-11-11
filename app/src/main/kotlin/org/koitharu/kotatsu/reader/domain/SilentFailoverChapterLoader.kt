package org.koitharu.kotatsu.reader.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.logs.breadcrumb
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.details.data.MergeSourcesRepository
import org.koitharu.kotatsu.details.domain.MergedChapterAggregator
import org.koitharu.kotatsu.details.domain.NekoChapterCanonicalizer
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Coordinates source-accurate chapter loading for merged chapters and automatic silent failover
 * across linked `MergedMangaEntity` sources when a chapter or individual page fails to load.
 */
@ActivityRetainedScoped
class SilentFailoverChapterLoader @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val mergeSourcesRepository: MergeSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mihonExtensionManager: MihonExtensionManager,
) {

	/** Emits the localized backup source title whenever Reader fails over to a backup source. */
	val onFailoverSource = MutableEventFlow<String>()

	private val pageContexts = ConcurrentHashMap<Long, PageFailoverContext>()
	private val resolvedBackupPagesCache = ConcurrentHashMap<Long, Pair<MangaSource, List<MangaPage>>>()
	private val notifiedChapterFailovers = ConcurrentHashMap.newKeySet<String>()
	private val fallbackMutex = Mutex()

	suspend fun loadChapterPages(
		primaryManga: Manga,
		chapter: MangaChapter,
	): List<MangaPage> {
		val ownerManga = resolveOwnerManga(primaryManga, chapter)
		val initialAttempt = runCatchingCancellable {
			val pages = fetchPagesFromSource(ownerManga, chapter)
			check(pages.isNotEmpty()) { "Source returned empty page list for chapter ${chapter.id}" }
			pages
		}
		initialAttempt.getOrNull()?.let { pages ->
			registerChapterPages(primaryManga, chapter, pages)
			return pages
		}

		val primaryError = initialAttempt.exceptionOrNull()
		if (chapter.source.isLocal) {
			throw primaryError ?: IllegalStateException("Failed to load local chapter")
		}

		val backupCandidates = buildBackupCandidates(primaryManga, failedSource = chapter.source)
		if (backupCandidates.isEmpty()) {
			throw primaryError ?: IllegalStateException("Failed to load chapter and no merged sources linked")
		}

		val targetKey = NekoChapterCanonicalizer.toCanonicalKey(primaryManga.title, chapter)
		for (candidateSeed in backupCandidates) {
			val fallbackPages = runCatchingCancellable {
				withTimeout(MergedChapterAggregator.SOURCE_TIMEOUT_MS) {
					resolveMatchingChapterAndPages(
						primaryTitle = primaryManga.title,
						candidateSeed = candidateSeed,
						targetCanonicalKey = targetKey,
					)
				}
			}.onFailure { err ->
				breadcrumb(TAG) { "Backup source ${candidateSeed.source.name} failed during chapter failover: $err" }
			}.getOrNull()

			if (!fallbackPages.isNullOrEmpty()) {
				val backupSource = fallbackPages.first().source
				resolvedBackupPagesCache[chapter.id] = backupSource to fallbackPages
				registerChapterPages(primaryManga, chapter, fallbackPages)
				notifyFailoverOnce(chapter.id, backupSource)
				return fallbackPages
			}
		}

		throw primaryError ?: IllegalStateException("All merged backup sources failed for chapter ${chapter.id}")
	}

	/**
	 * Called by [PageLoader] when an individual page image fails to download/decrypt on its active source.
	 * Resolves the corresponding page at the same index from a linked merged source if available.
	 */
	suspend fun findFallbackPage(failedPage: MangaPage): MangaPage? {
		if (failedPage.source.isLocal) return null
		val ctx = pageContexts[failedPage.id] ?: return null
		if (failedPage.source != ctx.activeSource) return null

		return fallbackMutex.withLock {
			resolvedBackupPagesCache[ctx.chapter.id]?.let { (backupSource, cachedPages) ->
				if (backupSource.name != failedPage.source.name) {
					val replacement = cachedPages.getOrNull(ctx.pageIndex)
					if (replacement != null) {
						notifyFailoverOnce(ctx.chapter.id, backupSource)
						return@withLock replacement
					}
				}
			}

			val candidates = buildBackupCandidates(ctx.primaryManga, failedSource = failedPage.source)
			if (candidates.isEmpty()) return@withLock null

			val targetKey = NekoChapterCanonicalizer.toCanonicalKey(ctx.primaryManga.title, ctx.chapter)
			for (candidateSeed in candidates) {
				val pages = runCatchingCancellable {
					withTimeout(MergedChapterAggregator.SOURCE_TIMEOUT_MS) {
						resolveMatchingChapterAndPages(
							primaryTitle = ctx.primaryManga.title,
							candidateSeed = candidateSeed,
							targetCanonicalKey = targetKey,
						)
					}
				}.onFailure { err ->
					breadcrumb(TAG) { "Backup source ${candidateSeed.source.name} failed during page failover: $err" }
				}.getOrNull()

				if (!pages.isNullOrEmpty() && ctx.pageIndex in pages.indices) {
					val backupSource = pages[ctx.pageIndex].source
					resolvedBackupPagesCache[ctx.chapter.id] = backupSource to pages
					notifyFailoverOnce(ctx.chapter.id, backupSource)
					return@withLock pages[ctx.pageIndex]
				}
			}
			null
		}
	}

	private fun registerChapterPages(
		primaryManga: Manga,
		chapter: MangaChapter,
		pages: List<MangaPage>,
	) {
		if (pageContexts.size > MAX_TRACKED_PAGES) {
			pageContexts.clear()
		}
		pages.forEachIndexed { index, page ->
			pageContexts[page.id] = PageFailoverContext(
				primaryManga = primaryManga,
				chapter = chapter,
				pageIndex = index,
				activeSource = page.source,
			)
		}
	}

	private fun notifyFailoverOnce(chapterId: Long, backupSource: MangaSource) {
		val dedupeKey = "$chapterId|${backupSource.name}"
		if (notifiedChapterFailovers.add(dedupeKey)) {
			onFailoverSource.call(backupSource.getTitle(appContext))
		}
	}

	private suspend fun resolveOwnerManga(primaryManga: Manga, chapter: MangaChapter): Manga {
		if (chapter.source.isLocal || chapter.source.name == primaryManga.source.name) {
			return ensureResolvedSource(primaryManga)
		}
		val link = mergeSourcesRepository.getMergedSources(primaryManga.id)
			.firstOrNull { it.mergedSourceName == chapter.source.name }
		return if (link != null) {
			ensureResolvedSource(mergeSourcesRepository.toSeedManga(link))
		} else {
			ensureResolvedSource(primaryManga)
		}
	}

	private suspend fun buildBackupCandidates(
		primaryManga: Manga,
		failedSource: MangaSource,
	): List<Manga> {
		val enabledLinks = mergeSourcesRepository.getEnabledMergedSources(primaryManga.id)
		val candidates = ArrayList<Manga>(enabledLinks.size + 1)
		if (!primaryManga.isLocal && primaryManga.source.name != failedSource.name) {
			candidates.add(primaryManga)
		}
		for (link in enabledLinks) {
			if (link.mergedSourceName != failedSource.name) {
				candidates.add(mergeSourcesRepository.toSeedManga(link))
			}
		}
		return candidates
	}

	private suspend fun resolveMatchingChapterAndPages(
		primaryTitle: String,
		candidateSeed: Manga,
		targetCanonicalKey: String,
	): List<MangaPage>? {
		val resolvedSeed = ensureResolvedSource(candidateSeed)
		val repo = mangaRepositoryFactory.create(resolvedSeed.source)
		val detailedManga = if (resolvedSeed.chapters.isNullOrEmpty()) {
			repo.getDetails(resolvedSeed)
		} else {
			resolvedSeed
		}
		val preferredBranch = detailedManga.getPreferredBranch(null)
		val searchPool = detailedManga.getChapters(preferredBranch) + detailedManga.chapters.orEmpty()
		val matchedChapter = searchPool.firstOrNull { ch ->
			NekoChapterCanonicalizer.toCanonicalKey(primaryTitle, ch) == targetCanonicalKey ||
				NekoChapterCanonicalizer.toCanonicalKey(detailedManga.title, ch) == targetCanonicalKey
		} ?: return null

		return try {
			repo.getPages(detailedManga, matchedChapter)
		} catch (e: CancellationException) {
			throw e
		}
	}

	private suspend fun fetchPagesFromSource(ownerManga: Manga, chapter: MangaChapter): List<MangaPage> {
		val resolvedManga = ensureResolvedSource(ownerManga)
		val resolvedChapter = if (chapter.source.name.startsWith("MIHON_") && chapter.source !is MihonMangaSource) {
			chapter.copy(source = ResolveMangaSource(chapter.source.name))
		} else {
			chapter
		}
		val repo = mangaRepositoryFactory.create(resolvedChapter.source)
		return repo.getPages(resolvedManga, resolvedChapter)
	}

	private suspend fun ensureResolvedSource(manga: Manga): Manga {
		if (manga.source.name.startsWith("MIHON_") && manga.source !is MihonMangaSource) {
			mihonExtensionManager.ensureReady(forceRefresh = false)
			return manga.copy(source = ResolveMangaSource(manga.source.name))
		}
		return manga
	}

	private data class PageFailoverContext(
		val primaryManga: Manga,
		val chapter: MangaChapter,
		val pageIndex: Int,
		val activeSource: MangaSource,
	)

	private companion object {
		const val TAG = "SilentFailoverLoader"
		const val MAX_TRACKED_PAGES = 600
	}
}
