package org.koitharu.kotatsu.reader.domain

import android.util.LongSparseArray
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.NekoChapterCanonicalizer
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

private const val PAGES_TRIM_THRESHOLD = 120

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val silentFailoverLoader: SilentFailoverChapterLoader,
	private val settings: AppSettings,
) {

	data class ChapterStepResult(
		val chapter: MangaChapter?,
		val skippedDuplicateLabel: String? = null,
	)

	private val chapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()

	@Volatile
	private var manga: Manga? = null

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: MangaDetails) = mutex.withLock {
		this.manga = manga.sourceManga
		chapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
		}
	}

	fun resolveAdjacentChapter(
		manga: MangaDetails,
		currentId: Long,
		isNext: Boolean,
		skipDuplicates: Boolean = settings.isReaderSkipDuplicateChaptersEnabled,
	): ChapterStepResult {
		val currentChapter = chapters[currentId] ?: manga.allChapters.find { it.id == currentId }
		val branchChapters = currentChapter?.let { ch ->
			manga.chapters[ch.branch]?.takeIf { list -> list.any { it.id == currentId } }
		} ?: manga.allChapters
		val predicate: (MangaChapter) -> Boolean = { it.id == currentId }
		val index = if (isNext) branchChapters.indexOfFirst(predicate) else branchChapters.indexOfLast(predicate)
		if (index == -1) return ChapterStepResult(null)

		val step = if (isNext) 1 else -1
		if (!skipDuplicates) {
			return ChapterStepResult(branchChapters.getOrNull(index + step))
		}

		val mangaTitle = manga.toManga().title
		val currentCanonical = currentChapter?.let {
			NekoChapterCanonicalizer.parseChapterNumber(mangaTitle, it.title, it.number)
		} ?: -1f

		var skippedLabel: String? = null
		var cursor = index + step
		while (cursor in branchChapters.indices) {
			val candidate = branchChapters[cursor]
			val candCanonical = NekoChapterCanonicalizer.parseChapterNumber(
				mangaTitle,
				candidate.title,
				candidate.number,
			)
			val isSameCanonicalAsCurrent = currentCanonical >= 0f &&
				candCanonical >= 0f &&
				abs(candCanonical - currentCanonical) < 0.001f
			if (isSameCanonicalAsCurrent) {
				if (skippedLabel == null) {
					skippedLabel = formatCanonicalNumber(candCanonical)
				}
				cursor += step
				continue
			}

			// If multiple consecutive chapters share this target canonical number, pick the most
			// consistent scanlator/source match so we don't land on a redundant duplicate upload.
			if (candCanonical >= 0f) {
				val cluster = ArrayList<MangaChapter>(2)
				var lookahead = cursor
				while (lookahead in branchChapters.indices) {
					val item = branchChapters[lookahead]
					val itemNum = NekoChapterCanonicalizer.parseChapterNumber(
						mangaTitle,
						item.title,
						item.number,
					)
					if (itemNum >= 0f && abs(itemNum - candCanonical) < 0.001f) {
						cluster.add(item)
						lookahead += step
					} else {
						break
					}
				}
				if (cluster.size > 1) {
					val preferredScanlator = currentChapter?.scanlator?.takeIf { it.isNotBlank() }
					val chosen = cluster.firstOrNull {
						preferredScanlator != null && it.scanlator.equals(preferredScanlator, ignoreCase = true)
					} ?: cluster.firstOrNull {
						currentChapter != null && it.source == currentChapter.source
					} ?: cluster.first()
					if (chosen.id != candidate.id && skippedLabel == null) {
						skippedLabel = formatCanonicalNumber(candCanonical)
					}
					return ChapterStepResult(chosen, skippedLabel)
				}
			}

			return ChapterStepResult(candidate, skippedLabel)
		}

		return ChapterStepResult(null, skippedLabel)
	}

	suspend fun loadPrevNextChapter(
		manga: MangaDetails,
		currentId: Long,
		isNext: Boolean,
	): Boolean = loadPrevNextChapterWithStep(manga, currentId, isNext).chapter != null

	suspend fun loadPrevNextChapterWithStep(
		manga: MangaDetails,
		currentId: Long,
		isNext: Boolean,
	): ChapterStepResult {
		val stepResult = resolveAdjacentChapter(
			manga = manga,
			currentId = currentId,
			isNext = isNext,
			skipDuplicates = settings.isReaderSkipDuplicateChaptersEnabled,
		)
		val newChapter = stepResult.chapter ?: return ChapterStepResult(null)
		val newPages = loadChapter(newChapter.id)
		mutex.withLock {
			if (chapterPages.chaptersSize > 1) {
				// trim pages
				if (chapterPages.size > PAGES_TRIM_THRESHOLD) {
					if (isNext) {
						chapterPages.removeFirst()
					} else {
						chapterPages.removeLast()
					}
				}
			}
			if (isNext) {
				chapterPages.addLast(newChapter.id, newPages)
			} else {
				chapterPages.addFirst(newChapter.id, newPages)
			}
		}
		return stepResult
	}

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean {
		val pages = loadChapter(chapterId)
		return mutex.withLock {
			chapterPages.clear()
			chapterPages.addLast(chapterId, pages)
			pages.isNotEmpty()
		}
	}

	fun peekChapter(chapterId: Long): MangaChapter? = chapters[chapterId]

	// All reads below go through synchronized(chapterPages) — the same monitor ChapterPages' own
	// @Synchronized mutators lock on — so a concurrent add/remove can't be observed mid-mutation
	// (e.g. a ConcurrentModificationException from toList()/first()/last() reading the deque while
	// another coroutine is still shifting indices).
	fun hasPages(chapterId: Long): Boolean = synchronized(chapterPages) {
		chapterId in chapterPages
	}

	fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
		chapterPages.subList(chapterId).map { it.toMangaPage() }
	}

	fun getPagesCount(chapterId: Long): Int = synchronized(chapterPages) {
		chapterPages.size(chapterId)
	}

	fun last() = synchronized(chapterPages) { chapterPages.last() }

	fun first() = synchronized(chapterPages) { chapterPages.first() }

	fun snapshot() = synchronized(chapterPages) { chapterPages.toList() }

	private suspend fun loadChapter(chapterId: Long): List<ReaderPage> {
		val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
		val primaryManga = checkNotNull(manga)
		return silentFailoverLoader.loadChapterPages(primaryManga, chapter).mapIndexed { index, page ->
			ReaderPage(page, index, chapterId)
		}
	}

	private fun formatCanonicalNumber(number: Float): String {
		val asInt = number.toInt()
		return if (abs(number - asInt.toFloat()) < 0.001f) {
			asInt.toString()
		} else {
			String.format(Locale.US, "%.1f", number).trimEnd('0').trimEnd('.')
		}
	}
}
