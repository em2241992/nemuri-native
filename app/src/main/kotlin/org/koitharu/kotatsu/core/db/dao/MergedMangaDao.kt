package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity

@Dao
abstract class MergedMangaDao {

	@Query(
		"SELECT * FROM merged_manga_sources WHERE primary_manga_id = :primaryMangaId ORDER BY priority_order ASC, id ASC",
	)
	abstract fun observeByPrimary(primaryMangaId: Long): Flow<List<MergedMangaEntity>>

	@Query(
		"SELECT * FROM merged_manga_sources WHERE primary_manga_id = :primaryMangaId ORDER BY priority_order ASC, id ASC",
	)
	abstract suspend fun findByPrimary(primaryMangaId: Long): List<MergedMangaEntity>

	@Query(
		"SELECT * FROM merged_manga_sources WHERE primary_manga_id = :primaryMangaId AND enabled = 1 ORDER BY priority_order ASC, id ASC",
	)
	abstract suspend fun findEnabledByPrimary(primaryMangaId: Long): List<MergedMangaEntity>

	@Query("SELECT * FROM merged_manga_sources ORDER BY primary_manga_id ASC, priority_order ASC, id ASC")
	abstract fun observeAll(): Flow<List<MergedMangaEntity>>

	@Query("SELECT * FROM merged_manga_sources ORDER BY primary_manga_id ASC, priority_order ASC, id ASC")
	abstract suspend fun findAll(): List<MergedMangaEntity>

	@Query("SELECT * FROM merged_manga_sources WHERE id = :id")
	abstract suspend fun findById(id: Long): MergedMangaEntity?

	@Query(
		"""
		SELECT * FROM merged_manga_sources
		WHERE primary_manga_id = :primaryMangaId
			AND merged_source_name = :mergedSourceName
			AND merged_manga_url = :mergedMangaUrl
		LIMIT 1
		""",
	)
	abstract suspend fun findLink(
		primaryMangaId: Long,
		mergedSourceName: String,
		mergedMangaUrl: String,
	): MergedMangaEntity?

	@Query("SELECT MAX(priority_order) FROM merged_manga_sources WHERE primary_manga_id = :primaryMangaId")
	abstract suspend fun maxPriorityOrder(primaryMangaId: Long): Int?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insert(entity: MergedMangaEntity): Long

	@Upsert
	abstract suspend fun upsert(entity: MergedMangaEntity)

	@Upsert
	abstract suspend fun upsertAll(entities: Collection<MergedMangaEntity>)

	@Query("UPDATE merged_manga_sources SET enabled = :enabled WHERE id = :id")
	abstract suspend fun setEnabled(id: Long, enabled: Boolean)

	@Query("UPDATE merged_manga_sources SET priority_order = :priorityOrder WHERE id = :id")
	abstract suspend fun updatePriority(id: Long, priorityOrder: Int)

	@Query("DELETE FROM merged_manga_sources WHERE id = :id")
	abstract suspend fun deleteById(id: Long)

	@Query("DELETE FROM merged_manga_sources WHERE primary_manga_id = :primaryMangaId")
	abstract suspend fun deleteByPrimary(primaryMangaId: Long)
}
