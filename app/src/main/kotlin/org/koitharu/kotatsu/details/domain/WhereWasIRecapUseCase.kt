package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.ai.LocalExtractiveFallbackException
import org.koitharu.kotatsu.core.ai.PluggableAiRouter
import org.koitharu.kotatsu.core.db.dao.MangaAiRecapDao
import org.koitharu.kotatsu.core.db.entity.MangaAiRecapEntity
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

data class QuarantinedRecapContext(
	val pausedChapter: MangaChapter,
	val pausedCanonicalNumber: Float,
	val readChapterCount: Int,
	val recentReadChapters: List<MangaChapter>,
)

/**
 * Generates and caches strictly spoiler-locked "Where Was I?" story recaps in Room (`manga_ai_recaps`).
 *
 * ### Strict Spoiler Quarantine Guarantee
 * - Only chapters at or before the user's stopping `history.chapterId` (`index <= pausedIndex`) AND
 *   whose canonical chapter number does not exceed `pausedCanonicalNumber` are ever visible to the
 *   prompt builder or local extractive fallback.
 * - Unread future chapter titles, future volume/arc labels, and total series chapter counts beyond
 *   the stopping point are NEVER included in any prompt payload.
 */
class WhereWasIRecapUseCase @Inject constructor(
	private val recapDao: MangaAiRecapDao,
	private val aiRouter: PluggableAiRouter,
) {

	fun observeRecap(mangaId: Long): Flow<MangaAiRecapEntity?> = recapDao.observeRecap(mangaId)

	suspend fun setDismissed(mangaId: Long, dismissed: Boolean) {
		withContext(Dispatchers.IO) {
			recapDao.setDismissed(mangaId, dismissed)
		}
	}

	suspend fun getOrGenerateRecap(
		details: MangaDetails,
		selectedBranch: String?,
		history: MangaHistory,
		forceRegenerate: Boolean = false,
	): Result<MangaAiRecapEntity?> = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			if (!aiRouter.configFlow.value.masterAiEnabled) {
				return@runCatchingCancellable null
			}
			val manga = details.toManga()
			val candidateChapters = details.chapters[selectedBranch]
				?.takeIf { branchList -> branchList.any { it.id == history.chapterId } }
				?: details.allChapters
			if (candidateChapters.isEmpty()) {
				return@runCatchingCancellable null
			}

			val quarantined = quarantineReadChapters(
				mangaTitle = manga.title,
				chapters = candidateChapters,
				pausedChapterId = history.chapterId,
			) ?: return@runCatchingCancellable null

			val pausedChapter = quarantined.pausedChapter
			val cached = recapDao.getRecap(manga.id)
			if (
				!forceRegenerate &&
				cached != null &&
				cached.upToChapterId == pausedChapter.id &&
				cached.summaryText.isNotBlank()
			) {
				return@runCatchingCancellable cached
			}

			val cleanSynopsis = sanitizeHtmlDescription(details.description?.toString().orEmpty())
			val prompt = buildSpoilerLockedPrompt(
				manga = manga,
				cleanSynopsis = cleanSynopsis,
				quarantined = quarantined,
				pausedPage = history.page,
			)

			val resolvedChapterLabel = pausedChapter.title?.takeIf { it.isNotBlank() }
				?: "Chapter ${formatChapterNumber(quarantined.pausedCanonicalNumber)}"

			val (summaryText, attribution) = aiRouter.generateText(
				systemInstruction = SPOILER_LOCK_SYSTEM_INSTRUCTION,
				userPrompt = prompt,
				temperature = 0.25f,
				maxOutputTokens = 280,
			).fold(
				onSuccess = { out -> out.text.trim() to out.backendAttribution },
				onFailure = { err ->
					if (err is LocalExtractiveFallbackException) {
						val localSummary = buildDeterministicReadingTrail(
							manga = manga,
							cleanSynopsis = cleanSynopsis,
							quarantined = quarantined,
							pausedPage = history.page,
						)
						localSummary to aiRouter.configFlow.value.activeAttributionBadge()
					} else {
						throw err
					}
				},
			)

			val entity = MangaAiRecapEntity(
				mangaId = manga.id,
				upToChapterId = pausedChapter.id,
				upToChapterNumber = quarantined.pausedCanonicalNumber,
				upToChapterTitle = resolvedChapterLabel,
				summaryText = summaryText,
				backendUsed = attribution,
				// Advancing to a new chapter or explicitly regenerating always expands the card
				isDismissed = false,
				updatedAt = System.currentTimeMillis(),
			)
			recapDao.upsert(entity)
			entity
		}
	}

	companion object {

		internal const val MAX_TRAIL_CHAPTERS = 12

		internal const val SPOILER_LOCK_SYSTEM_INSTRUCTION =
			"You are Nemuri's spoiler-locked manga & light novel reading companion. " +
				"Write a concise, atmospheric 3-bullet 'Where Was I?' memory refresher using ONLY " +
				"the premise and read chapter titles provided. " +
				"STRICT SPOILER RULE: NEVER mention, foreshadow, guess, or allude to any plot twist, " +
				"character fate, arc name, or event occurring after the reader's stopping chapter, " +
				"even if you recognize the series from pretraining knowledge. Keep under 95 words."

		/**
		 * Pure spoiler-quarantine filter. Exposed `internal` for unit testing.
		 *
		 * Guarantees:
		 * 1. Slices strictly to `0..pausedIndex` (`history.chapterId`).
		 * 2. Drops any chapter whose canonical number exceeds `pausedCanonicalNumber + 0.001f`,
		 *    preventing mis-ordered source feeds from leaking higher-numbered future chapters.
		 */
		internal fun quarantineReadChapters(
			mangaTitle: String,
			chapters: List<MangaChapter>,
			pausedChapterId: Long,
		): QuarantinedRecapContext? {
			val pausedIndex = chapters.indexOfFirst { it.id == pausedChapterId }
			if (pausedIndex < 0) return null

			val pausedChapter = chapters[pausedIndex]
			val pausedNumber = NekoChapterCanonicalizer.parseChapterNumber(
				mangaTitle = mangaTitle,
				chapterTitle = pausedChapter.title,
				chapterNumber = pausedChapter.number,
			)

			val prefix = chapters.subList(0, pausedIndex + 1)
			val strictlyRead = prefix.filter { ch ->
				if (ch.id == pausedChapterId) return@filter true
				if (pausedNumber < 0f) return@filter true
				val chNum = NekoChapterCanonicalizer.parseChapterNumber(
					mangaTitle = mangaTitle,
					chapterTitle = ch.title,
					chapterNumber = ch.number,
				)
				chNum < 0f || chNum <= pausedNumber + 0.001f
			}

			val effectiveNumber = if (pausedNumber >= 0f) {
				pausedNumber
			} else {
				strictlyRead.size.toFloat()
			}

			return QuarantinedRecapContext(
				pausedChapter = pausedChapter,
				pausedCanonicalNumber = effectiveNumber,
				readChapterCount = strictlyRead.size,
				recentReadChapters = strictlyRead.takeLast(MAX_TRAIL_CHAPTERS),
			)
		}

		/**
		 * Pure prompt builder. Exposed `internal` so unit tests can assert zero future chapter leakage.
		 */
		internal fun buildSpoilerLockedPrompt(
			manga: Manga,
			cleanSynopsis: String,
			quarantined: QuarantinedRecapContext,
			pausedPage: Int,
		): String {
			val pausedNumLabel = formatChapterNumber(quarantined.pausedCanonicalNumber)
			val tagsLine = manga.tags.take(6).joinToString(", ") { it.title }.ifBlank { "Unspecified" }
			val synopsisSnippet = cleanSynopsis.take(550).ifBlank { "No series synopsis provided." }
			val trailLines = quarantined.recentReadChapters.joinToString("\n") { ch ->
				val chNum = NekoChapterCanonicalizer.parseChapterNumber(
					mangaTitle = manga.title,
					chapterTitle = ch.title,
					chapterNumber = ch.number,
				)
				val numPrefix = if (chNum >= 0f) "Ch. ${formatChapterNumber(chNum)}" else "Chapter"
				val cleanTitle = sanitizeChapterTitleForRecap(ch.title, chNum)
				if (cleanTitle.isNotBlank()) "- $numPrefix: $cleanTitle" else "- $numPrefix"
			}
			val pageNote = if (pausedPage > 0) " (Page ${pausedPage + 1})" else ""

			return buildString {
				appendLine("Series Title: ${manga.title}")
				appendLine("Genres: $tagsLine")
				appendLine("HARD SPOILER BOUNDARY: Chapter $pausedNumLabel$pageNote (${quarantined.readChapterCount} chapters read so far).")
				appendLine()
				appendLine("Series Premise:")
				appendLine(synopsisSnippet)
				appendLine()
				appendLine("Read Chapter Trail (strictly up to Chapter $pausedNumLabel):")
				appendLine(trailLines)
				appendLine()
				append("Write a 3-bullet spoiler-free refresher ('• ') grounding the reader right at Chapter $pausedNumLabel.")
			}
		}

		internal fun buildDeterministicReadingTrail(
			manga: Manga,
			cleanSynopsis: String,
			quarantined: QuarantinedRecapContext,
			pausedPage: Int,
		): String {
			val pausedNumLabel = formatChapterNumber(quarantined.pausedCanonicalNumber)
			val pausedTitle = sanitizeChapterTitleForRecap(
				quarantined.pausedChapter.title,
				quarantined.pausedCanonicalNumber,
			)
			val pageSuffix = if (pausedPage > 0) ", page ${pausedPage + 1}" else ""
			val checkpointBullet = if (pausedTitle.isNotBlank()) {
				"• Checkpoint: Paused at Chapter $pausedNumLabel (\"$pausedTitle\"$pageSuffix) with ${quarantined.readChapterCount} read chapter(s) completed."
			} else {
				"• Checkpoint: Paused at Chapter $pausedNumLabel$pageSuffix with ${quarantined.readChapterCount} read chapter(s) completed."
			}

			val priorTitles = quarantined.recentReadChapters
				.dropLast(1)
				.takeLast(3)
				.map { ch ->
					val num = NekoChapterCanonicalizer.parseChapterNumber(manga.title, ch.title, ch.number)
					val lbl = if (num >= 0f) "Ch. ${formatChapterNumber(num)}" else "Ch."
					val t = sanitizeChapterTitleForRecap(ch.title, num)
					if (t.isNotBlank()) "$lbl ($t)" else lbl
				}
			val trailBullet = if (priorTitles.isNotEmpty()) {
				"• Recent Trail: ${priorTitles.joinToString(" → ")} → Ch. $pausedNumLabel."
			} else {
				"• Opening Arc: Currently at your initial reading checkpoint on ${manga.title}."
			}

			val firstSentence = cleanSynopsis
				.split(Regex("(?<=[.!?])\\s+"))
				.firstOrNull { it.length >= 24 }
				?.take(160)
				?.trim()
			val premiseBullet = if (!firstSentence.isNullOrBlank()) {
				"• Premise Anchor: $firstSentence"
			} else {
				val genres = manga.tags.take(4).joinToString(", ") { it.title }
				if (genres.isNotBlank()) "• Series Focus: $genres." else "• Spoiler Lock: Zero future chapters exposed."
			}

			return listOf(checkpointBullet, trailBullet, premiseBullet).joinToString("\n")
		}

		fun formatChapterNumber(number: Float): String {
			if (number < 0f || !number.isFinite()) return "?"
			val roundedInt = number.roundToInt()
			return if (kotlin.math.abs(number - roundedInt.toFloat()) < 0.005f) {
				roundedInt.toString()
			} else {
				String.format(Locale.US, "%.1f", number).trimEnd('0').trimEnd('.')
			}
		}

		private fun sanitizeChapterTitleForRecap(rawTitle: String?, canonicalNum: Float): String {
			val t = rawTitle?.trim().orEmpty()
			if (t.isBlank()) return ""
			val numStr = formatChapterNumber(canonicalNum)
			if (
				t.equals("Chapter $numStr", ignoreCase = true) ||
				t.equals("Ch. $numStr", ignoreCase = true) ||
				t.equals("Ch $numStr", ignoreCase = true) ||
				t.equals("Episode $numStr", ignoreCase = true) ||
				t == numStr
			) {
				return ""
			}
			return t
		}

		private fun sanitizeHtmlDescription(raw: String): String {
			if (raw.isBlank()) return ""
			return raw
				.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
				.replace(Regex("<[^>]+>"), " ")
				.replace("&quot;", "\"")
				.replace("&amp;", "&")
				.replace("&#39;", "'")
				.replace("&rsquo;", "'")
				.replace("&nbsp;", " ")
				.replace(Regex("\\s+"), " ")
				.trim()
		}
	}
}
