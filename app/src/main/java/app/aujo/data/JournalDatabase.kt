package app.aujo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert
    suspend fun insert(entry: EntryEntity): Long

    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observeById(id: Long): Flow<EntryEntity?>

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [EntryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aujo.db"
                ).build().also { instance = it }
            }
    }
}
