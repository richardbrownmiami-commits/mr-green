package im.mrgreen.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates drafts only; callers must still pass output through PackManager.prepare and explicit approval. */
class PackBuilder(private val trust: PackTrust) {
    fun build(id: String, name: String, version: String, files: Map<String, ByteArray>, tools: List<String> = emptyList(), permissions: List<String> = emptyList(), layer: Int = 1): ByteArray {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) && layer in 1..2)
        require(files.isNotEmpty() && files.size <= PackManager.MAX_FILES)
        files.forEach { (path, data) -> require(path.isNotBlank() && !path.startsWith("/") && ".." !in path.split('/') && '\\' !in path && data.size <= PackManager.MAX_FILE_BYTES) }
        if (layer == 1) require(files.keys.all { it.endsWith(".json") || it.endsWith(".md") || it.endsWith(".txt") })
        if (layer == 2) require(files.keys.all { it.endsWith(".js") || it.endsWith(".json") })
        val hashes=JSONObject().also { o->files.toSortedMap().forEach { (p,b)->o.put(p,PackTrust.sha256(b)) } }
        val m=JSONObject().put("id",id).put("name",name).put("version",version).put("api_min",1).put("api_max",1)
            .put("layers",JSONArray().put(layer)).put("permissions",JSONArray(permissions)).put("tools",JSONArray(tools)).put("triggers",JSONArray()).put("files",hashes)
        // Local builder's identity is the device's Android Keystore signing identity.
        val (_,pub)=trust.sign(canonical(m))
        m.put("signer_algorithm","RSA").put("signer_key",pub).put("signature_algorithm","SHA256withRSA")
        val (sig,_)=trust.sign(canonical(m))
        m.put("signature",sig)
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(m.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            files.toSortedMap().forEach { (path,data)->zip.putNextEntry(ZipEntry(path)); zip.write(data); zip.closeEntry() }
        }
        require(out.size() <= PackManager.MAX_TOTAL_BYTES)
        return out.toByteArray()
    }
    fun safeDemoFlow(id: String = "demo.flow"): ByteArray = build(id,"Safe demo","1.0.0",mapOf("flows/demo.json" to """{"steps":[{"op":"set","name":"message","value":"Hello from a local demo"},{"op":"verify","value":"${'$'}message","equals":"Hello from a local demo"}]}""".toByteArray(),"tests/demo.json" to """{"cases":[{"entry":"flows/demo.json","expected":{"ok":true}}]}""".toByteArray()),layer=1)
    fun safeDemoScript(id: String = "demo.script"): ByteArray = build(id,"Safe script demo","1.0.0",mapOf("scripts/demo.js" to "// No host effects; host adapters are provided by the app.\n'local demo';".toByteArray(),"tests/demo.json" to """{"cases":[{"entry":"scripts/demo.js","expected":"local demo"}]}""".toByteArray()),layer=2)
    private fun canonical(m:JSONObject):ByteArray {
        return PackCanonical.render(m).toByteArray(Charsets.UTF_8)
    }
}
