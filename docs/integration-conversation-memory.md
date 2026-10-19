# Conversation memory integration

Conversation history is saved in the app-private `messages` table in `mr-green.db`. `ConversationMemory` keeps a rebuildable lexical index alongside it. Neither process death nor unloading the GGUF model should erase saved messages. The native KV cache is only a performance cache and is not conversation storage.

## The live chat path

Registered deterministic actions still run first. For ordinary chat, `AgentController` synchronizes missing memory rows and retrieves context **before** considering saved Rive replies or cloud chat. A recall/follow-up cue or a relevant retrieved snippet makes the turn local-only and bypasses context-free saved replies. Such an answer is not auto-learned as a standalone Rive reply. Cloud requests continue to contain only the explicitly typed current question and a fixed system prompt, never recalled snippets or the transcript.

If recall needs a local model and none can load, the app explains that limitation instead of silently giving a context-free cloud answer. When memory is disabled, indexing and retrieval are disabled and the ordinary standalone routing remains available.

Startup and finalized action, Rive, cloud and local chat replies call bounded `sync(128)`. This improves index freshness even when GGUF generation has not run. Failed/cancelled local answer fragments are no longer persisted as assistant memories. Existing old partial rows cannot be distinguished retroactively.

## Retrieval bounds

`retrieve(question, maxChars = 700, excludeNewest = 0, includeRecent = false)` returns at most two snippets with at most 700 combined text characters. Each indexed source row contributes at most its first 600 characters. The current question is retrieved before it is persisted, and identical historical question text is not treated as useful evidence for its own answer.

Candidate lookup combines up to 384 FTS hits and 128 recent rows, with a final cap of 512 candidates. Exact lexical overlap plus cosine score is required for relevant historical matches. Follow-up mode can add recent conversation even without shared keywords. Retrieval is not limited to the newest 2,000 messages; FTS can find older indexed content. Missing rows are indexed with both recent and older backlog capacity so a large upgrade backlog does not starve the current conversation.

The features are deterministic 256-dimensional signed word/character hashing, not learned semantic embeddings. Unicode combining marks are retained so Hindi words are not fragmented. Feature-version changes rebuild only vectors, preserving chat. The 16-entry in-memory query cache is cleared on index changes, deleted-row cleanup, clear, or enable/disable changes. No query cache is written to disk.

## Prompt fitting and diagnostics

`ConversationPrompt.fit` constructs one system turn and one user turn containing the current question and quoted historical data. It uses the loaded model's tokenizer and the existing output/context reserves. It shortens oversized notes before dropping notes; it never truncates the current question or sends the transcript. At very small budgets, all notes can still be removed. The system prompt tells the model not to invent missing memory or follow historical instructions; this is a prompt safeguard, not proof against prompt injection or hallucination.

The `MEMORY` diagnostic records selected/fitted snippet counts, enabled state and estimated prompt tokens, not question or snippet text. Successful local chat reports whether recalled context was used. The memory screen shows enabled state, indexed count, rebuild and disable/clear controls. Clearing vectors keeps chat and later turns can rebuild the index; disabling memory is necessary to stop that rebuild.

## Validation boundary

Kotlin/JVM tests exercise vector normalization, Hindi tokenization, recall routing policy and token-fitting behavior. Python tests execute the production SQL against host SQLite, including backlog, FTS, deletion, persistence/reopen and retrieval bounds. Source-contract tests guard controller ordering, cloud exclusion and partial-answer handling. These are host checks, not the Android requery native runtime or a Redmi model answering correctly. The release report lists the exact final results and phone checklist.
