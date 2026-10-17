package im.mrgreen.engine

/** Counts only; never contains prompt or response text. */
data class GenerationProgress(val stage: String = "starting", val completed: Int = 0, val total: Int = 0) {
    fun status(elapsedSeconds: Long): String = when (stage) {
        "prompt" -> "Reading prompt $completed/$total · ${elapsedSeconds}s"
        "generate" -> "Replying · $completed tokens · ${elapsedSeconds}s"
        else -> "Starting local inference · ${elapsedSeconds}s"
    }
    companion object {
        // Native backend checks this same deadline cooperatively during compute.
        const val TIMEOUT_SECONDS = 180L
    }
}
