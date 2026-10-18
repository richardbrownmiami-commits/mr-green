package im.mrgreen.recipes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host contract tests for bounded payloads and safety invariants; UI/device automation still needs Redmi validation. */
class RecipeContractTest {
    @Test fun whatsappRequiresRecipientAndMessageWithinBounds() {
        assertFalse(RecipePolicy.validWhatsApp("", "hello"))
        assertFalse(RecipePolicy.validWhatsApp("Alice", " "))
        assertTrue(RecipePolicy.validWhatsApp("Alice", "hello"))
        assertFalse(RecipePolicy.validWhatsApp("x".repeat(121), "hello"))
        assertFalse(RecipePolicy.validWhatsApp("Alice", "x".repeat(2001)))
    }
    @Test fun promptMustBeNonEmptyAndBounded() {
        assertFalse(RecipePolicy.validPrompt(" "))
        assertTrue(RecipePolicy.validPrompt("question"))
        assertFalse(RecipePolicy.validPrompt("x".repeat(2001)))
    }
}
