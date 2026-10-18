package im.mrgreen.core

// Execute the production query on the host shadow; not Android/requery runtime evidence.
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FactMaintenanceTest {
    @Test fun boundedPruneRemovesOnlyExtraExactDuplicates() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY, key TEXT NOT NULL, value TEXT NOT NULL, tags TEXT NOT NULL, importance REAL NOT NULL, source TEXT NOT NULL, last_used INTEGER NOT NULL)")
            db.beginTransaction()
            try {
                // Twelve duplicate copies: the first row is canonical; only ten extras may be removed.
                repeat(12) { index ->
                    db.execSQL("INSERT INTO facts VALUES(?,?,?,?,?,?,?)", arrayOf(index + 1, "same", "value", "tag", 0.5, "source", 1))
                }
                // A unique fact must never be treated as a keeper candidate to delete.
                db.execSQL("INSERT INTO facts VALUES(?,?,?,?,?,?,?)", arrayOf(100, "unique", "untouched", "tag", 0.5, "source", 1))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }

            db.execSQL(FactMaintenance.PRUNE_DUPLICATES_SQL)

            assertEquals(2, db.rawQuery("SELECT count(*) FROM facts WHERE key='same'", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(1, db.rawQuery("SELECT count(*) FROM facts WHERE id=1", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(1, db.rawQuery("SELECT count(*) FROM facts WHERE id=100", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { db.close() }
    }
}
