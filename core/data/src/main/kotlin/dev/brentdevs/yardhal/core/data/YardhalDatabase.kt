package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageRow::class], version = 2, exportSchema = true)
public abstract class YardhalDatabase : RoomDatabase() {

    public abstract fun messageDao(): MessageDao

    public companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN channelContext TEXT")
            }
        }

        private fun ensureFts(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(sender, body, tokenize=unicode61)")
        }

        private val callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = ensureFts(db)

            override fun onOpen(db: SupportSQLiteDatabase) = ensureFts(db)
        }

        public fun build(context: Context, name: String = "yardhal.db"): YardhalDatabase =
            Room.databaseBuilder(context, YardhalDatabase::class.java, name)
                .addCallback(callback)
                .addMigrations(migration1To2)
                .build()

        public fun inMemory(context: Context): YardhalDatabase =
            Room.inMemoryDatabaseBuilder(context, YardhalDatabase::class.java)
                .addCallback(callback)
                .allowMainThreadQueries()
                .build()
    }
}
