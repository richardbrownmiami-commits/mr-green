# Error codes

Codes are defined centrally in `RuntimePolicy.kt`. They are stable identifiers, not evidence that a phone acceptance gate passed. Logs contain metadata, never prompts, replies or credentials.

| Code | What to do |
|---|---|
| `E_LLM_LOAD` | Check that the file is a supported, readable text-only GGUF and RAM is free. Host compatibility is not Android compatibility. |
| `E_LLM_GENERATE` | Check model status, shorten the message and retry once. Share metadata logs if it recurs. |
| `E_LLM_CANCELLED` | Reserved explicit cancellation code. Normal native cancellation is reported as a cancelled result, not a successful answer. |
| `E_LLM_UNAVAILABLE` | Import and load one local model. No synthetic offline reply is generated. |
| `E_LLM_CONTEXT` | Shorten the request; the small ARMv7 context is bounded. |
| `E_LLM_TIMEOUT` | A provider or local generation timeout needs attention. Local cooperative cancellation is not a hard real-time native kill. |
| `E_DB_INIT`, `E_DB_WRITE` | Check free private storage. Preserve app data; never uninstall as a first fix. |
| `E_DB_FTS5` | Investigate packaged FTS5 initialization on Android. Packaging is not runtime evidence. |
| `E_STORAGE_PERMISSION` | Use the document picker or grant optional all-files access explicitly. |
| `E_OOM_TRIM` | Close other apps, unload and retry with a smaller context. The 2 GiB monitor is best-effort, not a universal native OOM guarantee. |
| `E_SYS_CRASH` | Review the last-crash metadata card. Native fatal signals may need Android system diagnostics. |
| `E_SYS_SERVICE` | Reopen Mr Green and start in the foreground. Review MIUI battery settings. |
| `E_GROQ_429` | Groq quota/backoff pauses this model and uses local fallback. Wait for Retry-After/reset. No artificial app-side user quota is imposed. |
| `E_GATEWAY_PRIVACY` | Sensitive content/provenance or a Local only category prevented sending. Use local inference; never downgrade P2 to bypass the rule. |
| `E_GATEWAY_NETWORK` | Cloud transport failed. Check validated internet and local model availability. Raw server/exception bodies are not logged. |
| `E_GATEWAY_FALLBACK` | Another cloud rejection, missing model/key, audit failure or twice-invalid JSON. Review Cloud settings using synthetic data; never put the key in a bug report. |
| `E_TOOL_PERMISSION`, `E_VERIFY_FAIL` | Reserved for later tools/verification. |
| `E_ACCESS_NODE_NOT_FOUND`, `E_SCREEN_SECURE` | Reserved for later accessibility/screen features. |
| `E_PLUGIN_SANDBOX_KILL` | Reserved for the later sandbox runtime. |
| `E_DB_RECALL_EMPTY` | Reserved for later memory recall. |

`Provider=groq` success and `fallback=local` entries are content-free. Cloud audit rows count attempted sends, including discovery and retries; they do not prove provider acknowledgement. A22/A23 still require actual Redmi evidence. API error handling cannot universally catch native process crashes.
