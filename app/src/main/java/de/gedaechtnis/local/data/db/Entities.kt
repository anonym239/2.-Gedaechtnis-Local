package de.gedaechtnis.local.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** Woher ein Snippet stammt. */
enum class SnippetSource { SCREEN_TEXT, SCREEN_OCR, NOTE, VOICE }

/**
 * Ein erfasstes Kontext-Ereignis: "Was war wann in welcher App zu sehen / wurde notiert".
 * Die Embedding-Spalte wird asynchron (WorkManager) befüllt, damit die Erfassung billig bleibt.
 */
@Entity(
    tableName = "snippets",
    indices = [
        Index("timestamp"),
        Index("packageName"),
        Index("contentHash"),
    ],
)
data class ContextSnippet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val windowTitle: String?,
    val text: String,
    val source: SnippetSource,
    /** 64-Bit-Hash des normalisierten Textes zur Duplikaterkennung. */
    val contentHash: Long,
    /** Float32-Vektor (Little Endian) oder null, solange noch nicht eingebettet. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
) {
    // ByteArray-Felder: Identität über die ID genügt.
    override fun equals(other: Any?) = other is ContextSnippet && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * FTS4-Volltextindex, der über Trigger automatisch mit [ContextSnippet] synchron gehalten wird.
 * unicode61 entfernt Diakritika ("Prüfung" findet auch "Prufung") und ist sprachneutral.
 */
@Fts4(contentEntity = ContextSnippet::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "snippets_fts")
data class ContextSnippetFts(
    val text: String,
    val appLabel: String,
    val windowTitle: String?,
)

/** Schlanke Projektion für die Vektorsuche – lädt nicht den ganzen Text. */
data class EmbeddingRow(
    val id: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
)

data class AppCount(
    val appLabel: String,
    val packageName: String,
    val count: Int,
)
