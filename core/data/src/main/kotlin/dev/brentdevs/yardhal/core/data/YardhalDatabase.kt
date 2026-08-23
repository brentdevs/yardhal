package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageRow::class], version = 1, exportSchema = true)
public abstract class YardhalDatabase : RoomDatabase() {

    public abstract fun messageDao(): MessageDao

    public companion object {
        public fun build(context: Context, name: String = "yardhal.db"): YardhalDatabase =
            Room.databaseBuilder(context, YardhalDatabase::class.java, name)
                .fallbackToDestructiveMigration()
                .build()

        public fun inMemory(context: Context): YardhalDatabase =
            Room.inMemoryDatabaseBuilder(context, YardhalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
