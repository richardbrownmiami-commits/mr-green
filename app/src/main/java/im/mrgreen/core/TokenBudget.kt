package im.mrgreen.core

internal object TokenBudget {
    fun total(counts: Iterable<Int>, overhead: Int): Int {
        require(overhead >= 0)
        val total = counts.fold(overhead.toLong()) { sum, count ->
            check(count >= 0) { "Local tokenization is unavailable. Reload the model before trying again." }
            sum + count
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
