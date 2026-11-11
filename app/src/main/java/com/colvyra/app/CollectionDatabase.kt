package com.colvyra.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "collections")
data class CollectionRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val tags: String = "",
    val favorite: Boolean = false,
    val coverUri: String? = null
)

@Entity(tableName = "collection_entries", primaryKeys = ["collectionId", "uri"],
    foreignKeys = [ForeignKey(entity = CollectionRecord::class, parentColumns = ["id"],
        childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("collectionId")])
data class CollectionEntry(
    val collectionId: String,
    val uri: String,
    val name: String,
    val mime: String,
    val tags: String = "",
    val favorite: Boolean = false
)

data class LibraryReference(val uri: String, val name: String, val mime: String)

@Dao
abstract class CollectionDao {
    @Query("SELECT * FROM collections ORDER BY favorite DESC, name COLLATE NOCASE")
    abstract fun observeCollections(): Flow<List<CollectionRecord>>
    @Query("SELECT * FROM collection_entries ORDER BY favorite DESC, name COLLATE NOCASE")
    abstract fun observeEntries(): Flow<List<CollectionEntry>>
    @Query("SELECT * FROM collections WHERE id = :id")
    abstract suspend fun find(id: String): CollectionRecord?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(record: CollectionRecord): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEntry(entry: CollectionEntry): Long
    @Update abstract suspend fun update(record: CollectionRecord)
    @Update abstract suspend fun updateEntry(entry: CollectionEntry)
    @Query("DELETE FROM collections WHERE id = :id")
    abstract suspend fun deleteCollection(id: String)
    @Query("DELETE FROM collection_entries WHERE collectionId = :id AND uri = :uri")
    abstract suspend fun deleteEntry(id: String, uri: String)
    @Query("UPDATE collections SET coverUri = NULL WHERE id = :id AND coverUri = :uri")
    abstract suspend fun clearCover(id: String, uri: String)
    @Transaction
    open suspend fun removeReference(id: String, uri: String) {
        deleteEntry(id, uri)
        clearCover(id, uri)
    }
    @Transaction
    open suspend fun importLegacy(names: List<String>) {
        names.filter { it.isNotBlank() }.distinct().forEach {
            insert(CollectionRecord(id = UUID.nameUUIDFromBytes(("legacy:" + it).toByteArray(Charsets.UTF_8)).toString(), name = it))
        }
    }
    @Transaction
    open suspend fun addReference(id: String, ref: LibraryReference) {
        insertEntry(CollectionEntry(id, ref.uri, ref.name, ref.mime))
    }
}

@Database(entities = [CollectionRecord::class, CollectionEntry::class], version = 1, exportSchema = false)
abstract class CollectionDatabase : RoomDatabase() {
    abstract fun collections(): CollectionDao
    companion object {
        @Volatile private var instance: CollectionDatabase? = null
        fun get(context: Context): CollectionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CollectionDatabase::class.java,
                "colvyra-library.db").build().also { instance = it }
        }
    }
}
