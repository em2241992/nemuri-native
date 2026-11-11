package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Neko-style multi-source merge links (`merged_manga_sources`) allowing any DropSauce/Mihon/LNReader
 * manga to link secondary sources for chapter gap-filling and silent reader failover.
 */
class Migration39To40 : Migration(39, 40) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `merged_manga_sources` (" +
				"`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
				"`primary_manga_id` INTEGER NOT NULL, " +
				"`merged_source_name` TEXT NOT NULL, " +
				"`merged_manga_id` INTEGER NOT NULL, " +
				"`merged_manga_url` TEXT NOT NULL, " +
				"`merged_title` TEXT NOT NULL, " +
				"`merged_cover_url` TEXT NOT NULL, " +
				"`priority_order` INTEGER NOT NULL, " +
				"`enabled` INTEGER NOT NULL, " +
				"FOREIGN KEY(`primary_manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_merged_manga_sources_primary_manga_id` " +
				"ON `merged_manga_sources` (`primary_manga_id`)",
		)
		db.execSQL(
			"CREATE UNIQUE INDEX IF NOT EXISTS " +
				"`index_merged_manga_sources_primary_manga_id_merged_source_name_merged_manga_url` " +
				"ON `merged_manga_sources` (`primary_manga_id`, `merged_source_name`, `merged_manga_url`)",
		)
	}
}
