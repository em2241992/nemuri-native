package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

class NekoChapterCanonicalizerTest {

	private data class FakeSource(override val name: String) : MangaSource

	@Test
	fun `maps Ch 14, Chapter 014, Ep 14, and Vol 2 Ch 14 to identical canonical key`() {
		val mangaTitle = "Solo Leveling"
		val k1 = NekoChapterCanonicalizer.toCanonicalKey(mangaTitle, "Ch. 14", -1f)
		val k2 = NekoChapterCanonicalizer.toCanonicalKey(mangaTitle, "Chapter 014", -1f)
		val k3 = NekoChapterCanonicalizer.toCanonicalKey(mangaTitle, "Ep. 14", -1f)
		val k4 = NekoChapterCanonicalizer.toCanonicalKey(mangaTitle, "Vol.2 Ch.14", -1f)
		val k5 = NekoChapterCanonicalizer.toCanonicalKey(mangaTitle, "Vol.2 Ch.14: The Gate", 0f)
		val k6 = NekoChapterCanonicalizer.toCanonicalKey(mangaTitle, "The 3rd Trial", 14f)

		assertEquals("num:1400", k1)
		assertEquals(k1, k2)
		assertEquals(k1, k3)
		assertEquals(k1, k4)
		assertEquals(k1, k5)
		assertEquals(k1, k6)
	}

	@Test
	fun `preserves fractional and alpha subchapters accurately`() {
		assertEquals("num:1450", NekoChapterCanonicalizer.toCanonicalKey("Berserk", "Ch. 14.5", -1f))
		assertEquals("num:1410", NekoChapterCanonicalizer.toCanonicalKey("Berserk", "Chapter 14a", -1f))
		assertEquals("num:1499", NekoChapterCanonicalizer.toCanonicalKey("Berserk", "Ch. 14 Extra", -1f))
	}

	@Test
	fun `deduplicates by source priority and fills missing chapter gaps`() {
		val primarySource = FakeSource("MIHON_1:PrimaryDex")
		val backupHigh = FakeSource("MIHON_2:BackupHigh")
		val backupLow = FakeSource("MIHON_3:BackupLow")

		val primaryManga = Manga(
			id = 100L,
			title = "Omniscient Reader",
			altTitles = emptySet(),
			url = "/manga/orv",
			publicUrl = "",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = primarySource,
			chapters = listOf(
				chapter(1L, "Ch. 1", 1f, primarySource),
				chapter(3L, "Ch. 3", 3f, primarySource),
			),
		)

		val highLink = MergedMangaEntity(
			id = 1L,
			primaryMangaId = 100L,
			mergedSourceName = backupHigh.name,
			mergedMangaId = 200L,
			mergedMangaUrl = "/orv-high",
			mergedTitle = "Omniscient Reader",
			mergedCoverUrl = "",
			priorityOrder = 0,
			enabled = true,
		)
		val highManga = primaryManga.copy(
			id = 200L,
			source = backupHigh,
			chapters = listOf(
				chapter(21L, "Chapter 001", -1f, backupHigh),
				chapter(22L, "Ep. 2", -1f, backupHigh),
			),
		)

		val lowLink = MergedMangaEntity(
			id = 2L,
			primaryMangaId = 100L,
			mergedSourceName = backupLow.name,
			mergedMangaId = 300L,
			mergedMangaUrl = "/orv-low",
			mergedTitle = "Omniscient Reader",
			mergedCoverUrl = "",
			priorityOrder = 1,
			enabled = true,
		)
		val lowManga = primaryManga.copy(
			id = 300L,
			source = backupLow,
			chapters = listOf(
				chapter(32L, "Vol.1 Ch.2", -1f, backupLow),
				chapter(34L, "Vol.1 Ch.4", -1f, backupLow),
			),
		)

		// Instantiate aggregator with dummy collaborators solely to test pure mergeChapterLists()
		val merged = mergeForTest(
			baseManga = primaryManga,
			secondarySources = listOf(lowLink to lowManga, highLink to highManga),
		)

		assertEquals(listOf(1L, 22L, 3L, 34L), merged.map { it.id })
		assertEquals(
			listOf(primarySource, backupHigh, primarySource, backupLow),
			merged.map { it.source },
		)
		assertEquals(listOf(1f, 2f, 3f, 4f), merged.map { it.number })
	}

	private fun chapter(id: Long, title: String, number: Float, source: MangaSource) = MangaChapter(
		id = id,
		title = title,
		number = number,
		volume = 0,
		url = "/ch/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = source.takeUnless { it == UnknownMangaSource } ?: source,
	)

	private fun mergeForTest(
		baseManga: Manga,
		secondarySources: List<Pair<MergedMangaEntity, Manga>>,
	): List<MangaChapter> {
		return MergedChapterAggregator.mergeChapterListsStatic(baseManga, secondarySources)
	}
}
