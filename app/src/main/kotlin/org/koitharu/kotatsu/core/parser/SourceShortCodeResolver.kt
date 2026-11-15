package org.koitharu.kotatsu.core.parser

import android.content.Context
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.Locale

/**
 * Resolves compact 2–3 character uppercase short-codes for cover corner pills
 * (e.g., MangaDex -> "MD", MangaPark -> "MP", Bato -> "BT", Local -> "LOC").
 */
object SourceShortCodeResolver {

	fun resolve(source: MangaSource, context: Context? = null): String {
		if (source.isLocal) return "LOC"
		val rawTitle = context?.let { runCatching { source.getTitle(it) }.getOrNull() }
			?.takeIf { it.isNotBlank() }
			?: source.name
		return resolveFromLabel(rawTitle, source.name)
	}

	fun resolveFromLabel(title: String, sourceName: String = title): String {
		val normalized = title.trim().lowercase(Locale.ROOT)
		val byKnown = when {
			"mangadex" in normalized || sourceName.equals("MANGADEX", ignoreCase = true) -> "MD"
			"mangaplus" in normalized || "manga plus" in normalized -> "M+"
			"mangapark" in normalized -> "MP"
			"bato" in normalized -> "BT"
			"comick" in normalized -> "CK"
			"asura" in normalized -> "AS"
			"weebcentral" in normalized || "weeb central" in normalized -> "WC"
			"mangafire" in normalized -> "MF"
			"mangasee" in normalized -> "MS"
			"mangalife" in normalized -> "ML"
			"mangabuddy" in normalized -> "MB"
			"mangakakalot" in normalized -> "MK"
			"manganato" in normalized || "chapmanganato" in normalized -> "MN"
			"mangareader" in normalized -> "MR"
			"reaper" in normalized -> "RP"
			"flame" in normalized -> "FL"
			"toonily" in normalized -> "TN"
			"webtoons" in normalized || "line webtoon" in normalized -> "WT"
			"tapas" in normalized -> "TP"
			"tappytoon" in normalized -> "TT"
			"lezhin" in normalized -> "LZ"
			"dynasty" in normalized -> "DY"
			"cubari" in normalized -> "CB"
			"nhentai" in normalized -> "NH"
			"e-hentai" in normalized || "exhentai" in normalized -> "EH"
			"hitomi" in normalized -> "HT"
			normalized == "local" -> "LOC"
			else -> null
		}
		if (byKnown != null) return byKnown

		val cleaned = title
			.replace(Regex("\\([^)]*\\)"), " ")
			.replace(Regex("[^A-Za-z0-9 ]"), " ")
			.trim()
		if (cleaned.isEmpty()) return "SRC"

		val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (words.size >= 2) {
			return words.take(3).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
		}

		val single = words.first()
		val camelCaps = single.filter { it.isUpperCase() }
		if (camelCaps.length in 2..3) {
			return camelCaps
		}
		return single.take(3).uppercase(Locale.ROOT)
	}
}
