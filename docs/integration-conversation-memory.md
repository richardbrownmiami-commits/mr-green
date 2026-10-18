# Conversation memory integration

`im.mrgreen.memory.ConversationMemory` adds an opt-in-to-use (enabled by default) local lexical retrieval index in the existing `mr-green.db`. It does not alter `GreenStore`, migrations, `AgentController`, or Gradle configuration.

## Parent integration

Construct one instance with the app context and existing store:

```kotlin
val memory = ConversationMemory(context, greenStore)
memory.prepare() // idempotent; creates only conversation_vectors and its index
```

Only call `sync()` after a message is finalized and persisted; in particular do not sync a streaming/partial assistant response. It independently selects persisted `messages` rows with role `user` or `assistant`, so system messages are excluded. Sync oldest missing IDs first, up to 128 by default (`sync(maxNew = ...)`); call again on a later finalized message if there is more backlog. It indexes at most the first 600 characters per row.

For a turn, pass only the current question to `retrieve(question, maxChars = 700, excludeNewest = 0)`. It returns at most two `RecalledSnippet(messageId, role, text, score)` entries and at most `maxChars` total text. `excludeNewest` can omit the latest persisted user/assistant message rows. Use these snippets transiently as context; do not append full conversation history or log query/snippet contents. The component has a 16-entry in-memory LRU keyed by query and index revision; it stores no query cache on disk.

`enabled` / `setEnabled(Boolean)` controls retrieval and indexing, with preference key `conversation_memory.enabled` (default true). `status()` returns enabled state and indexed count. `clear()` deletes only vector rows, not conversation messages. Disable/clear behavior should be surfaced through app controls as appropriate.

## Implementation and constraints

The vectors use deterministic 256-dimensional signed feature hashing of normalized word features plus character trigrams. Terms use basic English/Hindi stop words. This is **lexical similarity, not a trained/semantic embedding**; topical paraphrases without shared words are not guaranteed to retrieve. A lexical term-overlap gate mitigates hash collisions. Retrieval scans at most the newest 2,000 indexed messages, so older material can fall outside this bound; sync, too, is explicitly bounded. No model, download, cloud service, or separate database is used. All content remains in the app's existing local database. The component must not be given screen contents or other sensitive ephemeral sources.

The JVM tests cover deterministic vector features and related-versus-unrelated lexical similarity only. Requery SQLite depends on Android native runtime; no new database integration test is claimed here.
