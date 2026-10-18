package im.mrgreen.extensions

import android.app.Application
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

/** Crypto/policy simulation only. The fixture is not executable code and is never loaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], application=Application::class)
class EnginePatchPolicyTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val keys=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    @Before fun reset() {
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("armeabi-v7a"))
        context.getSharedPreferences("engine_patch_v1",0).edit().clear().commit()
        File(context.filesDir,"engine-patches").deleteRecursively()
    }
    private fun artifact():File=File.createTempFile("policy-fixture",".so",context.cacheDir).apply {
        val b=ByteArray(64);b[0]=0x7f;b[1]='E'.code.toByte();b[2]='L'.code.toByte();b[3]='F'.code.toByte();b[4]=1;b[5]=1;b[16]=3;b[18]=40;writeBytes(b)
    }
    private fun manifest(file:File,version:String="9-test-1"):EnginePatchManifest {
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        val bytes="mrgreen-engine-patch-v1\n$version\narmeabi-v7a\nlibmrgreen_engine.so\n$hash\n".toByteArray()
        val signature=Signature.getInstance("SHA256withRSA").apply{initSign(keys.private);update(bytes)}.sign()
        return EnginePatchManifest(version,"armeabi-v7a","libmrgreen_engine.so",hash,java.util.Base64.getEncoder().encodeToString(signature))
    }
    private fun rejected(block:()->Unit) { try { block();fail("Expected rejection") } catch(_:IllegalArgumentException) {} }
    @Test fun correctSignatureStagesButMetadataAndArtifactTamperingFail() {
        val file=artifact();val m=manifest(file);val policy=EnginePatchPolicy(keys.public,File(context.cacheDir,"signed-stage")){true}
        assertTrue(policy.verifyAndStage(file,m).isFile)
        rejected{policy.verifyAndStage(file,m.copy(version="9-tampered"))}
        file.appendBytes(byteArrayOf(1))
        rejected{policy.verifyAndStage(file,m)}
    }
    @Test fun approvalWrongAbiAndPathTraversalAreRejected() {
        val file=artifact();val m=manifest(file)
        rejected{EnginePatchPolicy(keys.public,context.cacheDir){false}.verifyAndStage(file,m)}
        rejected{EnginePatchPolicy(keys.public,context.cacheDir){true}.verifyAndStage(file,m.copy(abi="arm64-v8a"))}
        rejected{EnginePatchPolicy(keys.public,context.cacheDir){true}.verifyAndStage(file,m.copy(version="../escape"))}
    }
    @Test fun activeOwnerPinIsRecheckedAndThreeInterruptedOperationsEnterSafeMode() {
        val file=artifact();val m=manifest(file)
        val manager=EnginePatchManager(context)
        manager.installAfterApproval(EnginePatchManager.Candidate(m,file,keys.public.encoded),true)
        assertNotNull(manager.prepare())
        repeat(2){manager.beginOperation();assertNotNull(manager.prepare())}
        manager.beginOperation();assertNull(manager.prepare())
        assertTrue(manager.status().contains("safe mode: true"))
    }
    @Test fun ownerCannotBeReplacedByASecondIndependentlySuppliedKey() {
        val file=artifact();val m=manifest(file);val manager=EnginePatchManager(context)
        manager.installAfterApproval(EnginePatchManager.Candidate(m,file,keys.public.encoded),true)
        val other=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        rejected { manager.installAfterApproval(EnginePatchManager.Candidate(m.copy(version="9-other"),file,other.public.encoded),true) }
    }
}
