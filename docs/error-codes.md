# Error codes

Codes are defined centrally in `RuntimePolicy.kt`. They are stable identifiers, not evidence that a feature is implemented. Phase 1 displays local runtime and storage errors; later-phase codes are reserved for the supplied plan.

| Code | What to do |
|---|---|
| `E_LLM_LOAD` | Check that the file is a supported, readable, text-only GGUF and enough RAM is free. Record the real device error; do not assume host compatibility proves Android compatibility. |
| `E_LLM_GENERATE` | Check the model status, shorten the message, and retry once. Share metadata logs if it recurs. |
| `E_LLM_CANCELLED` | A reserved explicit cancellation code. The current JNI facade returns a cancelled result which the controller reports under `E_LLM_GENERATE`. No successful completion is claimed. |
| `E_LLM_UNAVAILABLE` | Import and load one local model in Llama. No synthetic reply is generated. |
| `E_LLM_CONTEXT` | Shorten the request or clear irrelevant conversation context. The small ARMv7 context is bounded. |
| `E_LLM_TIMEOUT` | Reserved for later provider timeouts. |
| `E_DB_INIT`, `E_DB_WRITE` | Check free private storage. Preserve app data and logs; do not uninstall as a first fix. |
| `E_DB_FTS5` | Bundled FTS5 initialization needs investigation. Never mark the runtime check passed from packaging alone. |
| `E_STORAGE_PERMISSION` | Use the system document picker or grant optional all-files access explicitly. |
| `E_OOM_TRIM` | Android requested memory relief or the safety monitor stopped work. Close other apps, unload, and retry with 1024 context. The 2 GiB monitor is best-effort, not a guarantee against native OOM. |
| `E_SYS_CRASH` | Review the last-crash metadata card. Native fatal signals may need separate Android system diagnostics. |
| `E_SYS_SERVICE` | Reopen Mr Green and start the task in the foreground. Review MIUI battery settings. |
| `E_TOOL_PERMISSION`, `E_VERIFY_FAIL` | Reserved for later action tools and verification. |
| `E_GROQ_429` | Reserved for Phase 2 rate limits. Phase 1 does not call Groq. |
| `E_ACCESS_NODE_NOT_FOUND`, `E_SCREEN_SECURE` | Reserved for later accessibility/screen features. |
| `E_PLUGIN_SANDBOX_KILL` | Reserved for the later sandbox runtime. |
| `E_DB_RECALL_EMPTY` | Reserved for later memory recall. |

Full prompts, replies, credentials and model/file content are not put in these logs. An API returning an error can protect against ordinary failures; it cannot universally catch native process crashes.
