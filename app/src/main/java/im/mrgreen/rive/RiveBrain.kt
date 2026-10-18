package im.mrgreen.rive

import android.util.AtomicFile
import com.rivescript.Config
import com.rivescript.RiveScript
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** Text-only RiveScript: no regex triggers, tags, objects, scripts, redirects or actions. */
data class PreparedRules internal constructor(val text: String, val count: Int)
data class RuleStatus(val imported: Int, val learned: Int, val warning: String?)
data class LearnedRule(val trigger: String, val answer: String)
data class RuleReply(val text: String, val learned: Boolean)

class RiveBrain(directory: File) {
    companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_RULES = 500
        private const val DIVIDER = "// MR_GREEN_LEARNED_REPLIES"
        private const val HEADER = "! version = 2.0\n\n"
        private val triggerPattern = Regex("[\\p{L}\\p{M}\\p{N}]+(?: [\\p{L}\\p{M}\\p{N}]+)*")

        /** Only trailing sentence punctuation is ignored; arithmetic/operators are never stripped. */
        fun normalizeQuestion(question: String): String? {
            val value = Normalizer.normalize(question, Normalizer.Form.NFKC).trim()
                .lowercase(Locale.ROOT).trimEnd('.', '!', '?').trim().replace(Regex("\\s+"), " ")
            return value.takeIf { it.length in 1..256 && triggerPattern.matches(it) }
        }
        private fun safeAnswer(answer: String): Boolean = answer.isNotBlank() && answer.length <= 4096 &&
            !answer.contains("*/") && !answer.contains(" // ") &&
            answer.none { it in "<>{}\\" || (it.isISOControl() && it != '\n' && it != '\t') }
    }

    private val storage = AtomicFile(File(directory, "rules.rive"))
    private data class Snapshot(val imported: Map<String, String>, val learned: Map<String, String>, val engine: RiveScript)
    private var snapshot = compile(emptyMap(), emptyMap())
    private var warning: String? = null
    private var revision = 0L

    init {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create private rule storage" }
        try { reload() } catch (_: Exception) { warning = "Saved rules could not be loaded; no rules are active. Import a valid file or repair the saved rules." }
    }

    @Synchronized fun status() = RuleStatus(snapshot.imported.size, snapshot.learned.size, warning)
    @Synchronized fun revision(): Long = revision
    @Synchronized fun importedText(): String = HEADER + serialize(snapshot.imported)
    @Synchronized fun learnedRules(): List<LearnedRule> = snapshot.learned.map { LearnedRule(it.key, it.value) }
    @Synchronized fun exportText(): String = HEADER + serialize(snapshot.learned + snapshot.imported)

    fun prepare(input: InputStream): PreparedRules {
        val rules = parse(readBounded(input))
        compile(rules, emptyMap()) // Real strict interpreter validation and sort before any write.
        return PreparedRules(HEADER + serialize(rules), rules.size)
    }

    @Synchronized fun replace(candidate: PreparedRules) {
        val imported = parse(candidate.text) // Do not trust a forged or stale prepared object.
        val learned = snapshot.learned.filterKeys { it !in imported }
        commit(compile(imported, learned))
    }

    @Synchronized fun reload(): RuleStatus {
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return status()
        try {
            val text = storage.openRead().use(::readBounded)
            val parts = text.split("\n$DIVIDER\n")
            require(parts.size == 2) { "Saved rule file has an invalid source boundary" }
            val next = compile(parse(parts[0]), parse(parts[1]))
            snapshot = next
            revision++
            warning = null
            return status()
        } catch (e: Exception) {
            warning = "Reload rejected; previous working rules remain active."
            throw IllegalArgumentException(warning, e)
        }
    }

    @Synchronized fun reply(question: String): RuleReply? {
        val trigger = normalizeQuestion(question) ?: return null
        val current = snapshot
        if (trigger !in current.imported && trigger !in current.learned) return null
        // No interpreter is mutated while another reply or swap runs. There are no executable tags.
        val reply = current.engine.reply("owner", trigger)
        return RuleReply(reply, trigger !in current.imported)
    }

    /** Caller must gate this on explicit learning consent and successful, non-truncated local generation. */
    @Synchronized fun learn(question: String, answer: String, expectedRevision: Long,
                            completed: Boolean, cancelled: Boolean): Boolean {
        if (!completed || cancelled || expectedRevision != revision) return false
        val trigger = normalizeQuestion(question) ?: return false
        val literal = answer.trim().replace("\r\n", "\n")
        if (!safeAnswer(literal) || trigger in snapshot.imported || trigger in snapshot.learned) return false
        if (snapshot.imported.size + snapshot.learned.size >= MAX_RULES) return false
        commit(compile(snapshot.imported, snapshot.learned + (trigger to literal)))
        return true
    }

    @Synchronized fun forget(trigger: String) {
        if (trigger in snapshot.learned) commit(compile(snapshot.imported, snapshot.learned - trigger))
    }
    @Synchronized fun clearLearned() { commit(compile(snapshot.imported, emptyMap())) }

    private fun commit(next: Snapshot) {
        val bytes = (HEADER + serialize(next.imported) + "\n$DIVIDER\n" + serialize(next.learned)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Rule storage exceeds 256 KiB; remove old learned replies first" }
        val output = storage.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
        } catch (e: Exception) {
            storage.failWrite(output)
            throw e
        }
        storage.finishWrite(output)
        check(storage.openRead().use { readBounded(it).toByteArray(Charsets.UTF_8) }.contentEquals(bytes)) {
            "Atomic rule replacement could not be verified"
        }
        snapshot = next // Publish only after the durable atomic write succeeds.
        revision++
        warning = null
    }

    private fun compile(imported: Map<String, String>, learned: Map<String, String>): Snapshot {
        require(imported.size + learned.size <= MAX_RULES) { "At most 500 rules are allowed" }
        val engine = RiveScript(Config.newBuilder().strict(true).utf8(true).throwExceptions(true).depth(10).build())
        // No object handler, subroutine, host tool, network or filesystem capability is registered.
        engine.stream(HEADER + serialize(learned + imported))
        engine.sortReplies()
        return Snapshot(imported.toMap(), learned.toMap(), engine)
    }

    private fun serialize(rules: Map<String, String>): String = rules.entries.joinToString("") {
        "+ ${it.key}\n- ${it.value.replace("\n", "\\n")}\n\n"
    }

    private fun parse(text: String): Map<String, String> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Rule file exceeds 256 KiB" }
        val rules = linkedMapOf<String, String>()
        var pending: String? = null
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            fun invalid(reason: String): Nothing = throw IllegalArgumentException("Line ${index + 1}: $reason")
            if (line.isEmpty() || line.startsWith("//")) return@forEachIndexed
            if (line == "! version = 2.0") return@forEachIndexed
            if (line.startsWith("+ ")) {
                if (pending != null) invalid("previous trigger has no reply")
                val trigger = line.substring(2).trim()
                if (trigger.length !in 1..256 || !triggerPattern.matches(trigger) || trigger != trigger.lowercase(Locale.ROOT) ||
                    normalizeQuestion(trigger) != trigger) invalid("use a literal lowercase trigger; patterns and tags are not supported")
                if (trigger in rules) invalid("duplicate trigger")
                pending = trigger
            } else if (line.startsWith("- ")) {
                val trigger = pending ?: invalid("reply must follow one trigger")
                val encoded = line.substring(2)
                if (encoded.replace("\\n", "").contains('\\')) invalid("only \\n newline escapes are supported")
                val answer = encoded.replace("\\n", "\n")
                if (!safeAnswer(answer)) invalid("reply must be plain text, at most 4096 characters, without tags, braces or control characters")
                rules[trigger] = answer
                if (rules.size > MAX_RULES) invalid("at most 500 rules are allowed")
                pending = null
            } else invalid("only + trigger, - reply, // comments and ! version = 2.0 are supported")
        }
        require(pending == null) { "Last trigger has no reply" }
        return rules
    }

    private fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                output.write(one)
            } else output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "Rule file exceeds 256 KiB" }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Rule file must contain valid UTF-8 text", e)
        }
    }
}
