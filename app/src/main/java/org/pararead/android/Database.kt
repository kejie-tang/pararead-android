package org.pararead.android

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import org.pararead.core.*

@Entity data class DocumentRecord(@PrimaryKey val id: String, val uri: String, val name: String, val path: String,
    val revision: String, val length: Long, val opened: Long = System.currentTimeMillis())
@Entity data class TranslationRecord(@PrimaryKey val key: String, val source: String, val translation: String,
    val fingerprint: String, val created: Long = System.currentTimeMillis())
@Entity(primaryKeys = ["revision", "page", "segmenterVersion"])
data class PageRecord(val revision: String, val page: Int, val segmenterVersion: Int = 2, val json: String)
@Entity data class ReadingRecord(@PrimaryKey val documentId: String, val json: String)
@Entity data class DraftRecord(@PrimaryKey val documentId: String, val baseRevision: String, val path: String, val size: Long)
@Entity data class JobRecord(@PrimaryKey val id: String, val documentId: String, val json: String, val status: String)
@Entity data class PerformanceRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val documentId: String,
    val name: String, val value: Double, val timestamp: Long = System.currentTimeMillis())
data class LibraryDocument(@Embedded val document: DocumentRecord, val readingJson: String?) {
    fun reading(): JSONObject = runCatching { JSONObject(readingJson ?: "{}") }.getOrDefault(JSONObject())
}
@Dao interface ReaderDao {
    @Query("SELECT * FROM DocumentRecord ORDER BY opened DESC") suspend fun documents(): List<DocumentRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun document(value: DocumentRecord)
    @Query("SELECT DocumentRecord.*, ReadingRecord.json AS readingJson FROM DocumentRecord LEFT JOIN ReadingRecord ON DocumentRecord.id = ReadingRecord.documentId ORDER BY DocumentRecord.opened DESC")
    fun library(): Flow<List<LibraryDocument>>
    @Query("UPDATE DocumentRecord SET opened = :time WHERE id = :id") suspend fun opened(id: String, time: Long)
    @Query("UPDATE DocumentRecord SET name = :name WHERE id = :id") suspend fun rename(id: String, name: String)
    @Query("DELETE FROM DocumentRecord WHERE id = :id") suspend fun deleteDocumentRecord(id: String)
    @Query("DELETE FROM ReadingRecord WHERE documentId = :id") suspend fun deleteReading(id: String)
    @Query("DELETE FROM JobRecord WHERE documentId = :id") suspend fun deleteJobs(id: String)
    @Query("DELETE FROM PerformanceRecord WHERE documentId = :id") suspend fun deletePerformance(id: String)
    @Query("DELETE FROM PageRecord WHERE revision = :revision OR revision LIKE :draftPrefix") suspend fun deletePages(revision: String, draftPrefix: String)
    @Transaction suspend fun deleteDocument(id: String, revision: String) {
        deleteReading(id); deleteDraft(id); deleteJobs(id); deletePerformance(id)
        deletePages(revision, "$revision:draft:%"); deleteDocumentRecord(id)
        // Sentence translations are shared between PDFs and must survive another document's deletion.
    }
    @Query("SELECT * FROM TranslationRecord WHERE `key` = :key") suspend fun translation(key: String): TranslationRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun translation(value: TranslationRecord)
    @Query("SELECT * FROM PageRecord WHERE revision = :revision AND page = :page AND segmenterVersion = 2") suspend fun page(revision: String, page: Int): PageRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun page(value: PageRecord)
    @Query("SELECT * FROM ReadingRecord WHERE documentId = :id") suspend fun reading(id: String): ReadingRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun reading(value: ReadingRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun draft(value: DraftRecord)
    @Query("SELECT * FROM DraftRecord WHERE documentId = :id") suspend fun draft(id: String): DraftRecord?
    @Query("DELETE FROM DraftRecord WHERE documentId = :id") suspend fun deleteDraft(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun job(value: JobRecord)
    @Query("SELECT * FROM JobRecord WHERE id = :id") suspend fun job(id: String): JobRecord?
    @Insert suspend fun performance(value: PerformanceRecord)
    @Query("SELECT * FROM PerformanceRecord ORDER BY timestamp DESC LIMIT 5000") suspend fun performance(): List<PerformanceRecord>
    @Query("SELECT COUNT(*) FROM TranslationRecord") suspend fun translationCount(): Int
}
@Database(entities = [DocumentRecord::class, TranslationRecord::class, PageRecord::class, ReadingRecord::class, DraftRecord::class, JobRecord::class, PerformanceRecord::class], version = 1, exportSchema = true)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun reader(): ReaderDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context, ReaderDatabase::class.java, "pararead.db").build() }
}
class RoomTranslationCache(private val dao: ReaderDao) : TranslationCache {
    override suspend fun get(key: String) = dao.translation(key)?.translation
    override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {
        dao.translation(TranslationRecord(key, text, translation, config.fingerprint()))
    }
}
