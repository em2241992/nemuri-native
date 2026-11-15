package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room cache entity for spoiler-free "Where Was I?" AI recaps on MangaDetails.
 *
 * Keyed to [mangaId] and invalidated automatically whenever the user's read chapter
 * (`history.chapterId`) advances past [upToChapterId], while persisting [isDismissed]
 * for the active chapter checkpoint so closing the card collapses it into a compact chip
 * until the user reads further or reopens it.
 */
@Entity(
	tableName = "manga_ai_recaps",
	indices = [
		Index(value = ["up_to_chapter_id"]),
	],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class MangaAiRecapEntity(
	@PrimaryKey
	@ColumnInfo(name = "manga_id")
	val mangaId: Long,
	@ColumnInfo(name = "up_to_chapter_id")
	val upToChapterId: Long,
	@ColumnInfo(name = "up_to_chapter_number")
	val upToChapterNumber: Float,
	@ColumnInfo(name = "up_to_chapter_title")
	val upToChapterTitle: String,
	@ColumnInfo(name = "summary_text")
	val summaryText: String,
	@ColumnInfo(name = "backend_used")
	val backendUsed: String,
	@ColumnInfo(name = "is_dismissed")
	val isDismissed: Boolean,
	@ColumnInfo(name = "updated_at")
	val updatedAt: Long,
)
