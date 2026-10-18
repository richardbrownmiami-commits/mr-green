# Phase 7 packs

A `.redpack` is a signed ZIP containing data flows and/or QuickJS scripts. It cannot add Android permissions, native libraries, DEX, companion APKs or engine updates. This increment starts from Phase 2; it does not include Phases 3–6 or 8+.

## Install and review

Open **Overview → Plugins · Phase 7** or the same entry in **Library**. Import a pack with the Android file picker, including from SD or a downloaded GitHub release, or build a draft locally. The app validates the archive, signature, hashes, schema, API range and declared permissions, then executes side-effect-free tests. A separate review shows the identity, version, layers, permissions, tools and expanded size. **Only “Approve and install” activates it.** Reject/cancel discards the pending candidate.

Installed packs and security state are app-private. This deliberately protects approval and trust state rather than placing active code in a shared `/sdcard/Red/packs` folder. Imports still work from SD. There is no automatic GitHub downloader in this increment.

## Manifest and payload

Required manifest fields are `id`, `name`, `version`, `api_min`, `api_max`, `layers`, `permissions`, `tools`, `triggers`, `files`, `signer_algorithm`, `signer_key`, `signature_algorithm`, `signature`. Unknown fields are rejected. API range must include API **1**. `layers` contains only **1** and/or **2**. `files` maps every payload path to its lowercase SHA-256 digest; undeclared, missing and duplicate entries are rejected.

Payload folders are `flows`, `scripts`, `tests`, `templates`, `rules`, `prompts`, `docs`, `knowledge`, `themes`, `routines`. Only `.json`, `.js`, `.md`, `.txt` are accepted; JavaScript is confined to `scripts/*.js`. Native/executable payloads are rejected. Directory ZIP entries, absolute paths, traversal, hidden/dot path components, backslashes, NUL, overlarge files and excessive JSON nesting are rejected. There are at most 512 payload files and 32 executable entries. Flows are at most 256 KiB, scripts 1 MiB, tests 64 KiB, manifest 256 KiB, and other files 32 MiB.

Data files are stored and hash-validated. They do **not** activate a later-phase intent router, learning engine, theme system or Doctor.

## Permissions and host APIs

| Declaration | Access |
|---|---|
| `plugin.output` | `notify(text)` in the result screen; `plugin.echo` tool |
| `time.read` | `time.now()`; `time.now` tool |
| `plugin.memory` | `mem.get(key)` / `mem.set(key,value)`, namespaced to the pack |
| `plugin.log` | `log(text)` is accepted but its text is discarded; only API metadata is audited |

`tool.call(name,args)` accepts only declared, permission-mapped `plugin.echo` and `time.now`. These are plugin adapters, not the Phase 3 Android action registry. No filesystem, Android context, database, chat history, credentials, ambient network, `std`, `os`, `Java`, `require` or `fetch` is exposed.

`http.get`, runtime `ai.ask` and flow `ai_write` are reserved boundaries and fail closed as unavailable in this scoped build. Script-supplied text is never sent to the cloud. **Ask AI for a draft** is a separate, explicit builder action through the existing Phase 2 gateway and serialized local-model fallback; it does not execute or install its output. Redaction/P2 blocking and cloud category switches still apply.

## Tests and limits

Every executable must be covered by `tests/*.json`:

```json
{"cases":[{"entry":"flows/main.json","expected":{"ok":true,"variables":{"message":"hello"}}}]}
```

For flow results, expected objects match the specified fields recursively; arrays and scalar values must match. For scripts, use the expected JSON result, for example `"layer-2-ok"`. Tests execute against isolated mocked memory/tools, a deterministic clock and no real AI/network/output. Built-in flow invariants run as well. Invalid tests, uncovered entries, failed verifiers, resource stops or mismatched results reject the pack.

Flows support bounded `call`, `ask_user`, `if`, `foreach`, `set`, `wait`, `verify`, `ai_write`, `on_error`; see [flow syntax](phase7-flow-format.md). QuickJS has a 16 MiB allocator ceiling, 512 KiB stack ceiling, a maximum 4-second interrupt deadline, 50 aggregate host calls and 64 KiB host JSON bounds. Dynamic evaluation and imports are disabled. See [QuickJS details](phase7-quickjs.md).

The library cap is **200 MiB (209,715,200 bytes)**, including installed/previous versions, pending reservations and plugin memory. The screen warns at 80%. Each imported archive is additionally limited to **32 MiB expanded**, so a single import cannot stage the entire library ceiling in the Android heap. Memory is atomically persisted with a 1 MiB per-pack and 8 MiB aggregate ceiling, loaded lazily on background host access rather than during startup. Delete requires confirmation and removes that pack's retained version and memory, not chats/models.

## Signing

The app trusts a separate device-local Android Keystore identity for builder drafts, or an owner public key explicitly pinned after fingerprint review. A key embedded in a pack is not automatically trusted. Local building never replaces the owner pin. Private keys never enter a pack.

Canonical signed bytes are UTF-8 JSON of the manifest **without `signature`**: recursively sorted object keys using UTF-16 ordering, array order preserved, integer numbers only, no insignificant whitespace or trailing newline, and `/` is not escaped. The signature is Base64 RSA/SHA-256 (minimum RSA 2048) or ECDSA/SHA-256 (minimum EC 256). Public keys use Base64 X.509 DER.

Source-only examples live in `examples/phase7/flow` and `examples/phase7/script`. Sign with an existing private key kept **outside** the payload folder:

```sh
python3 scripts/sign-redpack.py \
  --content examples/phase7/flow/payload \
  --manifest examples/phase7/flow/manifest-template.json \
  --key /private/owner.pem --algorithm RSA --output /tmp/example.redpack
```

Import only the printed **public** key after comparing its SHA-256 fingerprint. Changing the owner pin can make previous owner-signed versions unavailable; it does not affect the device-local signing identity.

## Rollback and safe mode

A different approved version retains one previous version. Rollback validates and dry-runs that previous version before swapping. Installation uses a recovery journal and same-filesystem renames, not a claim of a transactional filesystem or protection against every power-loss scenario.

A persisted tokenized marker identifies interrupted execution across process death. Three consecutive runtime failures disable the pack and enter global safe mode. Native death during dry-run also leaves a recovery marker. A successful actual run clears its failure streak; successful preparation does not. Leaving safe mode requires an explicit action and does not re-enable a disabled version. Roll back or approve a repaired version before retrying.

Host tests and ARMv7 packaging checks are **not** device acceptance. A25/A26 remain pending on the owner's Redmi 9.
