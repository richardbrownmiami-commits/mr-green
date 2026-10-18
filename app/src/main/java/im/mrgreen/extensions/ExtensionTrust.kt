package im.mrgreen.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

/** Companion APK trust primitives. Approval and pinned signer checks are independent gates. */
class CompanionTrust(private val context: Context, private val approvalStore: CompanionApprovalStore) {
    fun signerSha256(packageName: String): Set<String> {
        val info = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val certs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        else @Suppress("DEPRECATION") info.signatures?.map { it.toByteArray() }.orEmpty()
        return certs.map { sha256(it) }.toSet()
    }
    fun isTrustedAndApproved(packageName: String, pinnedSignerHex: String): Boolean = runCatching {
        val expected = pinnedSignerHex.filter { it.isLetterOrDigit() }.lowercase()
        expected.length == 64 && signerSha256(packageName).any { it == expected } && approvalStore.approved(packageName, expected)
    }.getOrDefault(false)
    companion object { fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } }
}

interface CompanionApprovalStore { fun approved(packageName: String, signerSha256: String): Boolean }

/** Durable explicit approvals; only call approve() from a user-confirmed review screen. */
class PersistentCompanionApprovalStore(context: Context) : CompanionApprovalStore {
    private val prefs = context.applicationContext.getSharedPreferences("companion_approvals_v1", Context.MODE_PRIVATE)
    override fun approved(packageName: String, signerSha256: String): Boolean =
        prefs.getString(packageName, null)?.equals(signerSha256, ignoreCase = true) == true
    fun approveAfterUserConfirmation(packageName: String, signerSha256: String) {
        require(packageName.matches(Regex("^[a-zA-Z0-9_.]{3,255}$")))
        require(signerSha256.matches(Regex("(?i)^[a-f0-9]{64}$")))
        check(prefs.edit().putString(packageName, signerSha256.lowercase()).commit())
    }
    fun revoke(packageName: String) { prefs.edit().remove(packageName).commit() }
}

data class CompanionTool(val name: String, val description: String, val requestedRisk: String)

data class ValidatedCompanionTools(val packageName: String, val tools: List<CompanionTool>, val risks: Map<String, ToolRisk>)
enum class ToolRisk { READ_ONLY, CONFIRM, BLOCKED }

/** Skeleton-owned risk mapping; companion cannot lower risk via its manifest. */
object CompanionToolPolicy {
    private val namePattern = Regex("^[a-z][a-z0-9_.]{1,63}$")
    fun validate(packageName: String, tools: List<CompanionTool>, approvedNames: Set<String>): ValidatedCompanionTools {
        require(packageName.matches(Regex("^[a-zA-Z0-9_.]{3,255}$")))
        require(tools.size in 1..64 && tools.map { it.name }.distinct().size == tools.size)
        tools.forEach { require(namePattern.matches(it.name) && it.description.length in 1..300) }
        val risks = tools.associate { it.name to if (it.name in approvedNames) ToolRisk.CONFIRM else ToolRisk.BLOCKED }
        return ValidatedCompanionTools(packageName, tools, risks)
    }
}

data class EnginePatchManifest(val version: String, val abi: String, val soname: String, val sha256: String, val signatureBase64: String)

/** Stages vetted engine replacements as data only; it deliberately never loads a caller-supplied library. Signatures cover the canonical manifest and hash. */
class EnginePatchPolicy(private val pinnedKey: PublicKey, private val activeDir: File, private val approved: () -> Boolean) {
    fun verifyAndStage(artifact: File, manifest: EnginePatchManifest): File {
        require(approved()) { "Explicit user approval required" }
        require(pinnedKey is java.security.interfaces.RSAPublicKey && pinnedKey.modulus.bitLength()>=2048) { "Owner key must be RSA-2048 or stronger" }
        require(manifest.version.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))) { "Invalid patch version" }
        require(Build.SUPPORTED_ABIS.contains("armeabi-v7a") && manifest.abi == "armeabi-v7a") { "Patch ABI mismatch" }
        require(manifest.soname == "libllama.so" || manifest.soname == "libmrgreen_engine.so") { "Unapproved JNI ABI name" }
        require(artifact.isFile && artifact.length() in 1..(150L * 1024 * 1024))
        val hasher=MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input -> val buffer=ByteArray(8192); while(true) { val n=input.read(buffer); if(n<0)break; hasher.update(buffer,0,n) } }
        val digest=hasher.digest().joinToString("") { "%02x".format(it) }
        require(digest.equals(manifest.sha256, ignoreCase = true)) { "Patch hash mismatch" }
        val header = artifact.inputStream().use { input -> val bytes=ByteArray(20); var n=0; while(n<20) { val got=input.read(bytes,n,20-n); if(got<0)break; n+=got }; bytes.copyOf(n) }
        require(header.size == 20 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() && header[4] == 1.toByte() && header[5] == 1.toByte() && header[16] == 3.toByte() && header[17] == 0.toByte() && header[18] == 40.toByte() && header[19] == 0.toByte()) { "Patch is not a little-endian ARM ELF32 shared library" }
        val canonical = "mrgreen-engine-patch-v1\n${manifest.version}\n${manifest.abi}\n${manifest.soname}\n${manifest.sha256.lowercase()}\n".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(pinnedKey); update(canonical) }
        require(signature.verify(java.util.Base64.getDecoder().decode(manifest.signatureBase64))) { "Patch signature rejected" }
        activeDir.mkdirs()
        val tmp = File(activeDir, "pending-${manifest.version}.so.tmp")
        artifact.copyTo(tmp, overwrite = true)
        require(tmp.length() == artifact.length())
        val target = File(activeDir, "pending-${manifest.version}.so")
        check(tmp.renameTo(target)) { "Could not atomically stage patch" }
        return target
    }
}

/** Crash loop accounting survives process death; caller must increment before activating a patch. */
class PatchCrashGuard(private val state: PatchState) {
    fun beginCandidate(): Boolean {
        if (state.failures() >= MAX_FAILURES) { state.enterSafeMode(); return false }
        state.incrementFailures(); return true
    }
    fun markStable() { state.clearFailures() }
    fun afterStartupFailure(): Boolean {
        if (state.failures() >= MAX_FAILURES) { state.rollbackPrevious(); state.enterSafeMode(); return true }
        return false
    }
    companion object { const val MAX_FAILURES = 3 }
}
interface PatchState { fun failures(): Int; fun incrementFailures(); fun clearFailures(); fun rollbackPrevious(); fun enterSafeMode() }
