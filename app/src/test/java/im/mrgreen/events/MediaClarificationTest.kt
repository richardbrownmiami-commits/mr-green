package im.mrgreen.events

import android.app.Application
import im.mrgreen.data.GreenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class MediaClarificationTest {
    @Test fun missingArtistAsksAndCanBeCancelledWithoutLaunchingAnything()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val runtime=EventRuntime(context,GreenStore(context))
        assertTrue(runtime.execute("play music")!!.startsWith("Tell me"))
        assertEquals("Media request cancelled.",runtime.execute("cancel"))
        assertNull(runtime.execute("some artist"))
    }
    @Test fun unrelatedQuestionDoesNotBecomeAnArtistSearch()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val runtime=EventRuntime(context,GreenStore(context))
        assertTrue(runtime.execute("play video")!!.startsWith("Tell me"))
        assertNull(runtime.execute("what time is it"))
        assertEquals("Media request cancelled.",runtime.execute("stop"))
    }
}
