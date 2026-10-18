package im.mrgreen.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class PackException(message: String = "Pack could not be processed") : Exception(message)
class PendingPack internal constructor(val manifest: JSONObject, internal val payload: Map<String, ByteArray>, internal val token: String, internal val approvedDigest: String = "", internal val reservedBytes: Long = 0)
data class InstalledPack(val id: String, val name: String, val version: String, val bytes: Long, val permissions: List<String>, val tools: List<String>)
class PackExecution internal constructor(val id: String, internal val token: String)
data class PackManifest(val json: JSONObject, val id: String, val version: String, val layer: Int, val entries: List<String>, val tools: Set<String>)

/** Secure, app-private package lifecycle. The caller owns the approval UI and all host side effects. */
class PackManager(
    private val root: File,
    private val dryRun: (JSONObject, Map<String, ByteArray>) -> Unit = { _, _ -> },
    private val execute: (JSONObject, Map<String, ByteArray>, String) -> Unit = { _, _, _ -> },
    private val availableTools: Set<String> = setOf("plugin.echo", "time.now"),
    private val requiredPermissions: Map<String, String> = mapOf("plugin.echo" to "plugin.output", "time.now" to "time.read"),
    private val trust: PackTrust = PackTrust(File(root.parentFile ?: root, "pack-owner-key")),
    private val memoryLimitBytes: Int = MAX_TOTAL_BYTES
) {
    private val staging = File(root, ".staging")
    private val live = File(root, "installed")
    private val stateFile = File(root, ".run-state")
    private val pending = ConcurrentHashMap<String, PendingPack>()
    private val executions = HashMap<String, PackExecution>()
    private var stagedBytes = 0L
    private val failures = HashMap<String, Int>()
    private var safeMode = false
    private val memory = HashMap<String, MutableMap<String, Any?>>()
    private var memoryLoaded = false
    val builder: PackBuilder by lazy { PackBuilder(trust) }

    init {
        root.mkdirs(); live.mkdirs(); staging.mkdirs()
        try { recoverActivation(); recoverRollback(); readState(); recoverInterruptedRun(); recoverDryRun() }
        catch (_: Exception) { safeMode = true; runCatching { writeState() } }
    }

    @Synchronized fun prepare(input: InputStream): PendingPack = try {
        val files = LinkedHashMap<String, ByteArray>()
        var aggregate = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_FILES + 1 && !entry.isDirectory) { "bad archive" }
                val name = entry.name
                checkPath(name)
                require(name !in files && (name == "manifest.json" || allowedExtension(name))) { "bad file" }
                val limit = when {
                    name == "manifest.json" -> MAX_MANIFEST_BYTES
                    name.startsWith("flows/") -> 256 * 1024
                    name.startsWith("scripts/") -> 1024 * 1024
                    name.startsWith("tests/") -> 64 * 1024
                    else -> MAX_FILE_BYTES
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = zip.read(buf); if (n < 0) break
                    aggregate += n
                    require(n <= limit - out.size() && aggregate <= MAX_PACK_BYTES && durableBytes() + stagedBytes + aggregate <= MAX_TOTAL_BYTES) { "size limit" }
                    out.write(buf, 0, n)
                }
                files[name] = out.toByteArray()
                zip.closeEntry()
            }
        }
        val manifestBytes = files.remove("manifest.json") ?: error("missing manifest")
        validateJsonDepth(manifestBytes)
        files.filterKeys { it.endsWith(".json") }.values.forEach(::validateJsonDepth)
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        validateManifest(manifest, files)
        val candidate = PendingPack(manifest, files.toMap(), UUID.randomUUID().toString(), approvalDigest(manifest, files), aggregate)
        require(durableBytes() + stagedBytes + aggregate <= MAX_TOTAL_BYTES)
        validateToolPermissions(manifest)
        invokeDryRun(manifest, candidate.payload)
        pending[candidate.token] = candidate
        stagedBytes += aggregate
        candidate
    } catch (_: Exception) { throw PackException() }

    @Synchronized fun approveInstall(candidate: PendingPack): InstalledPack = try {
        val valid = pending.remove(candidate.token)
        require(valid === candidate) { "invalid approval token" }
        stagedBytes -= candidate.reservedBytes
        validateManifest(candidate.manifest, candidate.payload)
        require(candidate.approvedDigest.isNotEmpty() && candidate.approvedDigest == approvalDigest(candidate.manifest, candidate.payload))
        val info = metadata(candidate.manifest, candidate.payload)
        validateToolPermissions(candidate.manifest)
        invokeDryRun(candidate.manifest, candidate.payload)
        require(durableBytes() + stagedBytes + info.bytes + candidate.manifest.toString().toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES) { "storage cap" }
        val safeId = candidate.manifest.getString("id")
        val dir = File(live, safeId)
        val previous = File(root, ".previous-$safeId")
        if (dir.isDirectory) {
            val current = runCatching { JSONObject(File(dir, "manifest.json").readText()).getString("version") }.getOrNull()
            require(current != candidate.manifest.getString("version")) { "version overwrite" }
        }
        val journal = File(root, ".activation-journal")
        writeAtomic(journal, safeId)
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
            deleteRecursively(previous)
            check(dir.renameTo(previous))
        }
        val temp = File(root, ".activate-${UUID.randomUUID()}"); temp.mkdirs()
        candidate.payload.forEach { (path, bytes) -> val file = File(temp, path); file.parentFile?.mkdirs(); file.writeBytes(bytes) }
        File(temp, "manifest.json").writeText(candidate.manifest.toString())
        if (!temp.renameTo(dir)) { deleteRecursively(temp); if (previous.exists()) previous.renameTo(dir); error("activation") }
        journal.delete()
        info
    } catch (_: Exception) { throw PackException() }

    @Synchronized fun discard(candidate: PendingPack) { if (pending.remove(candidate.token) === candidate) stagedBytes -= candidate.reservedBytes }
    @Synchronized fun listInstalled(): List<InstalledPack> = (live.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { dir -> runCatching { load(dir.name).first }.getOrNull() }
    @Synchronized fun usageBytes(): Long = durableBytes() + stagedBytes
    @Synchronized fun remove(id: String) { require(validId(id)); deleteRecursively(File(live, id)); deleteRecursively(File(root, ".previous-$id")); failures.remove(id); memory.remove(id); memoryFile(id).delete(); writeState() }
    @Synchronized fun installedEntries(id: String): List<String> = load(id).third.keys.filter { (it.startsWith("flows/") && it.endsWith(".json")) || (it.startsWith("scripts/") && it.endsWith(".js")) }.sorted()
    fun ownerFingerprint(publicKey: java.security.PublicKey): String = trust.fingerprint(publicKey)
    /** Call only after UI has explicitly displayed the fingerprint and received owner confirmation. */
    fun approveOwnerKeyImport(publicKey: java.security.PublicKey, confirmedFingerprint: String) {
        require(trust.fingerprint(publicKey).equals(confirmedFingerprint, ignoreCase = true)); trust.pinOwnerKey(publicKey)
    }

    @Synchronized fun rollback(id: String): InstalledPack = try {
        require(validId(id)); val dir=File(live,id); val prev=File(root,".previous-$id")
        require(dir.isDirectory && prev.isDirectory)
        val m=JSONObject(File(prev,"manifest.json").readText()); val payload=readPayload(prev)
        require(m.getString("id")==id); validateManifest(m,payload); validateToolPermissions(m); invokeDryRun(m,payload)
        val cm=JSONObject(File(dir,"manifest.json").readText()); val cp=readPayload(dir); validateManifest(cm,cp)
        val temp=File(root,".rollback-${UUID.randomUUID()}")
        val journal=File(root,".rollback-journal"); writeAtomic(journal,id+"\n"+temp.name)
        check(dir.renameTo(temp))
        if (!prev.renameTo(dir)) { temp.renameTo(dir); error("rollback") }
        check(temp.renameTo(prev)); journal.delete(); metadata(m,payload)
    } catch (_:Exception) { throw PackException() }

    @Synchronized fun run(id: String, entry: String) {
        val (info, manifest, files) = try { require(!safeMode); val loaded = load(id); validateManifest(loaded.second, loaded.third); require(entry in installedEntries(id)); require(!File(live,id).resolve(".disabled").exists()); loaded } catch (_: Exception) { throw PackException() }
        val run = beforeRun(id)
        try { execute(manifest, files, entry); completeRun(run, true) } catch (_: Exception) { completeRun(run, false); throw PackException() }
    }

    /** Persist a crash sentinel before handing control to an untrusted runtime. */
    @Synchronized fun beforeRun(id: String): PackExecution {
        require(validId(id) && !safeMode)
        load(id); val run=PackExecution(id,UUID.randomUUID().toString()); writeAtomic(File(stateFile.path+".pending"),run.token+"\n"+id); executions[run.token]=run; return run
    }
    @Synchronized fun completeRun(run: PackExecution, success: Boolean) {
        val p = File(stateFile.path + ".pending")
        require(validId(run.id) && executions.remove(run.token) === run && p.isFile && p.readText()==run.token+"\n"+run.id)
        if (success) failures.remove(run.id) else failures[run.id]=(failures[run.id]?:0)+1
        if ((failures[run.id] ?: 0) >= FAILURE_LIMIT) { safeMode = true; File(live, run.id).mkdirs(); File(live, run.id).resolve(".disabled").writeText("disabled") }
        p.delete(); writeState()
    }
    @Synchronized fun recoverInterruptedRun() {
        val p = File(stateFile.path + ".pending")
        if (p.isFile) { val fields=p.readText().split("\n"); require(fields.size==2 && validId(fields[1])); val id=fields[1]; failures[id] = (failures[id] ?: 0) + 1; if ((failures[id] ?: 0) >= FAILURE_LIMIT) { safeMode = true; File(live, id).mkdirs(); File(live, id).resolve(".disabled").writeText("disabled") }; p.delete(); writeState() }
    }
    @Synchronized fun isSafeMode() = safeMode
    @Synchronized fun enterSafeMode() { safeMode = true; writeState() }
    @Synchronized fun exitSafeMode() { safeMode = false; writeState() }

    @Synchronized fun getMemory(id:String,key:String):Any? { require(validId(id)&&key.length in 1..MAX_MEMORY_KEY); ensureMemoryLoaded(); return memory[id]?.get(key) }
    @Synchronized fun setMemory(id:String,key:String,value:Any?) {
        require(validId(id)&&key.length in 1..MAX_MEMORY_KEY)
        ensureMemoryLoaded()
        val updated=HashMap(memory[id]?:emptyMap()); updated[key]=JSONObject.wrap(value)
        fun encode(m:Map<String,Any?>)=JSONObject().also { o->m.forEach { (k,v)->o.put(k,v) } }.toString()
        val current=memory.values.sumOf { encode(it).toByteArray(Charsets.UTF_8).size }
        val prior=memory[id]?.let { encode(it).toByteArray(Charsets.UTF_8).size }?:0
        val text=encode(updated); require(text.toByteArray(Charsets.UTF_8).size<=MAX_PACK_MEMORY_BYTES && current-prior+text.toByteArray(Charsets.UTF_8).size<=memoryLimitBytes)
        require(durableBytes() + stagedBytes + text.toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES)
        writeAtomic(memoryFile(id),text); memory[id]=updated
    }

    private fun validateManifest(m: JSONObject, files: Map<String, ByteArray>) {
        val keys = setOf("id","name","version","api_min","api_max","layers","permissions","tools","triggers","files","signer_algorithm","signer_key","signature_algorithm","signature")
        require(m.keys().asSequence().toSet() == keys)
        val id = m.getString("id"); require(validId(id))
        require(m.get("name") is String && m.get("version") is String)
        require(m.getString("name").length in 1..100 && m.getString("version").length in 1..40)
        val min = strictInt(m,"api_min"); val max = strictInt(m,"api_max"); require(min in 1..1 && max >= min && min <= 1)
        val layers = m.getJSONArray("layers"); require(layers.length() in 1..2)
        val layerSet = (0 until layers.length()).map { strictInt(layers,it) }.toSet(); require(layerSet.size == layers.length() && layerSet.all { it == 1 || it == 2 })
        val perms = stringArray(m.getJSONArray("permissions"), 32); require(perms.all { it.length <= 80 && it in setOf("plugin.output","time.read","plugin.memory","plugin.log") })
        val tools = stringArray(m.getJSONArray("tools"), 32).toSet(); require(tools.size <= 32 && tools.all { it in availableTools && requiredPermissions.containsKey(it) && requiredPermissions[it] in perms })
        val triggers=m.getJSONArray("triggers"); require(triggers.length() <= 32)
        for (i in 0 until triggers.length()) require(triggers.get(i) is JSONObject || triggers.get(i) is String)
        val hashes = m.getJSONObject("files"); require(hashes.length() == files.size && files.isNotEmpty() && files.size <= MAX_FILES)
        val executable = files.keys.filter { it.startsWith("flows/") && it.endsWith(".json") || it.startsWith("scripts/") && it.endsWith(".js") }
        require(executable.size <= 32)
        files.forEach { (path, data) -> require(hashes.has(path) && hashes.get(path) is String && hashes.getString(path) == PackTrust.sha256(data)) }
        val golden = files.filterKeys { it.startsWith("tests/") && it.endsWith(".json") }
        require(golden.isNotEmpty() && golden.size <= 32)
        golden.values.forEach { data ->
            require(data.size <= 64 * 1024)
            val suite = JSONObject(String(data, Charsets.UTF_8)); val cases = suite.getJSONArray("cases")
            require(cases.length() in 1..20)
            for (i in 0 until cases.length()) { val test = cases.getJSONObject(i); require(test.length() in 2..8 && test.has("entry") && test.has("expected")); require(test.getString("entry") in files) }
        }
        val key = trust.decodePublicKey(m.getString("signer_algorithm"), m.getString("signer_key"))
        require(trust.isPinned(key))
        val signature = m.getString("signature"); val alg = m.getString("signature_algorithm")
        require(trust.verify(canonical(m), signature, alg, key))
        val layer = if (layerSet.contains(2)) 2 else 1
        require(files.keys.all { (layer == 2 && it.endsWith(".js")) || it.endsWith(".json") || it.endsWith(".md") || it.endsWith(".txt") })
    }

    private fun canonical(m: JSONObject): ByteArray = PackCanonical.render(JSONObject(m.toString()).also { it.remove("signature") }).toByteArray(Charsets.UTF_8)
    private fun approvalDigest(m:JSONObject, files:Map<String,ByteArray>):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256"); digest.update(canonical(m))
        files.toSortedMap().forEach { (path,data)->digest.update(path.toByteArray(Charsets.UTF_8));digest.update(0);digest.update(data) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun metadata(m: JSONObject, files: Map<String, ByteArray>) = InstalledPack(m.getString("id"),m.getString("name"),m.getString("version"),files.values.sumOf { it.size.toLong() },stringArray(m.getJSONArray("permissions"),32),stringArray(m.getJSONArray("tools"),32))
    private fun load(id: String): Triple<InstalledPack, JSONObject, Map<String,ByteArray>> {
        require(validId(id)); val dir=File(live,id); require(dir.canonicalFile.parentFile == live.canonicalFile)
        val m=JSONObject(File(dir,"manifest.json").readText()); val payload=readPayload(dir)
        require(m.getString("id") == id); validateManifest(m,payload)
        val info=metadata(m,payload); return Triple(info,m,payload)
    }
    private fun readPayload(dir: File): Map<String,ByteArray> = dir.walkTopDown().filter { it.isFile && it.name != "manifest.json" && !it.name.startsWith(".") }.onEach { require(it.length()<=MAX_FILE_BYTES && it.canonicalPath.startsWith(dir.canonicalPath+File.separator)) }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
    private fun manifestEntries(m: JSONObject): List<String> = (0 until m.getJSONObject("files").length()).map { m.getJSONObject("files").names()!!.getString(it) }
    private fun durableBytes(): Long = live.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
        (root.listFiles() ?: emptyArray()).filter { it.name.startsWith(".previous-") || it.name.startsWith(".activate-") || it.name.startsWith(".rollback-") }.sumOf { f -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() } } +
        (root.listFiles() ?: emptyArray()).filter { it.name.startsWith(".memory-") }.sumOf { it.length() }
    private fun writeState() { writeAtomic(stateFile,JSONObject().put("safe",safeMode).put("failures",JSONObject().also { o->failures.forEach { (k,v)->o.put(k,v) } }).toString()) }
    private fun writeAtomic(file:File,text:String) { val tmp=File(file.parentFile,file.name+".tmp"); FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }; if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) } }
    private fun memoryFile(id:String)=File(root,".memory-$id.json")
    private fun invokeDryRun(m:JSONObject, payload:Map<String,ByteArray>) {
        val id=m.getString("id"); require(validId(id)); val marker=File(root,".dryrun-pending"); writeAtomic(marker,id)
        try { dryRun(m,payload) } finally { marker.delete() }
    }
    private fun recoverDryRun() {
        val marker=File(root,".dryrun-pending"); if (!marker.isFile) return
        val id=marker.readText(); require(validId(id)); failures[id]=(failures[id]?:0)+1
        if ((failures[id]?:0)>=FAILURE_LIMIT) safeMode=true
        marker.delete(); writeState()
    }
    private fun ensureMemoryLoaded() {
        if (memoryLoaded) return
        try { readMemory(); memoryLoaded=true }
        catch (_: Exception) { memory.clear(); safeMode=true; writeState(); throw IllegalStateException("Plugin memory unavailable") }
    }
    private fun readMemory() { (root.listFiles()?: emptyArray()).filter { it.name.startsWith(".memory-") && it.name.endsWith(".json") }.forEach { f->val id=f.name.removePrefix(".memory-").removeSuffix(".json"); require(validId(id)&&f.length()<=MAX_PACK_MEMORY_BYTES); val o=JSONObject(f.readText()); val m=HashMap<String,Any?>(); o.keys().forEach { k->require(k.length in 1..MAX_MEMORY_KEY); m[k]=o.get(k) }; memory[id]=m }; require(memory.values.sumOf { m->JSONObject().also { o->m.forEach { (k,v)->o.put(k,v) } }.toString().toByteArray(Charsets.UTF_8).size }<=memoryLimitBytes) }
    private fun strictInt(o:JSONObject,k:String):Int { val v=o.get(k); require(v is Int || v is Long && v.toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); return (v as Number).toInt() }
    private fun strictInt(a:JSONArray,i:Int):Int { val v=a.get(i); require(v is Int || v is Long && v.toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); return (v as Number).toInt() }
    private fun validateToolPermissions(m:JSONObject) { val ps=stringArray(m.getJSONArray("permissions"),32).toSet(); val ts=stringArray(m.getJSONArray("tools"),32); require(ps.all { it in setOf("plugin.output","time.read","plugin.memory","plugin.log") } && ts.all { it in availableTools && requiredPermissions[it] in ps }) }

    private fun recoverActivation() {
        val journal = File(root, ".activation-journal")
        if (!journal.isFile) return
        val id = runCatching { journal.readText().takeIf { validId(it) } }.getOrNull()
        if (id != null) {
            val dir = File(live,id); val previous = File(root,".previous-$id")
            if (!dir.isDirectory && previous.isDirectory) previous.renameTo(dir)
            (root.listFiles() ?: emptyArray()).filter { it.name.startsWith(".activate-") }.forEach { deleteRecursively(it) }
        }
        journal.delete()
    }
    private fun recoverRollback() {
        val journal=File(root,".rollback-journal")
        if (!journal.isFile) return
        val fields=journal.readText().split("\n")
        require(fields.size==2 && validId(fields[0]) && fields[1].matches(Regex("\\.rollback-[a-f0-9-]{36}")))
        val dir=File(live,fields[0]); val previous=File(root,".previous-${fields[0]}"); val temp=File(root,fields[1])
        if (!dir.exists()) { require(previous.isDirectory); check(previous.renameTo(dir)) }
        if (temp.exists()) { require(!previous.exists()); check(temp.renameTo(previous)) }
        journal.delete()
    }
    private fun readState() { if (!stateFile.exists()) return; val j=JSONObject(stateFile.readText()); require(j.get("safe") is Boolean); safeMode=j.getBoolean("safe"); val f=j.getJSONObject("failures"); f.keys().forEach { require(validId(it)); val n=f.get(it); require(n is Int && n>=0); failures[it]=n as Int } }
    private fun checkPath(path:String) { require(path.isNotEmpty() && path.length<=MAX_PATH && !path.startsWith("/") && '\\' !in path && '\u0000' !in path && path.split('/').all { it.isNotEmpty() && !it.startsWith(".") } && !Regex("^[A-Za-z]:").containsMatchIn(path)) }
    private fun allowedExtension(path:String):Boolean {
        val top=path.substringBefore('/')
        return top in setOf("flows","templates","rules","prompts","scripts","tests","docs","knowledge","themes","routines") &&
            path.substringAfterLast('.',"").lowercase() in setOf("json","js","md","txt") &&
            !(path.endsWith(".js") && top!="scripts") && !(top=="scripts" && !path.endsWith(".js"))
    }
    private fun validateJsonDepth(bytes:ByteArray) {
        val text=String(bytes,Charsets.UTF_8); var depth=0; var quoted=false; var escaped=false
        for (c in text) { if (quoted) { if (escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
            else when(c) { '"'->quoted=true; '{','['->{depth++; require(depth<=32)}; '}',']'->{depth--; require(depth>=0)} }
        }
        require(!quoted && depth==0)
    }
    private fun stringArray(a:JSONArray, max:Int)= (0 until a.length()).map { require(a.get(it) is String); a.getString(it).also { s->require(s.isNotBlank() && s.length<=128) } }.also { require(it.size<=max && it.toSet().size==it.size) }
    private fun validId(id:String)=id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
    private fun deleteRecursively(f:File) { if(f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }; f.delete() }
    companion object { const val MAX_TOTAL_BYTES=200*1024*1024; const val MAX_PACK_BYTES=32*1024*1024; const val MAX_FILE_BYTES=32*1024*1024; const val MAX_MANIFEST_BYTES=256*1024; const val MAX_FILES=512; const val MAX_PATH=240; const val MAX_MEMORY_KEY=128; const val MAX_PACK_MEMORY_BYTES=1024*1024; const val FAILURE_LIMIT=3 }
}
