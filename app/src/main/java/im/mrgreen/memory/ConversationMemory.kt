package im.mrgreen.memory

import android.content.Context
import android.content.SharedPreferences
import android.content.ContentValues
import im.mrgreen.data.ChatMessage
import im.mrgreen.data.GreenStore
import io.requery.android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlin.math.sqrt

/** Local, bounded lexical retrieval over finalized chat messages. No model, network, or separate DB. */
data class RecalledSnippet(val messageId: Long, val role: String, val text: String, val score: Double)
data class ConversationMemoryStatus(val enabled: Boolean, val indexedMessages: Int)

class ConversationMemory(context: Context, private val store: GreenStore) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("conversation_memory", Context.MODE_PRIVATE)
    private var revision = 0L
    private val cache = object : LinkedHashMap<String, List<RecalledSnippet>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RecalledSnippet>>?) = size > 16
    }
    private val db get() = store.writableDatabase

    /** Idempotently create only this component's table in mr-green.db. */
    @Synchronized fun prepare() {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_vectors(message_id INTEGER PRIMARY KEY, role TEXT NOT NULL CHECK(role IN ('user','assistant')), created_at INTEGER NOT NULL, vector BLOB NOT NULL, norm REAL NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS conversation_vectors_time ON conversation_vectors(created_at,message_id)")
        if (prefs.getInt("feature_version", 0) != FEATURE_VERSION) {
            db.delete("conversation_vectors", null, null)
            prefs.edit().putInt("feature_version", FEATURE_VERSION).apply()
            revision++
            cache.clear()
        }
    }

    /** Split a bounded batch between recent missing rows and oldest backlog; cap each text at 600 chars. */
    @Synchronized fun sync(maxNew: Int = 128): Int {
        require(maxNew >= 0)
        prepare()
        if (!enabled || maxNew == 0) return 0
        val recentSlots = (maxNew + 1) / 2
        val backlogSlots = maxNew / 2
        val rows = store.readableDatabase.rawQuery(
            "WITH missing AS (SELECT m.id,m.role,substr(m.content,1,600) AS content,m.created_at FROM messages m LEFT JOIN conversation_vectors v ON v.message_id=m.id WHERE m.role IN ('user','assistant') AND v.message_id IS NULL), recent AS (SELECT id FROM missing ORDER BY id DESC LIMIT ?), backlog AS (SELECT id FROM missing WHERE id NOT IN (SELECT id FROM recent) ORDER BY id ASC LIMIT ?) SELECT id,role,content,created_at FROM missing WHERE id IN (SELECT id FROM recent UNION SELECT id FROM backlog) ORDER BY id ASC",
            arrayOf(recentSlots.toString(), backlogSlots.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) } }
        val staleBefore = db.rawQuery("SELECT count(*) FROM conversation_vectors WHERE message_id NOT IN (SELECT id FROM messages)", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (staleBefore > 0) db.execSQL("DELETE FROM conversation_vectors WHERE message_id NOT IN (SELECT id FROM messages)")
        if (rows.isEmpty()) {
            if (staleBefore > 0) { revision++; cache.clear() }
            return 0
        }
        db.beginTransaction()
        try {
            for (m in rows) {
                val vector = VectorFeatures.encode(m.content)
                db.insertOrThrow("conversation_vectors", null, ContentValues().apply {
                    put("message_id", m.id); put("role", m.role); put("created_at", m.createdAt)
                    put("vector", VectorFeatures.pack(vector)); put("norm", VectorFeatures.norm(vector))
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision++
        cache.clear()
        return rows.size
    }

    @Synchronized fun retrieve(question: String, maxChars: Int = 700, excludeNewest: Int = 0, includeRecent: Boolean = false): List<RecalledSnippet> {
        require(maxChars >= 0 && excludeNewest >= 0)
        if (!enabled || question.isBlank() || maxChars == 0) return emptyList()
        prepare()
        val stale = db.rawQuery("SELECT count(*) FROM conversation_vectors WHERE message_id NOT IN (SELECT id FROM messages)", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (stale > 0) {
            db.execSQL("DELETE FROM conversation_vectors WHERE message_id NOT IN (SELECT id FROM messages)")
            revision++
            cache.clear()
        }
        val qTerms = VectorFeatures.terms(question)
        val key = "$revision|$maxChars|$excludeNewest|$includeRecent|${question.trim().lowercase(java.util.Locale.ROOT)}"
        cache[key]?.let { return it }
        val candidates = if (qTerms.isEmpty()) emptyList() else db.rawQuery(
            "WITH fts_ids AS (SELECT rowid AS id FROM fts_messages WHERE fts_messages MATCH ? ORDER BY rank LIMIT 384), recent_ids AS (SELECT id FROM messages WHERE role IN ('user','assistant') ORDER BY id DESC LIMIT 128), candidate_ids AS (SELECT id FROM fts_ids UNION SELECT id FROM recent_ids) SELECT m.id,m.role,substr(m.content,1,600),m.created_at,v.vector FROM candidate_ids c JOIN conversation_vectors v ON v.message_id=c.id JOIN messages m ON m.id=v.message_id WHERE m.role IN ('user','assistant') AND m.id NOT IN (SELECT id FROM messages WHERE role IN ('user','assistant') ORDER BY id DESC LIMIT ?) ORDER BY m.id DESC LIMIT 512",
            arrayOf(qTerms.take(16).joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }, excludeNewest.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(Candidate(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), VectorFeatures.unpack(c.getBlob(4)))) } }
        val qv = VectorFeatures.encode(question)
        val ranked = candidates.mapNotNull { c ->
            if (c.text.trim().equals(question.trim(), ignoreCase = true)) return@mapNotNull null
            val terms = VectorFeatures.terms(c.text)
            val overlap = qTerms.intersect(terms).size
            if (overlap == 0) return@mapNotNull null
            val cosine = VectorFeatures.cosine(qv, c.vector)
            // Require exact lexical evidence as well as hashed-vector agreement to suppress collisions.
            val score = cosine * (0.65 + 0.35 * (overlap.toDouble() / qTerms.size.coerceAtLeast(1))) * if (c.role == "user") 1.04 else 1.0
            if (score < MIN_SCORE) null else RecalledSnippet(c.id, c.role, c.text, score)
        }.sortedWith(compareByDescending<RecalledSnippet> { it.score }.thenByDescending { it.messageId })
        val picked = mutableListOf<RecalledSnippet>()
        val selectedIds = mutableSetOf<Long>()
        var chars = 0
        fun add(hit: RecalledSnippet) {
            if (picked.size == 2 || hit.messageId in selectedIds) return
            val text = hit.text.take((maxChars - chars).coerceAtLeast(0))
            if (text.isBlank()) return
            picked += hit.copy(text = text)
            selectedIds += hit.messageId
            chars += text.length
        }
        ranked.take(if (includeRecent) 1 else 2).forEach(::add)
        if (includeRecent && picked.size < 2 && chars < maxChars) {
            val recent = db.rawQuery(
                "SELECT m.id,m.role,substr(m.content,1,600) FROM messages m JOIN conversation_vectors v ON v.message_id=m.id WHERE m.role IN ('user','assistant') AND m.id IN (SELECT id FROM messages WHERE role IN ('user','assistant') AND id NOT IN (SELECT id FROM messages WHERE role IN ('user','assistant') ORDER BY id DESC LIMIT ?) ORDER BY id DESC LIMIT 2) ORDER BY m.id ASC",
                arrayOf(excludeNewest.toString())
            ).use { c -> buildList<RecalledSnippet> { while (c.moveToNext()) add(RecalledSnippet(c.getLong(0), c.getString(1), c.getString(2), 0.0)) } }
            recent.forEach { hit ->
                if (hit.text.trim().equals(question.trim(), ignoreCase = true)) return@forEach
                add(hit)
            }
        }
        return picked.toList().also { cache[key] = it }
    }

    @Synchronized fun clear() {
        prepare()
        db.delete("conversation_vectors", null, null)
        revision++
        cache.clear()
    }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply(); synchronized(this) { cache.clear() } }
    fun status(): ConversationMemoryStatus {
        prepare()
        val count = db.rawQuery("SELECT count(*) FROM conversation_vectors", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return ConversationMemoryStatus(enabled, count)
    }

    private data class Candidate(val id: Long, val role: String, val text: String, val createdAt: Long, val vector: FloatArray)
    companion object { private const val MIN_SCORE = .22; private const val FEATURE_VERSION = 2 }
}

/** Deterministic signed feature hashing; lexical retrieval features, not learned semantic embeddings. */
object VectorFeatures {
    const val DIMENSIONS = 256
    private val stopWords = ("a an and are as at be been but by for from had has have he her hers him his i if in into is it its me my of on or our ours she that the their them they this to was we were what when where which who will with you your yours " +
        "और का की के को है हैं में से पर यह वह मैं मेरा मेरी हम आप क्या कैसे कब कहाँ एक नहीं था थी थे Hindi")
        .lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).toSet()
    fun terms(text: String): Set<String> = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFC)).map { it.value }
        .filter { it.length > 1 && it !in stopWords }.toSet()
    fun encode(text: String): FloatArray {
        val v = FloatArray(DIMENSIONS)
        for (term in terms(text)) {
            add(v, "w:$term", 1f)
            val padded = "^$term$"
            if (padded.length >= 3) for (i in 0..padded.length - 3) add(v, "c:${padded.substring(i, i + 3)}", .35f)
        }
        val length = norm(v)
        if (length > 0f) for (i in v.indices) v[i] /= length
        return v
    }
    private fun add(v: FloatArray, feature: String, weight: Float) {
        val digest = MessageDigest.getInstance("SHA-256").digest(feature.toByteArray(Charsets.UTF_8))
        val n = ((digest[0].toInt() and 255) shl 8) or (digest[1].toInt() and 255)
        v[n % DIMENSIONS] += if ((digest[2].toInt() and 1) == 0) weight else -weight
    }
    fun norm(v: FloatArray): Float = sqrt(v.sumOf { it.toDouble() * it }.toFloat())
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == DIMENSIONS && b.size == DIMENSIONS)
        val an = norm(a); val bn = norm(b)
        if (an == 0f || bn == 0f) return 0.0
        return (a.indices.sumOf { a[it].toDouble() * b[it] } / (an * bn)).coerceIn(-1.0, 1.0)
    }
    fun pack(v: FloatArray): ByteArray {
        require(v.size == DIMENSIONS)
        return ByteBuffer.allocate(DIMENSIONS * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
    }
    fun unpack(bytes: ByteArray): FloatArray {
        require(bytes.size == DIMENSIONS * 4)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(DIMENSIONS) { b.float }
    }
}
