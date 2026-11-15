package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.MangaAiRecapEntity

@Dao
interface MangaAiRecapDao {

	@Query("SELECT * FROM manga_ai_recaps WHERE manga_id = :mangaId LIMIT 1")
	fun observeRecap(mangaId: Long): Flow<MangaAiRecapEntity?>

	@Query("SELECT * FROM manga_ai_recaps WHERE manga_id = :mangaId LIMIT 1")
	suspend fun getRecap(mangaId: Long): MangaAiRecapEntity?

	@Upsert
	suspend fun upsert(entity: MangaAiRecapEntity)

	@Query("UPDATE manga_ai_recaps SET is_dismissed = :dismissed WHERE manga_id = :mangaId")
	suspend fun setDismissed(mangaId: Long, dismissed: Boolean)

	@Query("DELETE FROM manga_ai_recaps WHERE manga_id = :mangaId")
	suspend fun deleteRecap(mangaId: Long)
}
