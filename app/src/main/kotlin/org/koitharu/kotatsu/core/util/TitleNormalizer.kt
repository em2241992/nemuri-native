package org.koitharu.kotatsu.core.util

import java.text.Normalizer
import java.util.Locale

object TitleNormalizer {

	private val bracketedNoiseRegex = Regex("""\[[^\]]*]|\([^\)]*(?:official|scan|ver|color|uncensored|colored|hd|raw|eng|en)[^\)]*\)""", RegexOption.IGNORE_CASE)
	private val nonAlphaNumericRegex = Regex("""[^\p{L}\p{N}\s]""")
	private val multiSpaceRegex = Regex("""\s+""")

	private val stopSuffixes = listOf(
		"official",
		"colored",
		"full color",
		"digital colored comics",
		"webtoon",
		"manhwa",
		"manhua",
		"manga",
	)

	fun normalize(rawTitle: String): String {
		if (rawTitle.isBlank()) return ""
		var text = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC)
			.lowercase(Locale.ROOT)
		text = bracketedNoiseRegex.replace(text, " ")
		text = nonAlphaNumericRegex.replace(text, " ")
		text = multiSpaceRegex.replace(text, " ").trim()
		for (suffix in stopSuffixes) {
			if (text.endsWith(" $suffix") && text.length > suffix.length + 2) {
				text = text.removeSuffix(" $suffix").trim()
			}
		}
		return text
	}

	fun toNormalizedSlug(rawTitle: String): String {
		return normalize(rawTitle).replace(' ', '-')
	}

	/**
	 * Sørensen–Dice bigram similarity in [0.0, 1.0].
	 */
	fun similarity(a: String, b: String): Float {
		val normA = normalize(a)
		val normB = normalize(b)
		if (normA.isEmpty() || normB.isEmpty()) return 0f
		if (normA == normB) return 1f
		if (normA.length < 2 || normB.length < 2) {
			return if (normA == normB) 1f else 0f
		}
		val bigramsA = HashMap<String, Int>(normA.length)
		for (i in 0 until normA.length - 1) {
			val bg = normA.substring(i, i + 2)
			bigramsA[bg] = (bigramsA[bg] ?: 0) + 1
		}
		var intersection = 0
		for (i in 0 until normB.length - 1) {
			val bg = normB.substring(i, i + 2)
			val count = bigramsA[bg] ?: 0
			if (count > 0) {
				bigramsA[bg] = count - 1
				intersection++
			}
		}
		return (2f * intersection) / ((normA.length - 1) + (normB.length - 1)).toFloat()
	}
}
