package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.util.TitleNormalizer
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

class WhereWasIRecapSpoilerLockTest {

	private data class FakeSource(override val name: String) : MangaSource

	@Test
	fun `strictly quarantines unread future chapters from prompt and local trail`() {
		val src = FakeSource("MIHON_1:TestDex")
		val chapters = listOf(
			chapter(101L, "Ch. 1 - The Awakening", 1f, src),
			chapter(102L, "Ch. 2 - Double Dungeon", 2f, src),
			chapter(103L, "Ch. 3 - Instant Dungeon Key", 3f, src),
			chapter(104L, "Ch. 4 - MAJOR SPOILER: Jeju Island Monarch Betrayal", 4f, src),
			chapter(105L, "Ch. 5 - FINAL ARC SPOILER: Shadow Sovereign Ascension", 5f, src),
		)
		val manga = Manga(
			id = 999L,
			title = "Solo Leveling",
			altTitles = emptySet(),
			url = "/solo-leveling",
			publicUrl = "",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = src,
			chapters = chapters,
		)

		val quarantined = WhereWasIRecapUseCase.quarantineReadChapters(
			mangaTitle = manga.title,
			chapters = chapters,
			pausedChapterId = 103L,
		)
		assertNotNull(quarantined)
		requireNotNull(quarantined)

		assertEquals(3f, quarantined.pausedCanonicalNumber, 0.001f)
		assertEquals(3, quarantined.readChapterCount)
		assertEquals(listOf(101L, 102L, 103L), quarantined.recentReadChapters.map { it.id })

		val prompt = WhereWasIRecapUseCase.buildSpoilerLockedPrompt(
			manga = manga,
			cleanSynopsis = "Weakest hunter Sung Jinwoo enters a mysterious double dungeon.",
			quarantined = quarantined,
			pausedPage = 14,
		)
		assertTrue(prompt.contains("Chapter 3 (Page 15)"))
		assertTrue(prompt.contains("Instant Dungeon Key"))
		assertFalse(prompt.contains("Jeju Island"))
		assertFalse(prompt.contains("Monarch Betrayal"))
		assertFalse(prompt.contains("Shadow Sovereign"))
		assertFalse(prompt.contains("Ch. 4"))
		assertFalse(prompt.contains("Ch. 5"))

		val deterministicTrail = WhereWasIRecapUseCase.buildDeterministicReadingTrail(
			manga = manga,
			cleanSynopsis = "Weakest hunter Sung Jinwoo enters a mysterious double dungeon.",
			quarantined = quarantined,
			pausedPage = 14,
		)
		assertTrue(deterministicTrail.contains("Paused at Chapter 3"))
		assertFalse(deterministicTrail.contains("Jeju Island"))
		assertFalse(deterministicTrail.contains("Shadow Sovereign"))
	}

	@Test
	fun `blocks mis-ordered higher-numbered future chapters appearing before paused index`() {
		val src = FakeSource("MIHON_2:MisorderedSource")
		val chapters = listOf(
			chapter(201L, "Chapter 1: Prologue", 1f, src),
			// Broken source feed placed Ch. 99 preview ahead of Ch. 2
			chapter(299L, "Chapter 99: Epilogue Finale Spoiler", 99f, src),
			chapter(202L, "Chapter 2: First Step", 2f, src),
			chapter(203L, "Chapter 3: Future Chapter", 3f, src),
		)

		val quarantined = WhereWasIRecapUseCase.quarantineReadChapters(
			mangaTitle = "Tower Chronicle",
			chapters = chapters,
			pausedChapterId = 202L,
		)
		requireNotNull(quarantined)

		assertEquals(listOf(201L, 202L), quarantined.recentReadChapters.map { it.id })
		assertFalse(quarantined.recentReadChapters.any { it.title?.contains("Epilogue Finale") == true })
	}

	@Test
	fun `fuzzy title similarity matches library duplicates while rejecting distinct series`() {
		val highMatch = TitleNormalizer.fuzzyTitleSimilarity(
			"Omniscient Reader's Viewpoint",
			"Omniscient Readers Viewpoint (Official)",
		)
		val unrelated = TitleNormalizer.fuzzyTitleSimilarity(
			"Berserk",
			"Bleach",
		)
		assertTrue("Expected >= 0.86 similarity, got $highMatch", highMatch >= 0.86f)
		assertTrue("Expected < 0.50 similarity, got $unrelated", unrelated < 0.50f)
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
		source = source,
	)
}
