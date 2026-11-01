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

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): EntryEntity?

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM entries WHERE silenceJson IS NULL OR waveformJson IS NULL OR IFNULL(analysisVersion, 0) < :analyzerVersion")
    suspend fun pendingAnalysis(analyzerVersion: Int): List<EntryEntity>

    @Query("UPDATE entries SET silenceJson = :silenceJson, waveformJson = :waveformJson, analysisVersion = :analyzerVersion WHERE id = :id")
    suspend fun setAnalysis(id: Long, analyzerVersion: Int, silenceJson: String?, waveformJson: String?)

    /** Live path (engine ran during the recording) — same call shape for both sources. */
    @Query("UPDATE entries SET transcript = :transcript, transcriptSource = :source, transcriptState = :state, transcriptError = :error WHERE id = :id")
    suspend fun setTranscript(id: Long, transcript: String, source: String, state: Int, error: String?)

    @Query("UPDATE entries SET transcriptState = :state, transcriptError = :error WHERE id = :id")
    suspend fun setTranscriptState(id: Long, state: Int, error: String?)

    /** Entries whose queued/run states outlived the process — repaired on startup. */
    @Query("UPDATE entries SET transcriptState = 4, transcriptError = 'interrupted — retry' WHERE transcriptState IN (1, 2)")
    suspend fun failStaleTranscripts()
}

private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN waveformJson TEXT")
    }
}

private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN analysisVersion INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN language TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN transcriptState INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN transcriptSource TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE entries ADD COLUMN transcriptError TEXT")
    }
}

@Database(entities = [EntryEntity::class], version = 5, exportSchema = false)
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
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
