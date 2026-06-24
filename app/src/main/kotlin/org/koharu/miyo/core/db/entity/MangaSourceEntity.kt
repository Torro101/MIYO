package org.koharu.miyo.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.koharu.miyo.core.db.TABLE_SOURCES

@Entity(
	tableName = TABLE_SOURCES,
)
data class MangaSourceEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "source")
	val source: String,
	@ColumnInfo(name = "enabled") val isEnabled: Boolean,
	@ColumnInfo(name = "sort_key", index = true) val sortKey: Int,
	@ColumnInfo(name = "added_in") val addedIn: Int,
	@ColumnInfo(name = "used_at") val lastUsedAt: Long,
	@ColumnInfo(name = "pinned") val isPinned: Boolean,
	@ColumnInfo(name = "cf_state") val cfState: Int,
	@ColumnInfo(name = "is_hidden", defaultValue = "0") val isHidden: Boolean = false,
	@ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = 0,
)
