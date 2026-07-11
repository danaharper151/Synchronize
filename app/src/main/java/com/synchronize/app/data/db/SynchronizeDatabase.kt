package com.synchronize.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.synchronize.app.data.db.dao.BiosignalSampleDao
import com.synchronize.app.data.db.dao.GlucoseReadingDao
import com.synchronize.app.data.db.dao.KetoneReadingDao
import com.synchronize.app.data.db.dao.MealEntryDao
import com.synchronize.app.data.db.dao.SleepSessionDao
import com.synchronize.app.data.db.entity.BiosignalSample
import com.synchronize.app.data.db.entity.GlucoseReading
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.db.entity.MealEntry
import com.synchronize.app.data.db.entity.SleepSession
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        GlucoseReading::class,
        KetoneReading::class,
        BiosignalSample::class,
        SleepSession::class,
        MealEntry::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SynchronizeDatabase : RoomDatabase() {

    abstract fun glucoseReadingDao(): GlucoseReadingDao
    abstract fun ketoneReadingDao(): KetoneReadingDao
    abstract fun biosignalSampleDao(): BiosignalSampleDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun mealEntryDao(): MealEntryDao

    companion object {
        @Volatile
        private var INSTANCE: SynchronizeDatabase? = null

        // Passphrase for SQLCipher encryption
        // In production this will be derived from BiometricPrompt / secure keystore
        private const val DB_NAME = "synchronize_db"

        fun getInstance(context: Context, passphrase: ByteArray): SynchronizeDatabase {
            return INSTANCE ?: synchronized(this) {
                val factory = SupportFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SynchronizeDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun closeInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}