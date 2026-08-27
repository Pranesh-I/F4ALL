package com.sai.sports.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.sai.sports.sync.SyncStatus

class SyncStatusConverter {

    @TypeConverter
    fun fromStatus(status: SyncStatus): String = status.name

    /**
     * Falls back to FAILED rather than throwing on an unrecognised value.
     *
     * A downgrade, or a row written by a newer build, must not crash the app on
     * launch — that would strand every queued video on the device. FAILED is
     * visible on the sync screen and manually retryable, which is the safe
     * place to land.
     */
    @TypeConverter
    fun toStatus(value: String): SyncStatus =
        runCatching { SyncStatus.valueOf(value) }.getOrDefault(SyncStatus.FAILED)
}

@Database(
    entities = [TestAttemptEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(SyncStatusConverter::class)
abstract class F4allDatabase : RoomDatabase() {

    abstract fun testAttemptDao(): TestAttemptDao

    companion object {

        private const val DATABASE_NAME = "f4all.db"

        @Volatile
        private var instance: F4allDatabase? = null

        fun get(context: Context): F4allDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): F4allDatabase =
            Room.databaseBuilder(context, F4allDatabase::class.java, DATABASE_NAME)
                // No fallbackToDestructiveMigration, on purpose. This table can
                // hold the only copy of a test an athlete travelled to record.
                // A future schema change must ship a real migration; failing
                // loudly in development is far better than silently wiping an
                // unsynced queue in the field.
                .build()
    }
}
