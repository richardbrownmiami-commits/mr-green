package im.mrgreen.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Requery's packaged native libraries target Android, not this Linux JVM.
// Run the production SQL host regression via scripts/check-phase2-storage.py;
// actual Android/requery migration verification remains a device check.
@org.junit.Ignore("Requires Android requery native runtime; host SQL checks are separate")
@RunWith(RobolectricTestRunner::class)
class GreenStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val dbName = "mr-green.db"

    @After fun cleanup() { context.deleteDatabase(dbName) }

    @Test fun v1UpgradePreservesUserDataFtsAndExistingCloudRows() {
        context.deleteDatabase(dbName)
        val old = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE identity(id INTEGER PRIMARY KEY,name TEXT NOT NULL)")
                db.execSQL("INSERT INTO identity VALUES(1,'Ada')")
                db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,content TEXT NOT NULL,created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE facts(id INTEGER PRIMARY KEY,key TEXT NOT NULL,value TEXT NOT NULL,tags TEXT NOT NULL DEFAULT '',importance REAL NOT NULL DEFAULT .5,source TEXT NOT NULL DEFAULT '',last_used INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE cloud_log(id INTEGER PRIMARY KEY,provider TEXT NOT NULL,data_class TEXT NOT NULL,category TEXT NOT NULL,size_bytes INTEGER NOT NULL,created_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO messages(role,content,created_at) VALUES('user','hello',123)")
                db.execSQL("INSERT INTO facts(id,key,value) VALUES(7,'color','green')")
                db.execSQL("INSERT INTO cloud_log VALUES(9,'old-provider','P1','chat',42,321)")
                db.execSQL("CREATE VIRTUAL TABLE fts_messages USING fts5(content, content='messages', content_rowid='rowid')")
                db.execSQL("CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN INSERT INTO fts_messages(rowid,content) VALUES(new.rowid,new.content); END")
                db.execSQL("INSERT INTO fts_messages(rowid,content) VALUES(1,'hello')")
                db.execSQL("CREATE TABLE models(id TEXT PRIMARY KEY, path TEXT NOT NULL)")
                db.execSQL("INSERT INTO models VALUES('m1','model.gguf')")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        old.writableDatabase.close()
        old.close()

        val store = GreenStore(context)
        assertEquals("Ada", store.identityName())
        assertEquals("hello", store.messages().single().content)
        val db = store.readableDatabase
        db.rawQuery("SELECT value FROM facts WHERE id=7", null).use { assertTrue(it.moveToFirst()); assertEquals("green", it.getString(0)) }
        db.rawQuery("SELECT provider,size_bytes FROM cloud_log WHERE id=9", null).use { assertTrue(it.moveToFirst()); assertEquals("old-provider", it.getString(0)); assertEquals(42, it.getInt(1)) }
        db.rawQuery("SELECT count(*) FROM fts_messages WHERE fts_messages MATCH 'hello'", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        db.rawQuery("SELECT path FROM models WHERE id='m1'", null).use { assertTrue(it.moveToFirst()); assertEquals("model.gguf", it.getString(0)) }
        store.auditCloudSend("provider-x", "P1", "chat", 12)
        assertEquals(2, store.cloudSends().size)
        store.close()
    }

    @Test fun cacheExpiresCapsTtlAndBoundsEntries() {
        context.deleteDatabase(dbName)
        val store = GreenStore(context)
        store.cacheCloudReply("short", "response", 1)
        Thread.sleep(10)
        assertNull(store.cachedCloudReply("short"))
        store.cacheCloudReply("long", "kept", Long.MAX_VALUE)
        assertEquals("kept", store.cachedCloudReply("long"))
        for (i in 0 until 35) store.cacheCloudReply("key-$i", "reply-$i", 300_000)
        val db = store.readableDatabase
        db.rawQuery("SELECT count(*) FROM cloud_cache", null).use { assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) <= 30) }
        db.rawQuery("SELECT digest FROM cloud_cache", null).use { while(it.moveToNext()) assertFalse(it.getString(0).contains("key-")) }
        store.close()
    }

    @Test fun auditRejectsP2AndUnlistedCategory() {
        context.deleteDatabase(dbName)
        val store = GreenStore(context)
        try { store.auditCloudSend("provider", "P2", "chat", 1); fail("P2 must be rejected") } catch (_: IllegalArgumentException) { }
        try { store.auditCloudSend("provider", "P1", "raw-prompt", 1); fail("category must be enumerated") } catch (_: IllegalArgumentException) { }
        assertTrue(store.cloudSends().isEmpty())
        store.close()
    }
}
