# Phase 9 integration: confirmed accessibility recipes

Package: `im.mrgreen.recipes`.

## Public API

- Launch `im.mrgreen.recipes.RecipesActivity` from the parent UI (non-exported activity recommended).
- `RecipeRuntime.execute(request: String): String?` is a suspend facade. JSON requests:
  - `{"recipe":"whatsapp.send","recipient":"exact visible contact","message":"text"}`
  - `{"recipe":"meta.prompt","prompt":"text"}`
  - `{"recipe":"gemini.prompt","prompt":"text"}`
  - `{"recipe":"meta.result"}` or `{"recipe":"gemini.result"}` to read bounded visible output from the already-open target app
  - `{"recipe":"wifi.settings"}`
- Null denotes unsupported/malformed JSON; human-readable non-null result always states what happened and does not overstate delivery or toggling.
- `RecipesActivity` supplies a foreground exact-content confirmation callback. Callers outside that screen must install `RecipeRuntime.confirmation` themselves or the operation fails closed.

## Required parent wiring

1. Declare `RecipeAccessibilityService` in the manifest as non-exported and protected with `android.permission.BIND_ACCESSIBILITY_SERVICE`; include `android.accessibilityservice.AccessibilityService` intent action and metadata referencing an accessibility service XML resource (`canRetrieveWindowContent=true`; do not enable gestures or screenshots). Add Android 11 `<queries>` entries for the three target packages. Add only the explicitly opted-in service UI entry point. Accessibility services require user enablement in Android Settings; starting the activity does not enable it.
2. Put service config under the parent-selected `res/xml` resource path. New service is intentionally distinct from `ScreenAssistantService`: that existing screen reader ignores background event text and is not an action bridge.
3. Launch RecipesActivity from an app-owned navigation entry. The parent owns these manifest/navigation edits.

## Safety/actual behavior

- WhatsApp opens only after foreground confirmation displaying the recipient and exact message. It validates current package and visible recipient text, fills an editable node, then clicks a clickable control whose exact label is `send` or `send message`. Failure/mismatch/time-out says nothing was sent; successful click is reported only as a send request, not delivery.
- Meta AI/Gemini package ids are validated before inspecting the UI. Prompts are placed in a visible editable field but never submitted; the user must review and tap Send. Unknown app layout or package mismatch fails closed.
- Result recipes revalidate expected app label/package and return at most 4,000 characters from visible accessibility text; they can include UI labels and may omit custom-rendered content.
- Wi-Fi recipe asks confirmation and opens Android Wi-Fi Settings only. Android 10+ third-party apps cannot programmatically toggle Wi-Fi; no toggle or resulting state is claimed.
- Node traversal is bounded to 300 nodes, each app wait is bounded to 12 seconds, and no screen tree is retained between recipe calls. No background send is supported.

## Limitations / verification

Package identifiers and UI labels vary by app version, locale, and OEM. Current WhatsApp and Meta AI screens may not expose recipient text or a button matching the exact labels, in which case the send path intentionally refuses to act. Meta AI package is Facebook (`com.facebook.katana`) and therefore needs an app/version-specific screen distinction not yet implemented; verify the target AI composer label before enabling that recipe. Android node text can be absent for custom-rendered controls. This module has not been run on a device, and parent must run host tests/build, add service XML/manifest wiring, then validate on Redmi 9/API 30. Experimental accessibility actions are not guaranteed by third-party apps.
