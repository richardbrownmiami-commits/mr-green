package im.mrgreen.recipes

import org.junit.Assert.*
import org.junit.Test

class NativeControlPolicyTest {
    @Test fun sensitiveOrMissingPackagesAreRejected() {
        assertFalse(NativeControlPolicy.safeTarget(null, "im.mrgreen"))
        listOf("com.android.settings", "com.bank.wallet", "com.android.packageinstaller", "com.google.android.apps.bard", "com.openai.chatgpt", "com.android.chrome", "com.termux", "im.mrgreen").forEach {
            assertFalse("$it should be blocked", NativeControlPolicy.safeTarget(it, "im.mrgreen"))
        }
        assertTrue(NativeControlPolicy.safeTarget("com.example.reader", "im.mrgreen"))
    }
    @Test fun labelsMustMatchExactlyAndBeBounded() {
        assertTrue(NativeControlPolicy.exactLabelMatch("Continue", " Continue "))
        assertFalse(NativeControlPolicy.exactLabelMatch("Continue", "Continue anyway"))
        assertFalse(NativeControlPolicy.exactLabelMatch("x".repeat(161), "x".repeat(161)))
    }
    @Test fun textGesturesAndTraversalAreBounded() {
        assertTrue(NativeControlPolicy.validText("hello")); assertFalse(NativeControlPolicy.validText("x".repeat(501)))
        assertFalse(NativeControlPolicy.validText("hello\n")); assertFalse(NativeControlPolicy.validText("\u001bcommand"))
        assertTrue(NativeControlPolicy.validSwipe(500, 1000)); assertFalse(NativeControlPolicy.validSwipe(5000, 1000))
        assertTrue(NativeControlPolicy.safeTreeNode(20, 119)); assertFalse(NativeControlPolicy.safeTreeNode(21, 0)); assertFalse(NativeControlPolicy.safeTreeNode(0, 120))
    }
    @Test fun sensitiveMutationsAreNotGenericTapTargets() {
        listOf("Delete all", "Send", "Install", "Grant permission", "Payment", "Clear all").forEach {
            assertFalse(it, NativeControlPolicy.validLabel(it))
            assertTrue(it, NativeControlPolicy.sensitiveLabel(it))
        }
    }
}
