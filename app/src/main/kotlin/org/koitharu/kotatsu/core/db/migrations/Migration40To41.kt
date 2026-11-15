package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration40To41 : Migration(40, 41) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `manga_ai_recaps` (`manga_id` INTEGER NOT NULL, `up_to_chapter_id` INTEGER NOT NULL, `up_to_chapter_number` REAL NOT NULL, `up_to_chapter_title` TEXT NOT NULL, `summary_text` TEXT NOT NULL, `backend_used` TEXT NOT NULL, `is_dismissed` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`manga_id`), FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_manga_ai_recaps_up_to_chapter_id` ON `manga_ai_recaps` (`up_to_chapter_id`)",
		)
	}
}
