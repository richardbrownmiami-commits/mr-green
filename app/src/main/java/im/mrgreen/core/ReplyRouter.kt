package im.mrgreen.core

/** Uses a deterministic reply when available; fallback runs only when action returns null. */
suspend fun <T> deterministicFirst(
    action: suspend () -> T?,
    fallback: suspend () -> T
): T = action() ?: fallback()
