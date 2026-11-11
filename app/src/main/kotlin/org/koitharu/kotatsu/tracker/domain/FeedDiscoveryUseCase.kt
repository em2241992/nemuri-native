package org.koitharu.kotatsu.tracker.domain

import dagger.Reusable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.TitleNormalizer
import org.koitharu.kotatsu.core.util.ext.takeMostFrequent
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import java.util.Locale
import javax.inject.Inject

@Reusable
class FeedDiscoveryUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val favouritesRepository: FavouritesRepository,
	private val historyRepository: HistoryRepository,
	private val suggestionRepository: SuggestionRepository,
	private val settings: AppSettings,
) {

	data class HubDiscoveryResult(
		val popular: List<Manga>,
		val latest: List<Manga>,
		val hasEnabledSources: Boolean,
	)

	data class DiscoveredAffinityManga(
		val manga: Manga,
		val matchedTags: List<String>,
		val score: Float,
	)

	data class ForYouDiscoveryResult(
		val affinityTags: List<String>,
		val items: List<DiscoveredAffinityManga>,
		val isLibraryAndHistoryEmpty: Boolean,
	)

	suspend fun fetchHubDiscovery(): HubDiscoveryResult {
		val sources = selectDiscoverySources(maxSources = MAX_HUB_SOURCES)
		if (sources.isEmpty()) {
			return HubDiscoveryResult(
				popular = emptyList(),
				latest = emptyList(),
				hasEnabledSources = false,
			)
		}
		return supervisorScope {
			val popularDeferred = sources.map { source ->
				async {
					runCatchingCancellable {
						withTimeout(SOURCE_TIMEOUT_MS) {
							fetchSortedFromSource(source, POPULAR_SORT_PRIORITY)
						}
					}.getOrDefault(emptyList())
				}
			}
			val latestDeferred = sources.map { source ->
				async {
					runCatchingCancellable {
						withTimeout(SOURCE_TIMEOUT_MS) {
							fetchSortedFromSource(source, LATEST_SORT_PRIORITY)
						}
					}.getOrDefault(emptyList())
				}
			}
			val popularBuckets = popularDeferred.awaitAll()
			val latestBuckets = latestDeferred.awaitAll()
			HubDiscoveryResult(
				popular = interleaveAndDeduplicate(popularBuckets, limit = MAX_SWIMLANE_ITEMS),
				latest = interleaveAndDeduplicate(latestBuckets, limit = MAX_SWIMLANE_ITEMS),
				hasEnabledSources = true,
			)
		}
	}

	suspend fun fetchForYouDiscovery(): ForYouDiscoveryResult {
		val favourites = runCatchingCancellable { favouritesRepository.getAllManga() }.getOrDefault(emptyList())
		val recentHistory = runCatchingCancellable { historyRepository.getList(0, 40) }.getOrDefault(emptyList())
		val seed = (favourites.take(40) + recentHistory).distinctById()
		if (seed.isEmpty()) {
			return ForYouDiscoveryResult(
				affinityTags = emptyList(),
				items = emptyList(),
				isLibraryAndHistoryEmpty = true,
			)
		}

		val libraryIds = favourites.mapTo(HashSet(favourites.size)) { it.id }
		val libraryTitles = favourites.mapNotNullTo(HashSet(favourites.size)) { manga ->
			TitleNormalizer.normalize(manga.title).takeIf { it.isNotEmpty() }
		}

		val blacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, TAG_MATCH_THRESHOLD)
		val weightedTagTitles = buildList {
			// Weight library series slightly higher than transient browsing history
			for (fav in favourites.take(40)) {
				for (tag in fav.tags) {
					if (tag !in blacklist && tag.title.isNotBlank()) {
						add(tag.title.trim())
						add(tag.title.trim())
					}
				}
			}
			for (hist in recentHistory) {
				for (tag in hist.tags) {
					if (tag !in blacklist && tag.title.isNotBlank()) {
						add(tag.title.trim())
					}
				}
			}
		}
		val topTags = weightedTagTitles.takeMostFrequent(MAX_AFFINITY_TAGS)
		val sources = selectDiscoverySources(maxSources = MAX_HUB_SOURCES)

		val liveFetched: List<Pair<Manga, String?>> = if (sources.isNotEmpty() && topTags.isNotEmpty()) {
			supervisorScope {
				sources.map { source ->
					async {
						runCatchingCancellable {
							withTimeout(SOURCE_TIMEOUT_MS) {
								fetchTagAffinityFromSource(source, topTags, blacklist)
							}
						}.getOrDefault(emptyList())
					}
				}.awaitAll().flatten()
			}
		} else if (sources.isNotEmpty()) {
			// Seed series exist in Room without parsed tags yet — query popular lists and exclude library
			supervisorScope {
				sources.map { source ->
					async {
						runCatchingCancellable {
							withTimeout(SOURCE_TIMEOUT_MS) {
								fetchSortedFromSource(source, POPULAR_SORT_PRIORITY).map { it to null }
							}
						}.getOrDefault(emptyList())
					}
				}.awaitAll().flatten()
			}
		} else {
			emptyList()
		}

		val cachedSuggestions = runCatchingCancellable {
			suggestionRepository.observeAll().first()
		}.getOrDefault(emptyList()).map { it to null }

		val hideNsfw = settings.isNsfwContentDisabled || settings.isSuggestionsExcludeNsfw
		val seenKeys = HashSet<String>()
		val scored = ArrayList<DiscoveredAffinityManga>()

		for ((manga, queriedTag) in (liveFetched + cachedSuggestions)) {
			if (manga.title.isBlank()) continue
			if (hideNsfw && manga.isNsfw()) continue
			if (blacklist.isNotEmpty() && manga in blacklist) continue
			if (manga.id in libraryIds) continue
			val normalizedTitle = TitleNormalizer.normalize(manga.title)
			if (normalizedTitle.isNotEmpty() && normalizedTitle in libraryTitles) continue
			val dedupeKey = normalizedTitle.ifEmpty { "${manga.source.name}:${manga.url}" }
			if (!seenKeys.add(dedupeKey)) continue

			val matched = LinkedHashSet<String>(4)
			if (!queriedTag.isNullOrBlank()) {
				matched.add(queriedTag)
			}
			for (topTag in topTags) {
				if (manga.tags.any { it.title.almostEquals(topTag, TAG_MATCH_THRESHOLD) }) {
					matched.add(topTag)
				}
			}
			val overlapScore = matched.foldIndexed(0f) { _, acc, tagLabel ->
				val rankIndex = topTags.indexOfFirst { it.equals(tagLabel, ignoreCase = true) }
				val weight = if (rankIndex >= 0) (topTags.size - rankIndex).toFloat() else 1f
				acc + weight
			} + if (manga.rating > 0f) manga.rating else 0f

			scored += DiscoveredAffinityManga(
				manga = manga,
				matchedTags = matched.take(3),
				score = overlapScore,
			)
		}

		scored.sortByDescending { it.score }
		return ForYouDiscoveryResult(
			affinityTags = topTags,
			items = scored.take(MAX_FOR_YOU_ITEMS),
			isLibraryAndHistoryEmpty = false,
		)
	}

	private suspend fun selectDiscoverySources(maxSources: Int): List<MangaSource> {
		sourcesRepository.ensureMihonSourcesLoaded()
		val hideNsfw = settings.isNsfwContentDisabled
		val enabled = sourcesRepository.getEnabledSources()
			.filterNot { hideNsfw && it.isNsfw() }
		if (enabled.isEmpty()) return emptyList()

		val imageSources = enabled.filterNot { it.isNovelSource }
		val pool = imageSources.ifEmpty { enabled }
		val pinned = sourcesRepository.getPinnedSources()

		val ordered = LinkedHashSet<MangaSource>(maxSources)
		// 1. Prioritize MangaDex if installed & enabled
		pool.firstOrNull(::isMangaDexSource)?.let(ordered::add)
		// 2. Pinned sources
		for (source in pool) {
			if (ordered.size >= maxSources) break
			if (source in pinned) ordered.add(source)
		}
		// 3. Top enabled sources by usage/order
		for (source in pool) {
			if (ordered.size >= maxSources) break
			ordered.add(source)
		}
		return ordered.toList()
	}

	private fun isMangaDexSource(source: MangaSource): Boolean {
		if (source.name.contains("mangadex", ignoreCase = true)) return true
		val mihon = source as? MihonMangaSource ?: return false
		return mihon.pkgName.contains("mangadex", ignoreCase = true) ||
			mihon.catalogueSource.name.equals("MangaDex", ignoreCase = true)
	}

	private suspend fun fetchSortedFromSource(
		source: MangaSource,
		preferredOrders: List<SortOrder>,
	): List<Manga> {
		val repo = mangaRepositoryFactory.create(source)
		val available = repo.sortOrders
		val order = preferredOrders.firstOrNull { it in available } ?: repo.defaultSortOrder
		val hideNsfw = settings.isNsfwContentDisabled
		return repo.getList(offset = 0, order = order, filter = null)
			.asSequence()
			.filter { it.title.isNotBlank() }
			.filterNot { hideNsfw && it.isNsfw() }
			.take(PER_SOURCE_LIMIT)
			.toList()
	}

	private suspend fun fetchTagAffinityFromSource(
		source: MangaSource,
		topTags: List<String>,
		blacklist: TagsBlacklist,
	): List<Pair<Manga, String?>> {
		val repo = mangaRepositoryFactory.create(source)
		val availableOrders = repo.sortOrders
		val order = POPULAR_SORT_PRIORITY.firstOrNull { it in availableOrders } ?: repo.defaultSortOrder
		val availableTags = runCatchingCancellable {
			repo.getFilterOptions().availableTags
		}.getOrDefault(emptySet())

		val matchedPairs = topTags.mapNotNull { userTagTitle ->
			val repoTag = availableTags.firstOrNull { candidate ->
				candidate !in blacklist && candidate.title.almostEquals(userTagTitle, TAG_MATCH_THRESHOLD)
			}
			if (repoTag != null) userTagTitle to repoTag else null
		}.take(2)

		if (matchedPairs.isNotEmpty()) {
			val combined = ArrayList<Pair<Manga, String?>>(PER_SOURCE_LIMIT)
			for ((userTagLabel, repoTag) in matchedPairs) {
				val list = runCatchingCancellable {
					repo.getList(
						offset = 0,
						order = order,
						filter = MangaListFilter(tags = setOf(repoTag)),
					)
				}.getOrDefault(emptyList())
				list.take(PER_SOURCE_TAG_LIMIT).forEach { manga ->
					combined += manga to userTagLabel
				}
			}
			if (combined.isNotEmpty()) {
				return combined
			}
		}

		// Fallback if extension exposes no static tag catalog: search top genre or popular list
		val primaryTag = topTags.firstOrNull()
		if (primaryTag != null && repo.filterCapabilities.isSearchSupported) {
			val byQuery = runCatchingCancellable {
				repo.getList(
					offset = 0,
					order = order,
					filter = MangaListFilter(query = primaryTag),
				)
			}.getOrDefault(emptyList())
			if (byQuery.isNotEmpty()) {
				return byQuery.take(PER_SOURCE_TAG_LIMIT).map { it to primaryTag }
			}
		}
		return repo.getList(offset = 0, order = order, filter = null)
			.take(PER_SOURCE_TAG_LIMIT)
			.map { it to null }
	}

	private fun interleaveAndDeduplicate(
		buckets: List<List<Manga>>,
		limit: Int,
	): List<Manga> {
		if (buckets.isEmpty()) return emptyList()
		val result = ArrayList<Manga>(limit)
		val seenKeys = HashSet<String>(limit * 2)
		val maxBucketSize = buckets.maxOfOrNull { it.size } ?: 0
		for (index in 0 until maxBucketSize) {
			for (bucket in buckets) {
				if (result.size >= limit) return result
				val item = bucket.getOrNull(index) ?: continue
				val norm = TitleNormalizer.normalize(item.title)
				val key = norm.ifEmpty {
					"${item.source.name.lowercase(Locale.ROOT)}:${item.url}"
				}
				if (seenKeys.add(key)) {
					result += item
				}
			}
		}
		return result
	}

	private companion object {
		const val SOURCE_TIMEOUT_MS = 8_000L
		const val MAX_HUB_SOURCES = 5
		const val PER_SOURCE_LIMIT = 12
		const val PER_SOURCE_TAG_LIMIT = 10
		const val MAX_SWIMLANE_ITEMS = 24
		const val MAX_FOR_YOU_ITEMS = 36
		const val MAX_AFFINITY_TAGS = 8
		const val TAG_MATCH_THRESHOLD = 0.4f

		val POPULAR_SORT_PRIORITY = listOf(
			SortOrder.POPULARITY,
			SortOrder.POPULARITY_TODAY,
			SortOrder.POPULARITY_WEEK,
			SortOrder.POPULARITY_MONTH,
			SortOrder.RATING,
		)

		val LATEST_SORT_PRIORITY = listOf(
			SortOrder.UPDATED,
			SortOrder.NEWEST,
		)
	}
}
