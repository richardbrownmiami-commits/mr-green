package im.mrgreen.extensions

import org.junit.Assert.*
import org.junit.Test

class ExtensionPolicyTest {
    @Test fun companionWaitAndResultAreBounded() {
        requireCompanionTimeout(1)
        requireCompanionTimeout(10_000)
        for (timeout in listOf(0L, -1L, 10_001L, Long.MAX_VALUE)) {
            try { requireCompanionTimeout(timeout); fail("Unbounded timeout accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val exact = "a".repeat(16 * 1024)
        assertEquals(exact, requireBoundedCompanionResult(exact))
        for (result in listOf(exact + "a", "\u20ac".repeat(6000))) {
            try { requireBoundedCompanionResult(result); fail("Oversized result accepted or silently truncated") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun invocationRequiresToolAdvertisedByCurrentCompanionAsWellAsReviewed() {
        val descriptor = CompanionDescriptor("im.example.companion", "a".repeat(64), listOf(CompanionTool("notes.read", "Read notes", "untrusted")))
        requireAdvertisedReviewedTool(descriptor, "notes.read", setOf("notes.read"))
        try {
            requireAdvertisedReviewedTool(descriptor, "message.send", setOf("message.send"))
            fail("Unadvertised tool must not be invocable even if supplied as reviewed")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun skeletonOwnsRiskAndUnapprovedToolsAreBlocked() {
        val result = CompanionToolPolicy.validate("im.example.companion", listOf(CompanionTool("notes.read", "Read notes", "READ_ONLY"), CompanionTool("message.send", "Send message", "READ_ONLY")), setOf("notes.read"))
        assertEquals(ToolRisk.CONFIRM, result.risks["notes.read"])
        assertEquals(ToolRisk.BLOCKED, result.risks["message.send"])
    }
    @Test(expected = IllegalArgumentException::class) fun rejectDuplicateToolNames() {
        CompanionToolPolicy.validate("im.example.companion", listOf(CompanionTool("tool.one", "a", "READ_ONLY"), CompanionTool("tool.one", "b", "READ_ONLY")), emptySet())
    }
    @Test fun crashGuardRollsBackAfterThirdUnstableStart() {
        val state = FakeState()
        val guard = PatchCrashGuard(state)
        assertTrue(guard.beginCandidate()); assertFalse(guard.afterStartupFailure())
        assertTrue(guard.beginCandidate()); assertFalse(guard.afterStartupFailure())
        assertTrue(guard.beginCandidate()); assertTrue(guard.afterStartupFailure())
        assertEquals(1, state.rollbacks); assertTrue(state.safeMode)
        assertFalse(guard.beginCandidate())
    }
    private class FakeState : PatchState {
        var count = 0; var rollbacks = 0; var safeMode = false
        override fun failures() = count
        override fun incrementFailures() { count++ }
        override fun clearFailures() { count = 0 }
        override fun rollbackPrevious() { rollbacks++ }
        override fun enterSafeMode() { safeMode = true }
    }
}
