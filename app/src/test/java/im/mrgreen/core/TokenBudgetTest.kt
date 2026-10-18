package im.mrgreen.core

import org.junit.Assert.*
import org.junit.Test

class TokenBudgetTest {
    @Test fun countsAndOverheadAreIncludedWithoutOverflow() {
        assertEquals(54, TokenBudget.total(listOf(10, 20), 24))
        assertEquals(24, TokenBudget.total(listOf(0, 0), 24))
        assertEquals(Int.MAX_VALUE, TokenBudget.total(listOf(Int.MAX_VALUE, Int.MAX_VALUE), 24))
    }
    @Test fun failedTokenizerCannotMasqueradeAsASmallPrompt() {
        assertThrows(IllegalStateException::class.java) { TokenBudget.total(listOf(10, -1), 24) }
        assertThrows(IllegalStateException::class.java) { TokenBudget.total(listOf(-1, -1), 24) }
        assertThrows(IllegalArgumentException::class.java) { TokenBudget.total(listOf(0), -1) }
    }
}
