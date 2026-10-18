# Phase 7 declarative flow format

A flow is a JSON object with exactly one root field, `steps`, containing an array of at most 50 operation objects. Unknown keys are rejected. Nested branch/handler step arrays count toward the same structural limit. Nesting is limited to 12 levels; values are bounded to 4,096 JSON nodes, depth 12, and strings of at most 16,384 characters. Variable identifiers match `[A-Za-z_][A-Za-z0-9_]{0,63}`.

Variable substitution is the exact string form **`$name`**, for example `"value":"$message"`. It is not template interpolation; `${message}` is not supported.

## Operations

| `op` | Required fields | Optional fields |
|---|---|---|
| `set` | `name`, `value` | — |
| `call` | `tool` | `args` (object), `name` (result variable) |
| `ask_user` | `prompt` (string) | `name` (answer variable) |
| `if` | `condition` | `then`, `else` (step arrays) |
| `foreach` | `in`, `item`, `steps` (step array) | `index` |
| `wait` | `ms` (integer, 0–60,000) | — |
| `verify` | `value`, `equals` | `message` (accepted for forward-compatible schema, failure output remains stable) |
| `ai_write` | `prompt` (string), `name` | — |
| `on_error` | `steps` (step array) | `name` (captures a stable error code) |

`call.tool` must be declared by the invoking plugin manifest. A foreach source must resolve to an array with at most 20 entries. The total runtime execution budget is 50 operations, including repeated operations in loops and branches. The elapsed runtime and cumulative wait budget are each capped at five minutes. Dry-run never invokes host calls, approval, AI, or sleep; waits still consume cumulative virtual time and are rejected if they exceed the time budget.

Verification compares JSON values structurally, including numbers by numeric value (`1` equals `1.0`). Runtime errors return `ok:false` and a stable generic `error` code rather than exception text or flow content. `on_error` can handle ordinary operation failures but cannot suppress execution-budget, deadline, or permission failures. Host `SecurityException`s map to `FLOW_PERMISSION`; other host failures map to `FLOW_HOST_ERROR`.

## Minimal example

```json
{"steps":[
  {"op":"set","name":"message","value":"Hello"},
  {"op":"verify","value":"$message","equals":"Hello"}
]}
```
