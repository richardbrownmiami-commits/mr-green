package im.mrgreen.plugins

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PackIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): JSONObject = JSONObject(javaClass.classLoader!!.getResourceAsStream("phase7-public-signature.json")!!.bufferedReader().use { it.readText() })
    private fun manager(root: File, fixture: JSONObject): PackManager {
        val trust = PackTrust(File(root.parentFile, "pin"))
        trust.pinOwnerKey(trust.decodePublicKey("RSA", fixture.getString("publicKey")))
        return PackManager(root, trust = trust)
    }
    @Test fun pythonOpenSslSignatureIsCompatibleWithAndroidCanonicalization() {
        val fixture = fixture(); val root = temporary.newFolder("packs"); val manager = manager(root, fixture)
        val archive = Base64.getDecoder().decode(fixture.getString("archiveBase64"))
        val pending = manager.prepare(ByteArrayInputStream(archive))
        assertEquals("A/B नमस्ते", pending.manifest.getString("name"))
        assertTrue(manager.usageBytes() > 0)
        manager.discard(pending)
        assertEquals(0L, manager.usageBytes())
        val install = manager.prepare(ByteArrayInputStream(archive))
        assertEquals("1.0.0", manager.approveInstall(install).version)
        assertEquals(listOf("flows/main.json"), manager.installedEntries("example.flow"))
    }
    @Test fun malformedRunStateEntersSafeModeInsteadOfCrashingTheApp() {
        val root = temporary.newFolder("corrupt")
        File(root, ".run-state").writeText("not json")
        assertTrue(PackManager(root).isSafeMode())
    }
    @Test fun memoryStringsPersistWithoutAccessToOtherNamespaces() {
        val root = temporary.newFolder("memory")
        val manager = PackManager(root)
        manager.setMemory("one.pack", "greeting", "local value")
        assertEquals("local value", PackManager(root).getMemory("one.pack", "greeting"))
        assertNull(PackManager(root).getMemory("two.pack", "greeting"))
        assertTrue(manager.usageBytes() > 0)
        manager.remove("one.pack")
        assertNull(PackManager(root).getMemory("one.pack", "greeting"))
    }
    @Test fun approvedUpdateRetainsAndRestoresPreviousVersion() {
        val root=temporary.newFolder("rollback")
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val trust=PackTrust(File(root,"owner.pin"),pair)
        var effects=0
        val manager=PackManager(File(root,"packs"),execute={_,_,_->effects++},trust=trust)
        fun draft(version:String):ByteArray = PackBuilder(trust).build("rollback.demo","Rollback demo",version,mapOf(
            "flows/main.json" to """{"steps":[{"op":"set","name":"version","value":"$version"}]}""".toByteArray(),
            "tests/main.json" to """{"cases":[{"entry":"flows/main.json","expected":{"ok":true}}]}""".toByteArray()))
        val first=manager.prepare(ByteArrayInputStream(draft("1")))
        assertTrue(manager.listInstalled().isEmpty()); assertEquals(0,effects)
        manager.approveInstall(first)
        manager.approveInstall(manager.prepare(ByteArrayInputStream(draft("2"))))
        assertEquals("2",manager.listInstalled().single().version)
        assertEquals("1",manager.rollback("rollback.demo").version)
        assertEquals("1",manager.listInstalled().single().version)
        val previous=File(root,"packs/.previous-rollback.demo/flows/main.json")
        previous.writeText("tampered")
        assertThrows(PackException::class.java) { manager.rollback("rollback.demo") }
        assertEquals("1",manager.listInstalled().single().version)
        val mutable=manager.prepare(ByteArrayInputStream(draft("3")))
        mutable.manifest.put("name","not-the-reviewed-pack")
        assertThrows(PackException::class.java) { manager.approveInstall(mutable) }
        assertEquals("1",manager.listInstalled().single().version)
    }
    @Test fun fullLibraryRejectsAnotherArchiveBeforeDryRun() {
        val fixture=fixture(); val root=temporary.newFolder("full")
        val manager=manager(root,fixture)
        val reserve=File(root,".previous-reserve/fill.txt").apply { parentFile!!.mkdirs() }
        java.io.RandomAccessFile(reserve,"rw").use { it.setLength(PackManager.MAX_TOTAL_BYTES.toLong()) }
        assertEquals(PackManager.MAX_TOTAL_BYTES.toLong(),manager.usageBytes())
        assertThrows(PackException::class.java) { manager.prepare(ByteArrayInputStream(Base64.getDecoder().decode(fixture.getString("archiveBase64")))) }
        assertTrue(manager.listInstalled().isEmpty())
    }
}
