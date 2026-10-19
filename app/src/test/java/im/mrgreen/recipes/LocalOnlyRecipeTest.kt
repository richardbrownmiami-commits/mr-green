package im.mrgreen.recipes

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LocalOnlyRecipeTest {
    @Test fun externalAiRoutesAreDeniedEvenBeforeAServiceIsAvailable() = runBlocking {
        RecipeRuntime.service = null
        listOf("meta.prompt", "gemini.prompt", "meta.result", "gemini.result").forEach { recipe ->
            val result = RecipeRuntime.execute(JSONObject().put("recipe", recipe)
                .put("prompt", "private question or screen text").toString())!!
            assertTrue(result.contains("disabled"))
            assertTrue(result.contains("no prompt was entered"))
            assertFalse(result.contains("Enable Mr Green Recipes"))
        }
    }
    @Test fun confirmedNonModelToolsStillUseTheirExistingServiceGate() = runBlocking {
        RecipeRuntime.service = null
        listOf("whatsapp.send", "wifi.settings").forEach { recipe ->
            assertTrue(RecipeRuntime.execute(JSONObject().put("recipe", recipe).toString())!!.contains("bridge is disconnected"))
        }
    }
}
