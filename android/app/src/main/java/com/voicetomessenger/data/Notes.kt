package com.voicetomessenger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT text FROM notes ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentTexts(limit: Int): List<String>

    @Insert
    suspend fun insert(note: Note): Long

    // Правим только текст: createdAt — момент диктовки, он не должен уезжать от правок,
    // иначе заметка прыгает в начало списка и путает порядок.
    @Query("UPDATE notes SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "voice-to-messenger.db",
            ).build().also { instance = it }
        }
    }
}

class NotesRepository(private val dao: NoteDao) {
    val notes: Flow<List<Note>> = dao.observeAll()

    /** Тексты последних заметок — примеры для стиля «Мой стиль». */
    suspend fun recentTexts(limit: Int): List<String> = dao.recentTexts(limit)

    suspend fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) dao.insert(Note(text = trimmed))
    }

    /** Пустой текст не сохраняем: это молчаливое удаление заметки, а его просят явно. */
    suspend fun updateText(id: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) dao.updateText(id, trimmed)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()
}
