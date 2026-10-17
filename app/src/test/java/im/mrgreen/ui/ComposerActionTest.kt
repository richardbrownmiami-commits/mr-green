package im.mrgreen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun idleShowsSend() {
        assertEquals(ComposerAction.SEND, composerAction(busy = false, generating = false))
    }
    @Test fun promptEvaluationAlreadyShowsStopBeforeStreaming() {
        assertEquals(ComposerAction.STOP_GENERATING, composerAction(busy = true, generating = true))
    }
    @Test fun loadAndUnloadDoNotOfferSend() {
        assertEquals(ComposerAction.WORKING, composerAction(busy = true, generating = false))
    }
    @Test fun completionAndErrorReturnToSend() {
        assertEquals(ComposerAction.SEND, composerAction(busy = false, generating = false))
        assertEquals(ComposerAction.SEND, composerAction(busy = false, generating = true))
    }
}
