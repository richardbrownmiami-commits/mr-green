package im.mrgreen.plugins

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PackManagerTest {
    @Test fun rejectsTraversalAndNativePayloadsBeforeCallbacks() {
        val root=Files.createTempDirectory("packs").toFile(); var dry=0
        val manager=PackManager(root,dryRun={_,_->dry++})
        assertThrows(PackException::class.java) { manager.prepare(zip("../escape.txt" to "x".toByteArray())) }
        assertThrows(PackException::class.java) { manager.prepare(zip("libevil.so" to byteArrayOf(1))) }
        assertEquals(0,dry)
    }
    @Test fun rejectsDuplicateEntriesAndExpandedSizeBomb() {
        val root=Files.createTempDirectory("packs").toFile(); val manager=PackManager(root)
        val duplicate=zip("flows/x.json" to byteArrayOf(1),"flows/y.json" to byteArrayOf(2)).readBytes().toString(Charsets.ISO_8859_1).replace("flows/y.json","flows/x.json").toByteArray(Charsets.ISO_8859_1)
        assertThrows(PackException::class.java) { manager.prepare(ByteArrayInputStream(duplicate)) }
        val bomb=ByteArrayOutputStream(); ZipOutputStream(bomb).use { z -> z.putNextEntry(ZipEntry("docs/bomb.txt")); val block=ByteArray(8192); repeat(5*1024) { z.write(block) }; z.closeEntry() }
        assertThrows(PackException::class.java) { manager.prepare(ByteArrayInputStream(bomb.toByteArray())) }
    }
    @Test fun signatureDetectsTamperingAndPinsExactOwner() {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload="canonical manifest".toByteArray()
        val sig=Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload) }.sign()
        val trust=PackTrust(Files.createTempDirectory("pin").resolve("owner").toFile())
        trust.pinOwnerKey(pair.public)
        assertTrue(trust.isPinned(pair.public))
        assertTrue(trust.verify(payload,Base64.getEncoder().encodeToString(sig),"SHA256withRSA",pair.public))
        assertFalse(trust.verify("tampered".toByteArray(),Base64.getEncoder().encodeToString(sig),"SHA256withRSA",pair.public))
        assertFalse(trust.verify(payload,Base64.getEncoder().encodeToString(sig),"SHA1withRSA",pair.public))
    }
    @Test fun signedPackDryRunsBeforeApprovalAndRunsOnlyAfterInstall() {
        val root=Files.createTempDirectory("packs").toFile()
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val trust=PackTrust(File(root,"owner.pin"),pair); var dry=0; var effects=0
        val manager=PackManager(File(root,"packs"),dryRun={_,_->dry++},execute={_,_,_->effects++},trust=trust)
        val draft=PackBuilder(trust).build("safe.demo","Safe demo","1",mapOf(
            "flows/main.json" to "{\"steps\":[{\"op\":\"set\",\"name\":\"x\",\"value\":1}]}".toByteArray(),
            "tests/golden.json" to "{\"cases\":[{\"entry\":\"flows/main.json\",\"expected\":{\"ok\":true}}]}".toByteArray()))
        val candidate=manager.prepare(ByteArrayInputStream(draft))
        assertEquals(1,dry); assertTrue(manager.listInstalled().isEmpty()); assertEquals(0,effects)
        manager.approveInstall(candidate)
        manager.run("safe.demo","flows/main.json"); assertEquals(1,effects)
        assertThrows(PackException::class.java) { manager.approveInstall(candidate) }
    }
    @Test fun approvalTokenCannotBeReusedAndMemoryIsNamespacedAndBounded() {
        val root=Files.createTempDirectory("packs").toFile()
        val manager=PackManager(root,memoryLimitBytes=128)
        manager.setMemory("plugin.a","k","v")
        assertEquals("v",manager.getMemory("plugin.a","k"))
        assertEquals("v",PackManager(root,memoryLimitBytes=128).getMemory("plugin.a","k"))
        assertNull(manager.getMemory("plugin.b","k"))
        assertThrows(IllegalArgumentException::class.java) { manager.setMemory("plugin.a","big","x".repeat(200)) }
        assertThrows(PackException::class.java) { manager.approveInstall(PendingPack(org.json.JSONObject(),emptyMap(),"not-issued")) }
        assertFalse(manager.isSafeMode())
    }
    @Test fun crashRecoveryCountsFailuresAndRequiresExplicitSafeModeExit() {
        val root=Files.createTempDirectory("packs").toFile()
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val trust=PackTrust(File(root,"owner.pin"),pair); val packRoot=File(root,"packs"); var manager=PackManager(packRoot,trust=trust)
        val draft=PackBuilder(trust).build("demo","Demo","1",mapOf("flows/main.json" to "{}".toByteArray(),"tests/main.json" to "{\"cases\":[{\"entry\":\"flows/main.json\",\"expected\":{}}]}".toByteArray()))
        manager.approveInstall(manager.prepare(ByteArrayInputStream(draft)))
        repeat(3) { manager.beforeRun("demo"); manager=PackManager(packRoot,trust=trust) }
        assertTrue(manager.isSafeMode())
        manager.exitSafeMode(); assertFalse(manager.isSafeMode())
        assertThrows(PackException::class.java) { manager.run("demo", "flows/main.json") }
    }
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArrayInputStream { val out=ByteArrayOutputStream(); ZipOutputStream(out).use { z->entries.forEach { (name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry() } }; return ByteArrayInputStream(out.toByteArray()) }
}
