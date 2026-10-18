package im.mrgreen.extensions

import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30], application=android.app.Application::class)
class CompanionTrustTest {
    @Suppress("DEPRECATION")
    private fun installedSigner(context: android.content.Context): String {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val testSignature=android.content.pm.Signature("0102030405060708090a")
        if(Build.VERSION.SDK_INT>=28 && info.signingInfo?.apkContentsSigners.isNullOrEmpty()) {
            val signing=org.robolectric.util.ReflectionHelpers.callConstructor(android.content.pm.SigningInfo::class.java)
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowSigningInfo>(signing).setSignatures(arrayOf(testSignature))
            info.signingInfo=signing
            org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(info)
        } else if(Build.VERSION.SDK_INT<28 && info.signatures.isNullOrEmpty()) {
            info.signatures=arrayOf(testSignature)
            org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(info)
        }
        val cert = if (Build.VERSION.SDK_INT >= 28) requireNotNull(info.signingInfo?.apkContentsSigners?.firstOrNull()).toByteArray() else @Suppress("DEPRECATION") requireNotNull(info.signatures?.firstOrNull()).toByteArray()
        return CompanionTrust.sha256(cert)
    }

    @Test fun matchingInstalledSignerRequiresExplicitPersistentApproval() {
        val context = RuntimeEnvironment.getApplication()
        val store = PersistentCompanionApprovalStore(context)
        store.revoke(context.packageName)
        val signer = installedSigner(context)
        val trust = CompanionTrust(context, store)
        assertFalse("correct signer is not enough without explicit approval", trust.isTrustedAndApproved(context.packageName, signer))
        store.approveAfterUserConfirmation(context.packageName, signer)
        assertTrue("right pinned signer and package approval succeed", trust.isTrustedAndApproved(context.packageName, signer))
        assertFalse("a different pinned signer is rejected", trust.isTrustedAndApproved(context.packageName, "0".repeat(64)))
        store.revoke(context.packageName)
        assertFalse("revocation takes effect", trust.isTrustedAndApproved(context.packageName, signer))
    }

    @Test fun unknownInstalledPackageAndMalformedPinsFailClosed() {
        val context = RuntimeEnvironment.getApplication()
        val trust = CompanionTrust(context, PersistentCompanionApprovalStore(context))
        assertFalse(trust.isTrustedAndApproved("im.example.not.installed", "a".repeat(64)))
        assertFalse(trust.isTrustedAndApproved(context.packageName, "not-a-fingerprint"))
    }
}
