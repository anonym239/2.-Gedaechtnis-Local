package de.gedaechtnis.local.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromSource(source: SnippetSource): String = source.name
    @TypeConverter fun toSource(value: String): SnippetSource =
        runCatching { SnippetSource.valueOf(value) }.getOrDefault(SnippetSource.SCREEN_TEXT)
}

@Database(
    entities = [ContextSnippet::class, ContextSnippetFts::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun snippetDao(): SnippetDao

    companion object {
        const val NAME = "gedaechtnis.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // WAL: Erfassung (Schreiben) und Suche (Lesen) blockieren sich nicht gegenseitig.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
