package im.mrgreen.extensions

import org.junit.Assert.*
import org.junit.Test

class ExtensionPolicyTest {
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
