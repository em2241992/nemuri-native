package org.koitharu.kotatsu.core.util

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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

	/**
	 * Token-set Jaccard similarity in [0.0, 1.0] over normalized words.
	 */
	fun tokenSimilarity(a: String, b: String): Float {
		val normA = normalize(a)
		val normB = normalize(b)
		if (normA.isEmpty() || normB.isEmpty()) return 0f
		if (normA == normB) return 1f
		val tokensA = normA.split(' ').filter { it.isNotBlank() }.toSet()
		val tokensB = normB.split(' ').filter { it.isNotBlank() }.toSet()
		if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
		val intersection = tokensA.intersect(tokensB).size
		val union = tokensA.union(tokensB).size
		return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
	}

	/**
	 * Jaro–Winkler similarity in [0.0, 1.0] over normalized strings.
	 */
	fun jaroWinklerSimilarity(a: String, b: String): Float {
		val s1 = normalize(a)
		val s2 = normalize(b)
		if (s1.isEmpty() || s2.isEmpty()) return 0f
		if (s1 == s2) return 1f

		val len1 = s1.length
		val len2 = s2.length
		val matchDistance = max(0, max(len1, len2) / 2 - 1)
		val s1Matches = BooleanArray(len1)
		val s2Matches = BooleanArray(len2)

		var matches = 0
		for (i in 0 until len1) {
			val start = max(0, i - matchDistance)
			val end = min(i + matchDistance + 1, len2)
			for (j in start until end) {
				if (s2Matches[j]) continue
				if (s1[i] != s2[j]) continue
				s1Matches[i] = true
				s2Matches[j] = true
				matches++
				break
			}
		}
		if (matches == 0) return 0f

		var transpositions = 0
		var k = 0
		for (i in 0 until len1) {
			if (!s1Matches[i]) continue
			while (!s2Matches[k]) k++
			if (s1[i] != s2[k]) transpositions++
			k++
		}

		val m = matches.toFloat()
		val jaro = ((m / len1) + (m / len2) + ((m - transpositions / 2f) / m)) / 3f
		var prefix = 0
		val maxPrefix = min(4, min(len1, len2))
		while (prefix < maxPrefix && s1[prefix] == s2[prefix]) {
			prefix++
		}
		return (jaro + prefix * 0.1f * (1f - jaro)).coerceIn(0f, 1f)
	}

	/**
	 * Composite fuzzy title similarity combining exact normalized equality, token Jaccard,
	 * bigram Sørensen–Dice, and Jaro–Winkler (weighted so short prefixes alone don't false-match).
	 */
	fun fuzzyTitleSimilarity(a: String, b: String): Float {
		val normA = normalize(a)
		val normB = normalize(b)
		if (normA.isEmpty() || normB.isEmpty()) return 0f
		if (normA == normB) return 1f
		if (normA.replace(" ", "") == normB.replace(" ", "")) return 0.99f

		val dice = similarity(normA, normB)
		val token = tokenSimilarity(normA, normB)
		val jw = jaroWinklerSimilarity(normA, normB)
		// Require meaningful lexical overlap so Jaro-Winkler's prefix bonus doesn't over-score unrelated titles
		val blended = 0.45f * jw + 0.35f * dice + 0.20f * token
		return max(token, max(dice, blended)).coerceIn(0f, 1f)
	}
}
