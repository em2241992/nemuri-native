package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupSourcePreferences
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonFloatPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonIntPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonLongPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringSetPreferenceValue
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.mihonChapterId
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRecord
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistry
import org.koitharu.kotatsu.settings.sources.catalog.normalizeExtensionStoreUrl
import org.koitharu.kotatsu.settings.sources.catalog.stableExtensionStoreId
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonBackupManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	@Suppress("unused") private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
	private val mihonExtensionLoader: MihonExtensionLoader,
	private val extensionStoreRegistry: ExtensionStoreRegistry,
) {

	data class Options(
		val libraryEntries: Boolean = true,
		val categories: Boolean = true,
		val history: Boolean = true,
		val appSettings: Boolean = true,
		val sourceSettings: Boolean = true,
		val extensionRepoSettings: Boolean = true,
		val tracking: Boolean = true,
	)

	data class RestoreReport(
		val restoredMangaCount: Int,
		val restoredCategoriesCount: Int = 0,
		val restoredHistoryCount: Int = 0,
		val restoredTrackingCount: Int = 0,
		val restoredMergedLinksCount: Int = 0,
		val missingSources: List<String> = emptyList(),
		val missingTrackers: List<Int> = emptyList(),
	)

	private class RestoreAccumulator {
		val missingSources = LinkedHashSet<String>()
		val missingTrackers = LinkedHashSet<Int>()
		var restoredMangaCount = 0
		var restoredCategoriesCount = 0
		var restoredHistoryCount = 0
		var restoredTrackingCount = 0
		var restoredMergedLinksCount = 0

		fun toReport() = RestoreReport(
			restoredMangaCount = restoredMangaCount,
			restoredCategoriesCount = restoredCategoriesCount,
			restoredHistoryCount = restoredHistoryCount,
			restoredTrackingCount = restoredTrackingCount,
			restoredMergedLinksCount = restoredMergedLinksCount,
			missingSources = missingSources.sorted(),
			missingTrackers = missingTrackers.sorted(),
		)
	}

	private data class PendingRestore(
		val manga: MangaEntity,
		val tags: List<TagEntity>,
		val chapters: List<ChapterEntity>,
		val favourites: List<FavouriteEntity>,
		val history: HistoryEntity?,
		val stats: StatsEntity?,
		val bookmarks: List<BookmarkEntity>,
		val scrobblings: List<ScrobblingEntity>,
		val track: TrackEntity?,
		val mangaPrefs: MangaPrefsEntity?,
		val mergedSecondaryManga: List<MangaEntity>,
		val mergedLinks: List<MergedMangaEntity>,
	)

	/**
	 * Maps Neko / Mihon backup categories onto Kotatsu favourite categories.
	 * Uncategorized (`categories.isEmpty()`) favorite manga always fall back to `Default`.
	 */
	private inner class CategoryResolver(
		private val backupCategories: List<MihonBackupCategory>,
		private val restoreUserCategories: Boolean,
	) {
		private val dao = db.getFavouriteCategoriesDao()
		private val idByOrder = HashMap<Long, Long>()
		private val idByCategoryId = HashMap<Long, Long>()
		private val idByTitleLower = HashMap<String, Long>()
		private var defaultCategoryId: Long = 0L
		var createdOrMatchedCount: Int = 0
			private set

		suspend fun prepare() {
			dao.findAll().forEach { existing ->
				val normalized = if (existing.title.equals("Read later", ignoreCase = true)) {
					dao.update(
						id = existing.categoryId.toLong(),
						title = DEFAULT_CATEGORY_TITLE,
						order = existing.order,
						tracker = existing.track,
						downloadNewChapters = existing.downloadNewChapters,
						onShelf = existing.isVisibleInLibrary,
					)
					DEFAULT_CATEGORY_TITLE
				} else {
					existing.title
				}
				idByTitleLower[normalized.trim().lowercase(Locale.ROOT)] = existing.categoryId.toLong()
			}
			defaultCategoryId = categoryIdForTitle(DEFAULT_CATEGORY_TITLE)

			if (restoreUserCategories) {
				backupCategories.sortedBy { it.order }.forEach { category ->
					val title = category.name.trim()
					if (title.isNotEmpty()) {
						val catId = categoryIdForTitle(title)
						idByOrder[category.order] = catId
						if (category.id > 0L) {
							idByCategoryId[category.id] = catId
						}
						createdOrMatchedCount += 1
					}
				}
			}
		}

		fun resolve(orders: List<Long>): List<Long> {
			if (!restoreUserCategories || orders.isEmpty()) {
				return listOf(defaultCategoryId)
			}
			val ids = orders.mapNotNull { key ->
				idByOrder[key] ?: idByCategoryId[key]
			}.distinct()
			return ids.ifEmpty { listOf(defaultCategoryId) }
		}

		private suspend fun categoryIdForTitle(title: String): Long {
			val lower = title.trim().lowercase(Locale.ROOT)
			idByTitleLower[lower]?.let { return it }
			val id = dao.insert(
				FavouriteCategoryEntity(
					categoryId = 0,
					createdAt = System.currentTimeMillis(),
					sortKey = dao.getNextSortKey(),
					title = title.trim(),
					order = "NEWEST",
					track = true,
					downloadNewChapters = false,
					isVisibleInLibrary = true,
					deletedAt = 0,
				),
			)
			idByTitleLower[lower] = id
			return id
		}
	}

	private val proto = ProtoBuf

	suspend fun analyzeBackup(uri: Uri, options: Options = Options()): RestoreReport = withContext(Dispatchers.IO) {
		mihonExtensionManager.ensureReady()
		val backup = openAndDecodeTachibk(uri)
		val acc = RestoreAccumulator()
		collectMissingSourcesAndTrackers(backup, options, acc)
		acc.restoredMangaCount = backup.backupManga.size
		acc.restoredCategoriesCount = backup.backupCategories.size
		acc.toReport()
	}

	suspend fun restoreBackup(
		uri: Uri,
		options: Options = Options(),
		onProgress: ((done: Int, total: Int) -> Unit)? = null,
	): RestoreReport = withContext(Dispatchers.IO) {
		mihonExtensionManager.ensureReady()
		val decoded = openAndDecodeTachibk(uri)
		restoreDecodedBackup(
			decoded = decoded,
			rawUriForOptionalPrefs = uri,
			options = options,
			onProgress = onProgress,
		)
	}

	suspend fun restoreBackupFromStream(
		inputStream: InputStream,
		options: Options = Options(),
		onProgress: ((done: Int, total: Int) -> Unit)? = null,
	): RestoreReport = withContext(Dispatchers.IO) {
		mihonExtensionManager.ensureReady()
		val decoded = NekoTachibkDecoder.decodeStream(inputStream)
		restoreDecodedBackup(
			decoded = decoded,
			rawUriForOptionalPrefs = null,
			options = options,
			onProgress = onProgress,
		)
	}

	private suspend fun restoreDecodedBackup(
		decoded: DecodedTachibkBackup,
		rawUriForOptionalPrefs: Uri?,
		options: Options,
		onProgress: ((done: Int, total: Int) -> Unit)?,
	): RestoreReport {
		val diagnostics = RestoreAccumulator()
		collectMissingSourcesAndTrackers(decoded, options, diagnostics)

		if (options.libraryEntries || options.history) {
			val categoryResolver = CategoryResolver(
				backupCategories = decoded.backupCategories,
				restoreUserCategories = options.categories,
			)
			db.withTransaction {
				categoryResolver.prepare()
			}
			diagnostics.restoredCategoriesCount = categoryResolver.createdOrMatchedCount

			val total = decoded.backupManga.size
			onProgress?.invoke(0, total)
			val chunks = decoded.backupManga.chunked(RESTORE_BATCH_SIZE)
			var processed = 0
			for (chunk in chunks) {
				currentCoroutineContext().ensureActive()
				val batchPending = chunk.map { item ->
					buildPendingRestore(
						item = item,
						backupSources = decoded.backupSources,
						options = options,
						diagnostics = diagnostics,
						categoryResolver = categoryResolver,
					)
				}
				db.withTransaction {
					persistBatch(batchPending, options, diagnostics)
				}
				processed += chunk.size
				onProgress?.invoke(processed, total)
			}
		}

		if (options.extensionRepoSettings && decoded.backupExtensionRepo.isNotEmpty()) {
			restoreExtensionRepo(decoded.backupExtensionRepo)
		}

		if ((options.appSettings || options.sourceSettings) && rawUriForOptionalPrefs != null) {
			runCatching { decodeMihonPrefsIfPresent(rawUriForOptionalPrefs) }.getOrNull()?.let { prefBackup ->
				if (options.appSettings && prefBackup.backupPreferences.isNotEmpty()) {
					restorePreferences(prefBackup.backupPreferences)
				}
				if (options.sourceSettings && prefBackup.backupSourcePreferences.isNotEmpty()) {
					restoreSourcePreferences(prefBackup.backupSourcePreferences)
				}
			}
		}

		return diagnostics.toReport()
	}

	private fun openAndDecodeTachibk(uri: Uri): DecodedTachibkBackup {
		val stream = context.contentResolver.openInputStream(uri)
			?: throw BadBackupFormatException(null)
		return stream.use { NekoTachibkDecoder.decodeStream(it) }
	}

	private fun decodeMihonPrefsIfPresent(uri: Uri): MihonBackup? {
		val payload = context.contentResolver.openInputStream(uri)?.use { input ->
			val source = input.source().buffer()
			val peeked = source.peek().apply { require(2) }
			val signature = peeked.readShort().toInt()
			when (signature) {
				0x1f8b -> source.gzip().buffer().readByteArray()
				else -> source.readByteArray()
			}
		} ?: return null
		return runCatching {
			proto.decodeFromByteArray(MihonBackup.serializer(), payload)
		}.getOrNull()
	}

	private fun collectMissingSourcesAndTrackers(
		backup: DecodedTachibkBackup,
		options: Options,
		diagnostics: RestoreAccumulator,
	) {
		if (options.libraryEntries) {
			val sourceNamesById = backup.backupSources.associate { it.sourceId to it.name }
			val distinctIds = backup.backupManga.mapTo(LinkedHashSet()) { it.source }
			for (sourceId in distinctIds) {
				if (sourceId <= 0L) continue
				if (NekoTachibkDecoder.isMangaDexSourceId(sourceId)) continue
				val resolved = resolveSourceNameAndTitle(sourceId, backup.backupSources)
				if (resolved.first.startsWith("MIHON_") && mihonExtensionManager.getMihonMangaSourceById(sourceId) == null) {
					val label = sourceNamesById[sourceId]?.takeIf { it.isNotBlank() } ?: sourceId.toString()
					diagnostics.missingSources += label
				}
			}
		}
		if (options.tracking) {
			backup.backupManga
				.asSequence()
				.flatMap { it.tracking.asSequence() }
				.map { it.syncId }
				.filter { it != 0 && mihonTrackerToScrobblerId(it) == null }
				.forEach { diagnostics.missingTrackers += it }
		}
	}

	private suspend fun buildPendingRestore(
		item: DecodedTachibkManga,
		backupSources: List<MihonBackupSource>,
		options: Options,
		diagnostics: RestoreAccumulator,
		categoryResolver: CategoryResolver,
	): PendingRestore {
		val now = System.currentTimeMillis()
		val isMangaDex = NekoTachibkDecoder.isMangaDexSourceId(item.source)
		val (sourceName, sourceTitle) = resolveSourceNameAndTitle(item.source, backupSources)
		val canonicalMangaUrl = if (isMangaDex) {
			NekoTachibkDecoder.canonicalizeMangaDexMangaUrl(item.url)
		} else {
			item.url.trim()
		}
		val publicUrl = if (isMangaDex && canonicalMangaUrl.startsWith("/manga/")) {
			"https://mangadex.org$canonicalMangaUrl"
		} else {
			canonicalMangaUrl
		}

		val mangaId = mihonMangaId(sourceName, canonicalMangaUrl)
		val tags = item.genre.mapNotNull { title ->
			val clean = title.trim()
			if (clean.isBlank()) {
				null
			} else {
				TagEntity(
					id = "${clean.lowercase(Locale.ROOT)}:$sourceName".longHashCode(),
					title = clean,
					key = clean.lowercase(Locale.ROOT),
					source = sourceName,
					isPinned = false,
				)
			}
		}

		val normalizedBackupChapters = item.chapters.map { ch ->
			if (isMangaDex) {
				ch.copy(url = NekoTachibkDecoder.canonicalizeMangaDexChapterUrl(ch.url))
			} else {
				ch
			}
		}
		val normalizedHistory = item.history.map { h ->
			if (isMangaDex) {
				h.copy(url = NekoTachibkDecoder.canonicalizeMangaDexChapterUrl(h.url))
			} else {
				h
			}
		}

		val orderedBackupChapters = normalizedBackupChapters.sortedWith(
			compareByDescending<MihonBackupChapter> { it.sourceOrder }.thenBy { it.chapterNumber },
		)
		val chapters = orderedBackupChapters.mapIndexed { index, chapter ->
			ChapterEntity(
				chapterId = mihonChapterId(sourceName, chapter.url),
				mangaId = mangaId,
				title = chapter.name,
				number = chapter.chapterNumber,
				volume = 0,
				url = chapter.url,
				scanlator = chapter.scanlator,
				uploadDate = chapter.dateUpload,
				branch = chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() },
				source = sourceName,
				index = index,
			)
		}
		val chapterByUrl = chapters.associateBy { it.url }
		val backupChapterByUrl = orderedBackupChapters.associateBy { it.url }

		val categoryIds = if (options.libraryEntries && item.favorite) {
			categoryResolver.resolve(item.categories)
		} else {
			emptyList()
		}
		val favourites = categoryIds.mapIndexed { sortIndex, categoryId ->
			FavouriteEntity(
				mangaId = mangaId,
				categoryId = categoryId,
				sortKey = sortIndex,
				isPinned = false,
				createdAt = item.dateAdded.takeIf { it > 0 } ?: now,
				deletedAt = 0,
			)
		}

		val bookmarks = orderedBackupChapters.asSequence()
			.filter { it.bookmark }
			.mapNotNull { chapter ->
				val chapterEntity = chapterByUrl[chapter.url] ?: return@mapNotNull null
				val page = chapter.lastPageRead.toInt().coerceAtLeast(0)
				BookmarkEntity(
					mangaId = mangaId,
					pageId = "$mangaId:${chapter.url}:$page".longHashCode(),
					chapterId = chapterEntity.chapterId,
					page = page,
					scroll = 0,
					imageUrl = "",
					createdAt = now,
					percent = 0f,
				)
			}
			.toList()

		val progressedChapter = chapters.lastOrNull { chapterEntity ->
			val backupChapter = backupChapterByUrl[chapterEntity.url]
			backupChapter?.let { it.read || it.lastPageRead > 0 } == true
		}
		val latestHistory = normalizedHistory
			.filter { it.lastRead > 0 }
			.maxByOrNull { it.lastRead }
		val currentChapter = latestHistory?.url?.let(chapterByUrl::get) ?: progressedChapter
		val currentHistory = currentChapter?.url?.let { url ->
			normalizedHistory.filter { it.url == url && it.lastRead > 0 }.maxByOrNull { it.lastRead }
		}
		val history = if (options.history && currentChapter != null) {
			val backupChapter = backupChapterByUrl[currentChapter.url]
			val restoredPage = backupChapter?.lastPageRead?.toInt()?.coerceAtLeast(0) ?: 0
			val updatedAt = currentHistory?.lastRead
				?: item.lastModifiedAt.takeIf { it > 0 }
				?: item.dateAdded.takeIf { it > 0 }
				?: now
			HistoryEntity(
				mangaId = mangaId,
				createdAt = updatedAt,
				updatedAt = updatedAt,
				chapterId = currentChapter.chapterId,
				page = restoredPage,
				scroll = 0f,
				percent = computeHistoryPercent(
					chapterIndex = currentChapter.index,
					chaptersCount = chapters.size,
				),
				deletedAt = 0,
				chaptersCount = chapters.size,
			)
		} else {
			null
		}

		val preferredBranch = currentChapter?.branch
			?: chapters.groupBy { it.branch }.maxByOrNull { it.value.size }?.key
		val lastTrackedChapter = chapters.lastOrNull { it.branch == preferredBranch }
		val track = lastTrackedChapter?.let { chapter ->
			TrackEntity(
				mangaId = mangaId,
				lastChapterId = chapter.chapterId,
				newChapters = 0,
				lastCheckTime = 0L,
				lastChapterDate = chapter.uploadDate,
				lastResult = TrackEntity.RESULT_NONE,
				lastError = null,
			)
		}

		val stats = if ((currentHistory?.readDuration ?: 0L) > 0L && history != null) {
			StatsEntity(
				mangaId = mangaId,
				startedAt = (history.updatedAt - currentHistory!!.readDuration).coerceAtLeast(0L),
				duration = currentHistory.readDuration,
				pages = (history.page + 1).coerceAtLeast(1),
			)
		} else {
			null
		}

		val scrobblings = if (options.tracking) {
			item.tracking.mapNotNull { tracking ->
				if (tracking.syncId == 0) {
					// Neko syncId 0 is MangaDex MDList internal status — not an external OAuth scrobbler
					return@mapNotNull null
				}
				val scrobblerId = mihonTrackerToScrobblerId(tracking.syncId) ?: return@mapNotNull null
				val targetId = tracking.mediaId.takeIf { it > 0 }
					?: tracking.mediaIdInt.toLong().takeIf { it > 0 }
					?: return@mapNotNull null
				val remoteEntryId = tracking.libraryId.toInt().takeIf { it > 0 }
					?: targetId.toInt().takeIf { it > 0 }
					?: return@mapNotNull null
				ScrobblingEntity(
					scrobbler = scrobblerId,
					id = remoteEntryId,
					mangaId = mangaId,
					targetId = targetId,
					status = decodeTrackingStatus(tracking.status),
					chapter = tracking.lastChapterRead.toInt().coerceAtLeast(0),
					comment = null,
					rating = tracking.score,
				)
			}
		} else {
			emptyList()
		}

		val mangaPrefs = if (!item.customTitle.isNullOrBlank() || !item.alternativeArtwork.isNullOrBlank()) {
			val existingPrefs = db.getPreferencesDao().find(mangaId)
			existingPrefs?.copy(
				titleOverride = item.customTitle ?: existingPrefs.titleOverride,
				coverUrlOverride = item.alternativeArtwork ?: existingPrefs.coverUrlOverride,
			) ?: MangaPrefsEntity(
				mangaId = mangaId,
				mode = -1,
				cfBrightness = 0f,
				cfContrast = 0f,
				cfInvert = false,
				cfGrayscale = false,
				cfBookEffect = false,
				titleOverride = item.customTitle,
				coverUrlOverride = item.alternativeArtwork,
				descriptionOverride = null,
				contentRatingOverride = null,
			)
		} else {
			null
		}

		// Map Neko BackupMergeManga (@ProtoNumber(904)) into merged_manga_sources
		val mergedSecondaries = ArrayList<MangaEntity>(item.mergeMangaList.size)
		val mergedLinks = ArrayList<MergedMangaEntity>(item.mergeMangaList.size)
		item.mergeMangaList.forEachIndexed { idx, mergeItem ->
			val cleanUrl = mergeItem.url.trim()
			if (cleanUrl.isNotEmpty()) {
				val (mergeSourceName, mergeDisplayTitle) = resolveMergeSource(mergeItem.mergeType)
				val secondaryId = mihonMangaId(mergeSourceName, cleanUrl)
				val resolvedTitle = mergeItem.title.ifBlank { item.title }
				mergedSecondaries += MangaEntity(
					id = secondaryId,
					title = resolvedTitle,
					altTitles = null,
					url = cleanUrl,
					publicUrl = cleanUrl,
					rating = -1f,
					isNsfw = false,
					contentRating = null,
					coverUrl = mergeItem.coverUrl.ifBlank { item.thumbnailUrl.orEmpty() },
					largeCoverUrl = null,
					state = null,
					authors = item.author,
					description = null,
					source = mergeSourceName,
					sourceTitle = mergeDisplayTitle,
				)
				mergedLinks += MergedMangaEntity(
					primaryMangaId = mangaId,
					mergedSourceName = mergeSourceName,
					mergedMangaId = secondaryId,
					mergedMangaUrl = cleanUrl,
					mergedTitle = resolvedTitle,
					mergedCoverUrl = mergeItem.coverUrl.ifBlank { item.thumbnailUrl.orEmpty() },
					priorityOrder = idx + 1,
					enabled = true,
				)
			}
		}

		val authorsCombined = listOfNotNull(
			item.author?.trim()?.takeIf { it.isNotEmpty() },
			item.artist?.trim()?.takeIf { it.isNotEmpty() && !it.equals(item.author?.trim(), ignoreCase = true) },
		).joinToString(", ").ifEmpty { null }

		return PendingRestore(
			manga = MangaEntity(
				id = mangaId,
				title = item.title,
				altTitles = null,
				url = canonicalMangaUrl,
				publicUrl = publicUrl,
				rating = -1f,
				isNsfw = false,
				contentRating = null,
				coverUrl = item.thumbnailUrl.orEmpty(),
				largeCoverUrl = null,
				state = decodeMangaState(item.status),
				authors = authorsCombined,
				description = item.description,
				source = sourceName,
				sourceTitle = sourceTitle,
			),
			tags = tags,
			chapters = chapters,
			favourites = favourites,
			history = history,
			stats = stats,
			bookmarks = bookmarks,
			scrobblings = scrobblings,
			track = track,
			mangaPrefs = mangaPrefs,
			mergedSecondaryManga = mergedSecondaries,
			mergedLinks = mergedLinks,
		)
	}

	private suspend fun persistBatch(
		batch: List<PendingRestore>,
		options: Options,
		diagnostics: RestoreAccumulator,
	) {
		batch.flatMapTo(linkedSetOf()) { it.tags }
			.takeIf { it.isNotEmpty() }
			?.let { db.getTagsDao().upsert(it.toList()) }

		for (item in batch) {
			db.getMangaDao().upsert(item.manga, item.tags)
			for (secondary in item.mergedSecondaryManga) {
				db.getMangaDao().upsert(secondary)
			}
			item.mangaPrefs?.let { db.getPreferencesDao().upsert(it) }
			item.track?.let { db.getTracksDao().upsert(it) }
			if (options.libraryEntries) {
				for (fav in item.favourites) {
					db.getFavouritesDao().upsert(fav)
				}
			}
			if (item.chapters.isNotEmpty()) {
				db.getChaptersDao().replaceAll(item.manga.id, item.chapters)
			}
			if (options.history && item.history != null) {
				db.getHistoryDao().upsert(item.history)
				diagnostics.restoredHistoryCount += 1
			}
			item.stats?.let { db.getStatsDao().upsert(it) }
			if (item.bookmarks.isNotEmpty()) {
				db.getBookmarksDao().upsert(item.bookmarks)
			}
			for (scrobbling in item.scrobblings) {
				db.getScrobblingDao().upsert(scrobbling)
				diagnostics.restoredTrackingCount += 1
			}
			for (link in item.mergedLinks) {
				val existing = db.getMergedMangaDao().findLink(
					primaryMangaId = link.primaryMangaId,
					mergedSourceName = link.mergedSourceName,
					mergedMangaUrl = link.mergedMangaUrl,
				)
				if (existing == null) {
					db.getMergedMangaDao().insert(link)
				} else {
					db.getMergedMangaDao().upsert(link.copy(id = existing.id))
				}
				diagnostics.restoredMergedLinksCount += 1
			}
		}

		diagnostics.restoredMangaCount += batch.size
	}

	private fun restorePreferences(preferences: List<MihonBackupPreference>) {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		prefs.edit {
			preferences.forEach { pref ->
				when (val value = pref.value) {
					is MihonBooleanPreferenceValue -> putBoolean(pref.key, value.value)
					is MihonFloatPreferenceValue -> putFloat(pref.key, value.value)
					is MihonIntPreferenceValue -> putInt(pref.key, value.value)
					is MihonLongPreferenceValue -> putLong(pref.key, value.value)
					is MihonStringPreferenceValue -> putString(pref.key, value.value)
					is MihonStringSetPreferenceValue -> putStringSet(pref.key, value.value)
				}
			}
		}
	}

	private fun restoreSourcePreferences(preferences: List<MihonBackupSourcePreferences>) {
		preferences.forEach { sourcePreferences ->
			val sourceId = parseSourceIdFromPreferenceKey(sourcePreferences.sourceKey)
				?: return@forEach
			val (sourceName, _) = resolveSourceNameAndTitle(sourceId, emptyList())
			val prefs = context.getSharedPreferences(SourceSettings.getStorageName(sourceName), Context.MODE_PRIVATE)
			prefs.edit {
				sourcePreferences.prefs.forEach { pref ->
					when (val value = pref.value) {
						is MihonBooleanPreferenceValue -> putBoolean(pref.key, value.value)
						is MihonFloatPreferenceValue -> putFloat(pref.key, value.value)
						is MihonIntPreferenceValue -> putInt(pref.key, value.value)
						is MihonLongPreferenceValue -> putLong(pref.key, value.value)
						is MihonStringPreferenceValue -> putString(pref.key, value.value)
						is MihonStringSetPreferenceValue -> putStringSet(pref.key, value.value)
					}
				}
			}
		}
	}

	private fun restoreExtensionRepo(repos: List<MihonBackupExtensionRepo>) {
		extensionStoreRegistry.ensureMigrated(
			systemPackages = mihonExtensionLoader.getInstalledExtensions(context, privateMode = false)
				.mapTo(HashSet()) { it.pkgName },
			sandboxPackages = mihonExtensionLoader.getInstalledExtensions(context, privateMode = true)
				.mapTo(HashSet()) { it.pkgName },
		)
		extensionStoreRegistry.importStores(
			repos.map { repo ->
				val url = normalizeExtensionStoreUrl(repo.baseUrl)
				ExtensionStoreRecord(
					id = stableExtensionStoreId(url),
					indexUrl = url,
					name = repo.name.ifBlank { repo.baseUrl },
					shortName = repo.shortName,
					fingerprint = repo.signingKeyFingerprint.takeIf(String::isNotBlank),
				)
			},
		)
	}

	private fun resolveSourceNameAndTitle(
		sourceId: Long,
		backupSources: List<MihonBackupSource>,
	): Pair<String, String?> {
		val installedAll = mihonExtensionManager.getMihonMangaSources()
		val installedById = installedAll.associate { it.sourceId to it.name }
		val installedByNorm = LinkedHashMap<String, String>(installedAll.size)
		for (src in installedAll) {
			val norm = normalizeSourceLookupKey(src.catalogueSource.name)
			if (norm.isNotEmpty() && !installedByNorm.containsKey(norm)) {
				installedByNorm[norm] = src.name
			}
		}
		val backupNameById = backupSources.associate { it.sourceId to it.name }
		val installedMangaDexName = installedAll.firstOrNull { src ->
			src.sourceId == NekoTachibkDecoder.MANGADEX_EN_SOURCE_ID ||
				src.catalogueSource.name.equals("MangaDex", ignoreCase = true) ||
				src.pkgName.contains("mangadex", ignoreCase = true)
		}?.name

		val resolvedName = resolveTargetSourceName(
			sourceId = sourceId,
			installedSourcesById = installedById,
			installedSourcesByNormalizedName = installedByNorm,
			backupSourceNamesById = backupNameById,
			installedMangaDexSourceName = installedMangaDexName,
		)
		val resolvedTitle = when {
			NekoTachibkDecoder.isMangaDexSourceId(sourceId) -> "MangaDex"
			else -> mihonExtensionManager.getMihonMangaSourceById(sourceId)?.displayName
				?: backupNameById[sourceId]?.takeIf { it.isNotBlank() }
		}
		return resolvedName to resolvedTitle
	}

	private fun resolveMergeSource(mergeType: Int): Pair<String, String> {
		val displayTitle = NekoTachibkDecoder.nekoMergeTypeDisplayName(mergeType)
		val targetNorm = normalizeSourceLookupKey(displayTitle)
		val installedMatch = mihonExtensionManager.getMihonMangaSources().firstOrNull { src ->
			normalizeSourceLookupKey(src.catalogueSource.name) == targetNorm
		}
		if (installedMatch != null) {
			return installedMatch.name to installedMatch.displayName
		}
		val syntheticId = NekoTachibkDecoder.computeTachiyomiSourceId(displayTitle, "en", 1)
		return "MIHON_$syntheticId" to displayTitle
	}

	private fun parseSourceIdFromPreferenceKey(key: String): Long? {
		return key.substringAfterLast('_').toLongOrNull()
			?: key.removePrefix("source_").substringBefore(':').toLongOrNull()
	}

	private fun mihonTrackerToScrobblerId(syncId: Int): Int? = when (syncId) {
		1 -> 3 // MyAnimeList -> MAL
		2 -> 2 // AniList
		3 -> 4 // Kitsu
		4 -> 1 // Shikimori
		11 -> 5 // MangaBaka
		else -> null
	}

	private fun decodeTrackingStatus(status: Int): String? = when (status) {
		1 -> "planned"
		2 -> "reading"
		3 -> "completed"
		4 -> "on_hold"
		5 -> "dropped"
		6 -> "re_reading"
		else -> null
	}

	private fun decodeMangaState(status: Int): String? = when (status) {
		1 -> "ONGOING"
		2 -> "FINISHED"
		4 -> "FINISHED"
		5 -> "ABANDONED"
		6 -> "PAUSED"
		else -> null
	}

	private fun computeHistoryPercent(
		chapterIndex: Int,
		chaptersCount: Int,
	): Float {
		if (chaptersCount <= 0) return 0f
		val normalizedIndex = chapterIndex.coerceIn(0, chaptersCount - 1)
		return (normalizedIndex + 1) / chaptersCount.toFloat()
	}

	companion object {
		const val DEFAULT_CATEGORY_TITLE = "Default"
		const val RESTORE_BATCH_SIZE = 200

		fun normalizeSourceLookupKey(raw: String): String {
			return buildString(raw.length) {
				for (ch in raw) {
					if (ch.isLetterOrDigit()) {
						append(ch.lowercaseChar())
					}
				}
			}
		}

		/**
		 * Pure source-mapping resolver for `.tachibk` / `.proto.gz` backups:
		 * 1. Exact sourceId match in installed Mihon extensions.
		 * 2. Neko/Tachiyomi MangaDex numeric source IDs -> installed Mihon MangaDex extension first,
		 *    falling back cleanly to built-in `"MANGADEX"`.
		 * 3. Normalized source name match against installed Mihon extensions.
		 * 4. `"MIHON_<sourceId>"` fallback.
		 */
		fun resolveTargetSourceName(
			sourceId: Long,
			installedSourcesById: Map<Long, String>,
			installedSourcesByNormalizedName: Map<String, String>,
			backupSourceNamesById: Map<Long, String>,
			installedMangaDexSourceName: String?,
		): String {
			installedSourcesById[sourceId]?.let { return it }

			if (NekoTachibkDecoder.isMangaDexSourceId(sourceId)) {
				return installedMangaDexSourceName ?: NekoTachibkDecoder.BUILTIN_MANGADEX_NAME
			}

			val backupName = backupSourceNamesById[sourceId]
			if (!backupName.isNullOrBlank()) {
				val norm = normalizeSourceLookupKey(backupName)
				installedSourcesByNormalizedName[norm]?.let { return it }
			}

			return if (sourceId > 0L) "MIHON_$sourceId" else "UNKNOWN"
		}
	}
}
