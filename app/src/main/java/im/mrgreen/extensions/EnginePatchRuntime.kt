package im.mrgreen.extensions

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

/** Host JNI contract v1: the six LlamaEngine native methods, identical signatures and packaged dependencies.
 * Only an independently trusted owner may sign a native replacement. AI-generated code is never accepted.
 */
class EnginePatchManager(context: Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("engine_patch_v1",Context.MODE_PRIVATE)
    private val root=File(app.filesDir,"engine-patches").apply { mkdirs() }
    data class Candidate(val manifest:EnginePatchManifest,val artifact:File,val ownerDer:ByteArray) {
        val ownerFingerprint get()=CompanionTrust.sha256(ownerDer)
    }
    fun inspect(input:java.io.InputStream):Candidate {
        val pending=File(root,"review").apply { deleteRecursively(); mkdirs() }
        var manifest:ByteArray?=null; var owner:ByteArray?=null; var artifact:File?=null
        ZipInputStream(input).use { zip ->
            var entry=zip.nextEntry; var count=0
            while(entry!=null) {
                require(++count<=3 && !entry.isDirectory) { "Unexpected patch entry" }
                when(entry.name) {
                    "manifest.json" -> { require(manifest==null); manifest=readSmall(zip,16384) }
                    "owner.der" -> { require(owner==null); owner=readSmall(zip,8192) }
                    "engine.so" -> { require(artifact==null); val target=File(pending,"engine.so"); target.outputStream().use { out -> val bytes=ByteArray(8192); var total=0L; while(true) { val n=zip.read(bytes); if(n<0)break; total+=n; require(total<=150L*1024*1024); out.write(bytes,0,n) } }; artifact=target }
                    else -> error("Only manifest.json, owner.der and engine.so are allowed")
                }
                zip.closeEntry(); entry=zip.nextEntry
            }
        }
        val j=JSONObject(String(manifest?:error("Missing manifest"),Charsets.UTF_8))
        require(j.keys().asSequence().toSet()==setOf("version","abi","soname","sha256","signature"))
        val m=EnginePatchManifest(j.getString("version"),j.getString("abi"),j.getString("soname"),j.getString("sha256"),j.getString("signature"))
        require(m.version.startsWith("9-") && m.soname=="libmrgreen_engine.so") { "Patch must target app versionCode 9 / mrgreen-jni-v1" }
        val candidate=Candidate(m,artifact?:error("Missing engine"),owner?:error("Missing owner key"))
        // Inspection verifies cryptography but does not pin the key or activate code.
        EnginePatchPolicy(key(candidate.ownerDer),File(pending,"checked")){true}.verifyAndStage(candidate.artifact,m)
        return candidate
    }
    fun installAfterApproval(candidate:Candidate, independentlyVerifiedOwner:Boolean) {
        val pinned=prefs.getString("owner",null)
        require(pinned==candidate.ownerFingerprint || (pinned==null && independentlyVerifiedOwner)) { "Owner key does not match the pinned signer" }
        require(!File(root,"versions/pending-${candidate.manifest.version}.so").exists()) { "This version was already staged; use a unique signed version" }
        val staged=EnginePatchPolicy(key(candidate.ownerDer),File(root,"versions")){true}.verifyAndStage(candidate.artifact,candidate.manifest)
        val metadata=File(staged.path+".json")
        metadata.writeText(JSONObject().put("version",candidate.manifest.version).put("abi",candidate.manifest.abi).put("soname",candidate.manifest.soname).put("sha256",candidate.manifest.sha256).put("signature",candidate.manifest.signatureBase64).put("owner",java.util.Base64.getEncoder().encodeToString(candidate.ownerDer)).toString())
        staged.setReadable(false,false); staged.setReadable(true,true); staged.setWritable(false,false)
        check(prefs.edit().putString("owner",candidate.ownerFingerprint).putString("previous",prefs.getString("active",null)).putString("active",staged.name).putInt("failures",0).putBoolean("inflight",false).putBoolean("safe",false).commit())
    }
    fun prepare():String? {
        if(prefs.getBoolean("inflight",false)) prefs.edit().putInt("failures",prefs.getInt("failures",0)+1).putBoolean("inflight",false).commit()
        if(prefs.getInt("failures",0)>=3) { rollback(); prefs.edit().putBoolean("safe",true).commit(); return null }
        if(prefs.getBoolean("safe",false))return null
        val active=prefs.getString("active",null)?:return null
        require(active.matches(Regex("pending-[A-Za-z0-9._-]+\\.so")))
        val artifact=File(root,"versions/$active")
        return try {
            val j=JSONObject(File(artifact.path+".json").readText())
            val owner=java.util.Base64.getDecoder().decode(j.getString("owner"))
            require(CompanionTrust.sha256(owner)==prefs.getString("owner",null))
            val m=EnginePatchManifest(j.getString("version"),j.getString("abi"),j.getString("soname"),j.getString("sha256"),j.getString("signature"))
            require(m.version.startsWith("9-") && m.soname=="libmrgreen_engine.so")
            // Revalidation copies only into a disposable staging directory, never mutating the active binary.
            EnginePatchPolicy(key(owner),File(root,"validation")){true}.verifyAndStage(artifact,m)
            artifact.absolutePath
        } catch(_:Exception) { prefs.edit().putBoolean("safe",true).commit(); null }
    }
    fun rollback() { val previous=prefs.getString("previous",null); prefs.edit().putString("active",previous).remove("previous").putBoolean("inflight",false).putInt("failures",0).putBoolean("safe",false).commit() }
    fun disable() { prefs.edit().putBoolean("safe",true).putBoolean("inflight",false).commit() }
    fun beginOperation() { if(prefs.getString("active",null)!=null && !prefs.getBoolean("safe",false)) prefs.edit().putBoolean("inflight",true).commit() }
    fun finishOperation(generated:Boolean) { val edit=prefs.edit().putBoolean("inflight",false); if(generated)edit.putInt("failures",0); edit.commit() }
    fun status()="Active: ${prefs.getString("active",null)?:"built-in"}; failures: ${prefs.getInt("failures",0)}; safe mode: ${prefs.getBoolean("safe",false)}. A process restart is required for any change."
    private fun key(der:ByteArray)=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    private fun readSmall(input:java.io.InputStream,max:Int):ByteArray { val out=java.io.ByteArrayOutputStream(); val bytes=ByteArray(4096); while(true){val n=input.read(bytes);if(n<0)break;require(out.size()+n<=max);out.write(bytes,0,n)};return out.toByteArray() }
}

/** Set during Application startup, before the single LlamaEngine class is initialized. */
object EnginePatchRuntime {
    @Volatile var selectedLibrary:String?=null; private set
    @Volatile private var manager:EnginePatchManager?=null
    private var journal:android.content.SharedPreferences?=null
    fun prepare(context:Context) {
        journal=context.getSharedPreferences("native_journal",0)
        if(journal?.getBoolean("inflight",false)==true) {
            val dir=File(context.filesDir,"bug-reports").apply{mkdirs()}
            val report=JSONObject().put("kind","interrupted_native_operation").put("stage",journal?.getString("stage","unknown")).put("started_at",journal?.getLong("started_at",0)).put("sdk",android.os.Build.VERSION.SDK_INT).put("device",android.os.Build.MODEL).put("abi","armeabi-v7a").put("cause","Unknown. May be OS process death, force-stop or a native crash. Obtain Android crash/logcat evidence; no automatic DB fix.")
            runCatching { File(dir,"native-interrupted.json").writeText(report.toString(2)) }
            journal?.edit()?.putBoolean("inflight",false)?.putBoolean("reported",false)?.commit()
        }
        val m=EnginePatchManager(context); manager=m; selectedLibrary=runCatching { m.prepare() }.getOrNull()
    }
    fun beginOperation(stage:String="native") { journal?.edit()?.putBoolean("inflight",true)?.putString("stage",stage)?.putLong("started_at",System.currentTimeMillis())?.commit(); if(selectedLibrary!=null)manager?.beginOperation() }
    fun finishOperation(generated:Boolean=false) { journal?.edit()?.putBoolean("inflight",false)?.commit(); if(selectedLibrary!=null)manager?.finishOperation(generated) }
    fun reject() { manager?.disable(); selectedLibrary=null }
}
