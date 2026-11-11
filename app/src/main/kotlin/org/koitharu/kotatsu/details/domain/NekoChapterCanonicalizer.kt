package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.util.TitleNormalizer
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Port of Neko's `ChapterRecognition` + `ParseChapterNameUseCase` chapter canonicalization rules.
 *
 * Maps variant chapter notations across DropSauce, Mihon `.apk`, and LNReader sources — such as
 * `"Ch. 14"`, `"Chapter 014"`, `"Ep. 14"`, `"Episode 14"`, `"#14"`, and `"Vol.2 Ch.14"` — onto
 * identical canonical chapter numbers (`14f`) and deterministic deduplication keys (`"num:1400"`).
 */
object NekoChapterCanonicalizer {

	private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\s*\.?\s*(?:extra|omake|special)\b|\.?[a-z]+)?"""

	/** Matches `(ch|chap|chapter|ep|episode|#)` prefixes used across manga, webtoon, and novel sources. */
	private val basicChapterRegex = Regex(
		"""(?<![a-z])(ch|chap|chapter|ep|episode)\s*\.?\s*$NUMBER_PATTERN|(?<!\p{L})#\s*$NUMBER_PATTERN""",
		RegexOption.IGNORE_CASE,
	)

	/** Explicit non-zero chapter marker used to repair parsers that default unset `number` to `0f`. */
	private val explicitNonZeroChapterRegex = Regex(
		"""(?<![a-z])(ch|chap|chapter|ep|episode)\s*\.?\s*0*[1-9][0-9]*""",
		RegexOption.IGNORE_CASE,
	)

	/** Matches bare chapter numbers when no explicit prefix is present. */
	private val bareNumberRegex = Regex(NUMBER_PATTERN, RegexOption.IGNORE_CASE)

	/** Strips `[scanlator]` or `(scanlator)` leading/trailing tags and `v/ver/vol/volume/season/s` prefixes. */
	private val bracketTagRegex = Regex("""\[[^\]]*]""")
	private val unwantedVolumeOrSeasonRegex = Regex(
		"""\b(?<![a-z])(v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+(\.[0-9]+)?""",
		RegexOption.IGNORE_CASE,
	)
	private val unwantedWhitespaceRegex = Regex("""\s*([-\u2013\u2014:_])\s*""")
	private val multiWhitespaceRegex = Regex("""\s+""")

	/**
	 * Resolves a canonical chapter number (`>= 0f`) or `-1f` when the chapter is unnumbered.
	 */
	fun parseChapterNumber(
		mangaTitle: String,
		chapterTitle: String?,
		chapterNumber: Float,
	): Float {
		if (chapterNumber > 0f && chapterNumber.isFinite()) {
			return normalizeZero(chapterNumber)
		}
		if (chapterNumber == 0f && (chapterTitle.isNullOrBlank() || !explicitNonZeroChapterRegex.containsMatchIn(chapterTitle))) {
			return 0f
		}
		if (chapterTitle.isNullOrBlank()) {
			return if (chapterNumber >= 0f && chapterNumber.isFinite()) normalizeZero(chapterNumber) else -1f
		}
		val parsed = parseFromTitle(mangaTitle, chapterTitle)
		if (parsed >= 0f) {
			return parsed
		}
		return if (chapterNumber == 0f) 0f else -1f
	}

	/**
	 * Deterministic canonical key for cross-source chapter deduplication and Reader failover matching.
	 * Examples:
	 * - `"Ch. 14"`, `"Chapter 014"`, `"Ep. 14"`, `"Vol.2 Ch.14"` -> `"num:1400"`
	 * - `"Ch. 14.5"` -> `"num:1450"`
	 */
	fun toCanonicalKey(
		mangaTitle: String,
		chapter: MangaChapter,
	): String = toCanonicalKey(
		mangaTitle = mangaTitle,
		chapterTitle = chapter.title,
		chapterNumber = chapter.number,
		fallbackId = chapter.url,
	)

	fun toCanonicalKey(
		mangaTitle: String,
		chapterTitle: String?,
		chapterNumber: Float,
		fallbackId: String = "",
	): String {
		val canonicalNumber = parseChapterNumber(mangaTitle, chapterTitle, chapterNumber)
		if (canonicalNumber >= 0f) {
			val scaled = (canonicalNumber * 100f).roundToInt()
			return "num:$scaled"
		}
		val normalizedTitle = TitleNormalizer.normalize(chapterTitle.orEmpty())
			.ifEmpty { fallbackId.trim().lowercase(Locale.ROOT) }
		return "title:$normalizedTitle"
	}

	private fun parseFromTitle(mangaTitle: String, rawChapterTitle: String): Float {
		var name = rawChapterTitle.lowercase(Locale.ROOT)
			.replace(',', '.')

		// Remove bracketed scanlator tags first (e.g. "[Group] Vol.2 Ch.14")
		name = bracketTagRegex.replace(name, " ")

		// Normalize hyphen/colon whitespace like Neko's ChapterRecognition
		name = unwantedWhitespaceRegex.replace(name, " ")

		// Strip leading manga title if repeated in the chapter name (e.g. "Berserk Chapter 014")
		val cleanMangaTitle = mangaTitle.trim().lowercase(Locale.ROOT)
		if (cleanMangaTitle.length >= 3 && name.startsWith(cleanMangaTitle)) {
			name = name.removePrefix(cleanMangaTitle).trim()
		}

		// Strip volume / version / season numbers so "Vol.2 Ch.14" or "S2 Ep. 14" never resolves to 2
		name = unwantedVolumeOrSeasonRegex.replace(name, " ")
		name = multiWhitespaceRegex.replace(name, " ").trim()

		// Priority 1: Explicit ch / chap / chapter / ep / episode / # prefix
		basicChapterRegex.find(name)?.let { match ->
			extractNumberFromMatch(match)?.let { return it }
		}

		// Priority 2: First remaining number token
		bareNumberRegex.find(name)?.let { match ->
			parseMatchGroups(
				initialStr = match.groups[1]?.value,
				subChapterDecimal = match.groups[2]?.value,
				subChapterAlpha = match.groups[3]?.value,
			)?.let { return it }
		}

		return -1f
	}

	private fun extractNumberFromMatch(match: MatchResult): Float? {
		// Groups 2,3,4 correspond to `(ch|chap|chapter|ep|episode)` branch; groups 5,6,7 to `#` branch
		val initial = match.groups[2]?.value ?: match.groups[5]?.value
		val decimal = match.groups[3]?.value ?: match.groups[6]?.value
		val alpha = match.groups[4]?.value ?: match.groups[7]?.value
		return parseMatchGroups(initial, decimal, alpha)
	}

	private fun parseMatchGroups(
		initialStr: String?,
		subChapterDecimal: String?,
		subChapterAlpha: String?,
	): Float? {
		val initial = initialStr?.toFloatOrNull() ?: return null
		val addition = when {
			!subChapterDecimal.isNullOrEmpty() -> subChapterDecimal.toFloatOrNull() ?: 0f
			!subChapterAlpha.isNullOrEmpty() -> parseAlphaPostfix(subChapterAlpha)
			else -> 0f
		}
		return normalizeZero(initial + addition)
	}

	/**
	 * Matches Neko's alpha-postfix semantics (`extra` -> `.99`, `omake` -> `.98`, `special` -> `.97`,
	 * `.a` / `a` -> `.1`, `b` -> `.2`, etc.).
	 */
	private fun parseAlphaPostfix(postfix: String): Float {
		val cleaned = postfix.trim().trimStart('.').trim().lowercase(Locale.ROOT)
		if (cleaned.isEmpty()) return 0f
		return when {
			cleaned.startsWith("extra") -> 0.99f
			cleaned.startsWith("omake") -> 0.98f
			cleaned.startsWith("special") -> 0.97f
			else -> {
				val code = cleaned[0].code - ('a'.code - 1)
				if (code in 1..9) code / 10f else 0f
			}
		}
	}

	private fun normalizeZero(value: Float): Float = if (value == -0f) 0f else value
}
