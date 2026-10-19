#!/usr/bin/env python3
"""Host source-contract guard, not Android/native runtime validation."""
from pathlib import Path
import os
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get('MR_GREEN_CONTROLLER', ROOT / 'app/src/main/java/im/mrgreen/core/AgentController.kt')).read_text()
CHAT = SOURCE.split('private suspend fun replyWithRulesOrModel', 1)[1].split('/** Explicit builder-only', 1)[0]
CLOUD = SOURCE.split('private fun tryCloud', 1)[1].split('fun cloudDisabled', 1)[0]
EPHEMERAL = SOURCE.split('suspend fun answerContext', 1)[1].split('private fun tryCloud', 1)[0]

class ConversationWiring(unittest.TestCase):
    def test_retrieval_runs_before_saved_replies_and_cloud(self):
        self.assertLess(CHAT.index('conversationMemory.retrieve'), CHAT.index('app.rive.reply'))
        self.assertLess(CHAT.index('conversationMemory.retrieve'), CHAT.index('tryCloud(text'))
        self.assertIn('if (needsMemory) null else app.rive.reply', CHAT)
        self.assertIn('if (!needsMemory &&', CHAT)
        self.assertIn('includeRecent = MemoryChatPolicy.needsRecent(text)', CHAT)

    def test_history_is_not_added_to_cloud(self):
        self.assertIn('ChatTurn("user", text)', CLOUD)
        for identifier in ('recalled', 'fitted', 'conversationMemory', 'store.messages', 'ConversationPrompt'):
            self.assertNotIn(identifier, CLOUD)

    def test_fitted_prompt_is_the_generated_prompt(self):
        self.assertIn('val turns = fitted.turns', CHAT)
        self.assertIn('engine.generate(turns,', CHAT)
        self.assertNotIn('store.messages()', CHAT)
        self.assertIn('full_history=false', CHAT)

    def test_partial_answers_are_not_persisted_or_auto_learned(self):
        self.assertIn('if (result.ok && !cancelled.get() && buffer.isNotBlank()) app.store.addMessage', CHAT)
        self.assertIn('val learnThisTurn = !needsMemory &&', CHAT)

    def test_finalized_routes_and_startup_sync(self):
        self.assertGreaterEqual(SOURCE.count('syncConversationMemory()'), 6)
        self.assertIn('app.store.addMessage("assistant", result.text)\n            syncConversationMemory()', CLOUD)

    def test_screen_context_remains_ephemeral(self):
        self.assertNotIn('addMessage', EPHEMERAL)
        self.assertNotIn('conversationMemory', EPHEMERAL)
        self.assertIn('category != "screen"', EPHEMERAL)

if __name__ == '__main__':
    unittest.main(verbosity=2)
