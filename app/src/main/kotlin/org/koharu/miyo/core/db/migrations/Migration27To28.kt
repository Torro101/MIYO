package org.koharu.miyo.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration27To28 : Migration(27, 28) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE sources ADD COLUMN is_hidden INTEGER NOT NULL DEFAULT 0")
		db.execSQL("ALTER TABLE sources ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
	}
}
