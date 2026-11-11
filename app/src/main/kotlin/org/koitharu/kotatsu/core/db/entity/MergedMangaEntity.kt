package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_MERGED_MANGA

@Entity(
	tableName = TABLE_MERGED_MANGA,
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["primary_manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["primary_manga_id"]),
		Index(value = ["primary_manga_id", "merged_source_name", "merged_manga_url"], unique = true),
	],
)
data class MergedMangaEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "id") val id: Long = 0L,
	@ColumnInfo(name = "primary_manga_id") val primaryMangaId: Long,
	@ColumnInfo(name = "merged_source_name") val mergedSourceName: String,
	@ColumnInfo(name = "merged_manga_id") val mergedMangaId: Long,
	@ColumnInfo(name = "merged_manga_url") val mergedMangaUrl: String,
	@ColumnInfo(name = "merged_title") val mergedTitle: String,
	@ColumnInfo(name = "merged_cover_url") val mergedCoverUrl: String,
	@ColumnInfo(name = "priority_order") val priorityOrder: Int = 0,
	@ColumnInfo(name = "enabled") val enabled: Boolean = true,
)
