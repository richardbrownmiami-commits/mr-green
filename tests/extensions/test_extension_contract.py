"""Source-level guardrails; Robolectric trust behavior tests live in app/src/test."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TRUST = ROOT / "app/src/main/java/im/mrgreen/extensions/ExtensionTrust.kt"
CLIENT = ROOT / "app/src/main/java/im/mrgreen/extensions/CompanionMessenger.kt"
ACTIVITY = ROOT / "app/src/main/java/im/mrgreen/extensions/ExtensionsActivity.kt"


def test_patch_checks_manifest_signature_hash_and_armv7_elf_before_staging():
    src = TRUST.read_text()
    assert 'manifest.abi == "armeabi-v7a"' in src
    assert '"Patch hash mismatch"' in src
    assert '"Patch signature rejected"' in src
    assert '"Explicit user approval required"' in src
    assert 'mrgreen-engine-patch-v1\\n${manifest.version}' in src
    assert '"Patch is not a little-endian ARM ELF32 shared library"' in src
    assert 'manifest.version.matches(Regex' in src


def test_companion_invocation_is_pinned_reviewed_and_confirmed_with_exact_args():
    src = TRUST.read_text()
    client = CLIENT.read_text()
    activity = ACTIVITY.read_text()
    assert 'ToolRisk.CONFIRM' in src and 'ToolRisk.BLOCKED' in src
    assert 'require(confirm(packageName, toolName, argsJson))' in client
    assert 'trust.isTrustedAndApproved(packageName, pinnedSigner)' in client
    assert 'queryIntentServices' in activity
    assert 'Arguments sent verbatim' in activity
    assert 'approveAfterUserConfirmation' in activity


def test_no_arbitrary_native_loader_or_dex_path():
    src = TRUST.read_text()
    assert 'deliberately never loads a caller-supplied library' in src
    assert 'System.load' not in src and 'DexClassLoader' not in src
