package org.koitharu.kotatsu.details.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MergeSourcesRepository @Inject constructor(
	private val db: MangaDatabase,
	private val mangaDataRepository: MangaDataRepository,
) {

	private val dao
		get() = db.getMergedMangaDao()

	fun observeMergedSources(primaryMangaId: Long): Flow<List<MergedMangaEntity>> {
		return dao.observeByPrimary(primaryMangaId)
	}

	fun observeAllMergedSources(): Flow<List<MergedMangaEntity>> {
		return dao.observeAll()
	}

	suspend fun getMergedSources(primaryMangaId: Long): List<MergedMangaEntity> {
		return dao.findByPrimary(primaryMangaId)
	}

	suspend fun getEnabledMergedSources(primaryMangaId: Long): List<MergedMangaEntity> {
		return dao.findEnabledByPrimary(primaryMangaId)
	}

	suspend fun hasEnabledMergedSources(primaryMangaId: Long): Boolean {
		return dao.findEnabledByPrimary(primaryMangaId).isNotEmpty()
	}

	suspend fun linkSource(primaryManga: Manga, secondaryManga: Manga): MergedMangaEntity {
		// Ensure both primary and secondary manga rows exist in `manga` so FK constraints hold
		// even if the primary series has not been added to Favourites yet.
		mangaDataRepository.storeManga(primaryManga, replaceExisting = false)
		mangaDataRepository.storeManga(secondaryManga, replaceExisting = false)

		return db.withTransaction {
			val existing = dao.findLink(
				primaryMangaId = primaryManga.id,
				mergedSourceName = secondaryManga.source.name,
				mergedMangaUrl = secondaryManga.url,
			)
			if (existing != null) {
				val updated = existing.copy(
					mergedMangaId = secondaryManga.id,
					mergedTitle = secondaryManga.title,
					mergedCoverUrl = secondaryManga.largeCoverUrl ?: secondaryManga.coverUrl.orEmpty(),
					enabled = true,
				)
				dao.upsert(updated)
				updated
			} else {
				val nextPriority = (dao.maxPriorityOrder(primaryManga.id) ?: -1) + 1
				val entity = MergedMangaEntity(
					primaryMangaId = primaryManga.id,
					mergedSourceName = secondaryManga.source.name,
					mergedMangaId = secondaryManga.id,
					mergedMangaUrl = secondaryManga.url,
					mergedTitle = secondaryManga.title,
					mergedCoverUrl = secondaryManga.largeCoverUrl ?: secondaryManga.coverUrl.orEmpty(),
					priorityOrder = nextPriority,
					enabled = true,
				)
				val newId = dao.insert(entity)
				entity.copy(id = newId)
			}
		}
	}

	suspend fun unlinkSource(id: Long) {
		dao.deleteById(id)
	}

	suspend fun setEnabled(id: Long, enabled: Boolean) {
		dao.setEnabled(id, enabled)
	}

	suspend fun movePriority(primaryMangaId: Long, id: Long, moveUp: Boolean) {
		db.withTransaction {
			val current = dao.findByPrimary(primaryMangaId).toMutableList()
			val index = current.indexOfFirst { it.id == id }
			if (index == -1) return@withTransaction
			val targetIndex = if (moveUp) index - 1 else index + 1
			if (targetIndex !in current.indices) return@withTransaction
			val item = current.removeAt(index)
			current.add(targetIndex, item)
			current.forEachIndexed { order, entity ->
				if (entity.priorityOrder != order) {
					dao.updatePriority(entity.id, order)
				}
			}
		}
	}

	suspend fun toSeedManga(entity: MergedMangaEntity): Manga {
		val stored = db.getMangaDao().find(entity.mergedMangaId)?.toManga()
		val resolvedSource = ResolveMangaSource(entity.mergedSourceName)
		if (stored != null) {
			return stored.copy(
				url = entity.mergedMangaUrl,
				source = resolvedSource,
			)
		}
		return Manga(
			id = entity.mergedMangaId,
			title = entity.mergedTitle,
			altTitles = emptySet(),
			url = entity.mergedMangaUrl,
			publicUrl = "",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = entity.mergedCoverUrl.ifBlank { null },
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = resolvedSource,
		)
	}
}
